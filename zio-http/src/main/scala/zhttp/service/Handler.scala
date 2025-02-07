package zhttp.service

import io.netty.buffer.{ByteBuf, ByteBufUtil, Unpooled}
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.handler.codec.http.HttpResponseStatus._
import io.netty.handler.codec.http.HttpVersion._
import io.netty.handler.codec.http._
import zhttp.http.HttpApp.InvalidMessage
import zhttp.http._
import zhttp.service.server.{ServerTimeGenerator, WebSocketUpgrade}
import zio.stream.ZStream
import zio.{Chunk, Promise, UIO, ZIO}

import java.net.{InetAddress, InetSocketAddress}

final case class Handler[R] private[zhttp] (
  app: HttpApp[R, Throwable],
  runtime: HttpRuntime[R],
  config: Server.Config[R, Throwable],
  serverTime: ServerTimeGenerator,
) extends ChannelInboundHandlerAdapter
    with WebSocketUpgrade[R] { self =>

  private val cBody: ByteBuf                                            = Unpooled.compositeBuffer()
  private var decoder: ContentDecoder[Any, Throwable, Chunk[Byte], Any] = _
  private var completePromise: Promise[Throwable, Any]                  = _
  private var isFirst: Boolean                                          = true
  private var decoderState: Any                                         = _
  private var jReq: HttpRequest                                         = _
  private var request: Request                                          = _

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
    val void = ctx.voidPromise()

    /**
     * Writes ByteBuf data to the Channel
     */
    def unsafeWriteLastContent[A](data: ByteBuf): Unit = {
      ctx.writeAndFlush(new DefaultLastHttpContent(data), void): Unit
    }

    /**
     * Writes last empty content to the Channel
     */
    def unsafeWriteAndFlushLastEmptyContent(): Unit = {
      ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT, void): Unit
    }

    /**
     * Writes Binary Stream data to the Channel
     */
    def writeStreamContent[A](stream: ZStream[R, Throwable, ByteBuf]) = {
      stream.process.map { pull =>
        def loop: ZIO[R, Throwable, Unit] = pull
          .foldM(
            {
              case None        => UIO(ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT, void)).unit
              case Some(error) => ZIO.fail(error)
            },
            chunks =>
              for {
                _ <- ZIO.foreach_(chunks)(buf => UIO(ctx.write(new DefaultHttpContent(buf), void)))
                _ <- UIO(ctx.flush())
                _ <- loop
              } yield (),
          )

        loop
      }.useNow.flatten
    }

    /**
     * Writes any response to the Channel
     */
    def unsafeWriteAnyResponse[A](res: Response[R, Throwable]): Unit = {
      ctx.write(decodeResponse(res), void): Unit
    }

    /**
     * Executes http apps
     */
    def unsafeRun[A](http: Http[R, Throwable, A, Response[R, Throwable]], a: A): Unit = {
      http.execute(a).evaluate match {
        case HExit.Effect(resM) =>
          unsafeRunZIO {
            resM.foldM(
              {
                case Some(cause) => UIO(unsafeWriteAndFlushErrorResponse(cause))
                case None        => UIO(unsafeWriteAndFlushEmptyResponse())
              },
              res =>
                if (self.canSwitchProtocol(res)) UIO(self.initializeSwitch(ctx, res))
                else {
                  for {
                    _ <- UIO(unsafeWriteAnyResponse(res))
                    _ <- res.data match {
                      case HttpData.Empty =>
                        UIO(unsafeWriteAndFlushLastEmptyContent())

                      case data @ HttpData.Text(_, _) =>
                        UIO(unsafeWriteLastContent(data.encodeAndCache(res.attribute.memoize)))

                      case HttpData.BinaryByteBuf(data) => UIO(unsafeWriteLastContent(data))

                      case data @ HttpData.BinaryChunk(_) =>
                        UIO(unsafeWriteLastContent(data.encodeAndCache(res.attribute.memoize)))

                      case HttpData.BinaryStream(stream) =>
                        writeStreamContent(stream.mapChunks(a => Chunk(Unpooled.copiedBuffer(a.toArray))))
                    }
                  } yield ()
                },
            )
          }

        case HExit.Success(res) =>
          if (self.canSwitchProtocol(res)) {
            self.initializeSwitch(ctx, res)
          } else {
            unsafeWriteAnyResponse(res)

            res.data match {
              case HttpData.Empty =>
                unsafeWriteAndFlushLastEmptyContent()

              case data @ HttpData.Text(_, _) =>
                unsafeWriteLastContent(data.encodeAndCache(res.attribute.memoize))

              case HttpData.BinaryByteBuf(data) =>
                unsafeWriteLastContent(data)

              case data @ HttpData.BinaryChunk(_) =>
                unsafeWriteLastContent(data.encodeAndCache(res.attribute.memoize))

              case HttpData.BinaryStream(stream) =>
                unsafeRunZIO(writeStreamContent(stream.mapChunks(a => Chunk(Unpooled.copiedBuffer(a.toArray)))))
            }
          }

        case HExit.Failure(e) => unsafeWriteAndFlushErrorResponse(e)
        case HExit.Empty      => unsafeWriteAndFlushEmptyResponse()
      }
    }

    /**
     * Writes error response to the Channel
     */
    def unsafeWriteAndFlushErrorResponse(cause: Throwable): Unit = {
      ctx.writeAndFlush(serverErrorResponse(cause), void): Unit
    }

    /**
     * Writes not found error response to the Channel
     */
    def unsafeWriteAndFlushEmptyResponse(): Unit = {
      ctx.writeAndFlush(notFoundResponse, void): Unit
    }

    /**
     * Executes program
     */
    def unsafeRunZIO(program: ZIO[R, Throwable, Any]): Unit = runtime.unsafeRun(ctx) {
      program
    }

    /**
     * Decodes content and executes according to the ContentDecoder provided
     */
    def decodeContent(
      content: ByteBuf,
      decoder: ContentDecoder[Any, Throwable, Chunk[Byte], Any],
      isLast: Boolean,
    ): Unit = {
      decoder match {
        case ContentDecoder.Text =>
          cBody.writeBytes(content)
          if (isLast) {
            unsafeRunZIO(self.completePromise.succeed(cBody.toString(HTTP_CHARSET)))
          } else {
            ctx.read(): Unit
          }

        case step: ContentDecoder.Step[_, _, _, _, _] =>
          if (self.isFirst) {
            self.decoderState = step.state
            self.isFirst = false
          }
          val nState = self.decoderState

          unsafeRunZIO(for {
            (publish, state) <- step
              .asInstanceOf[ContentDecoder.Step[R, Throwable, Any, Chunk[Byte], Any]]
              .next(
                // content.array() fails with post request with body
                // Link: https://livebook.manning.com/book/netty-in-action/chapter-5/54
                Chunk.fromArray(ByteBufUtil.getBytes(content)),
                nState,
                isLast,
                self.request.method,
                self.request.url,
                self.request.getHeaders,
              )
            _                <- publish match {
              case Some(out) => self.completePromise.succeed(out)
              case None      => ZIO.unit
            }
            _                <- UIO {
              self.decoderState = state
              if (!isLast) ctx.read(): Unit
            }
          } yield ())

      }
    }

    msg match {
      case jRequest: HttpRequest =>
        // TODO: Unnecessary requirement
        // `autoRead` is set when the channel is registered in the event loop.
        // The explicit call here is added to make unit tests work properly
        ctx.channel().config().setAutoRead(false)
        self.jReq = jRequest
        self.request = new Request {
          override def decodeContent[R0, B](
            decoder: ContentDecoder[R0, Throwable, Chunk[Byte], B],
          ): ZIO[R0, Throwable, B] =
            ZIO.effectSuspendTotal {
              if (self.decoder != null)
                ZIO.fail(ContentDecoder.Error.ContentDecodedOnce)
              else
                for {
                  p <- Promise.make[Throwable, B]
                  _ <- UIO {
                    self.decoder = decoder
                      .asInstanceOf[ContentDecoder[Any, Throwable, Chunk[Byte], B]]
                    self.completePromise = p.asInstanceOf[Promise[Throwable, Any]]
                    ctx.read(): Unit
                  }
                  b <- p.await
                } yield b
            }

          override def method: Method                     = Method.fromHttpMethod(jRequest.method())
          override def url: URL                           = URL.fromString(jRequest.uri()).getOrElse(null)
          override def getHeaders: List[Header]           = Header.make(jRequest.headers())
          override def remoteAddress: Option[InetAddress] = {
            ctx.channel().remoteAddress() match {
              case m: InetSocketAddress => Some(m.getAddress())
              case _                    => None
            }
          }
        }
        unsafeRun(
          app.asHttp.asInstanceOf[Http[R, Throwable, Request, Response[R, Throwable]]],
          self.request,
        )

      case msg: LastHttpContent =>
        if (self.isInitialized) {
          self.switchProtocol(ctx, jReq)
        } else if (decoder != null) {
          decodeContent(msg.content(), decoder, true)
        }

        // TODO: add unit tests
        // Channels are reused when keep-alive header is set.
        // So auto-read needs to be set to true once the first request is processed.
        ctx.channel().config().setAutoRead(true): Unit

      case msg: HttpContent =>
        if (decoder != null) {
          decodeContent(msg.content(), decoder, false)
        }

      case msg => ctx.fireExceptionCaught(InvalidMessage(msg)): Unit
    }
  }

  private def decodeResponse(res: Response[_, _]): HttpResponse = {
    if (res.attribute.memoize) decodeResponseCached(res) else decodeResponseFresh(res)
  }

  private def decodeResponseFresh(res: Response[_, _]): HttpResponse = {
    val jHeaders = Header.disassemble(res.getHeaders)
    if (res.attribute.serverTime) jHeaders.set(HttpHeaderNames.DATE, serverTime.refreshAndGet())
    new DefaultHttpResponse(HttpVersion.HTTP_1_1, res.status.asJava, jHeaders)
  }

  private def decodeResponseCached(res: Response[_, _]): HttpResponse = {
    val cachedResponse = res.cache
    // Update cache if it doesn't exist OR has become stale
    // TODO: add unit tests for server-time
    if (cachedResponse == null || (res.attribute.serverTime && serverTime.canUpdate())) {
      val jRes = decodeResponseFresh(res)
      res.cache = jRes
      jRes
    } else cachedResponse
  }

  private val notFoundResponse: HttpResponse = {
    val response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND, false)
    response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0)
    response
  }

  private def serverErrorResponse(cause: Throwable): HttpResponse = {
    val content  = cause.toString
    val response = new DefaultFullHttpResponse(
      HTTP_1_1,
      INTERNAL_SERVER_ERROR,
      Unpooled.copiedBuffer(content, HTTP_CHARSET),
    )
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length)
    response
  }
}
