package zhttp.http

import io.netty.channel._
import zhttp.http.middleware.{HttpMiddleware, Patch}
import zhttp.service.server.ServerTimeGenerator
import zhttp.service.{Handler, HttpRuntime, Server}
import zio._
import zio.clock.Clock
import zio.duration.Duration

import java.nio.charset.Charset

case class HttpApp[-R, +E](asHttp: Http[R, E, Request, Response[R, E]]) {
  self =>
  def orElse[R1 <: R, E1 >: E](other: HttpApp[R1, E1]): HttpApp[R1, E1] =
    HttpApp(self.asHttp orElse other.asHttp)

  def defaultWith[R1 <: R, E1 >: E](other: HttpApp[R1, E1]): HttpApp[R1, E1] =
    HttpApp(self.asHttp defaultWith other.asHttp)

  def <>[R1 <: R, E1 >: E](other: HttpApp[R1, E1]): HttpApp[R1, E1] = self orElse other

  def +++[R1 <: R, E1 >: E](other: HttpApp[R1, E1]): HttpApp[R1, E1] = self defaultWith other

  /**
   * Converts a failing Http app into a non-failing one by handling the failure and converting it to a result if
   * possible.
   */
  def silent[R1 <: R, E1 >: E](implicit s: CanBeSilenced[E1, Response[R1, E1]]): HttpApp[R1, E1] =
    self.catchAll(e => Http.succeed(s.silent(e)).toApp)

  /**
   * Combines multiple Http apps into one
   */
  def combine[R1 <: R, E1 >: E](i: Iterable[HttpApp[R1, E1]]): HttpApp[R1, E1] =
    i.reduce(_.defaultWith(_))

  /**
   * Catches all the exceptions that the http app can fail with
   */
  def catchAll[R1 <: R, E1](f: E => HttpApp[R1, E1])(implicit ev: CanFail[E]): HttpApp[R1, E1] =
    HttpApp(self.asHttp.catchAll(f(_).asHttp).asInstanceOf[Http[R1, E1, Request, Response[R1, E1]]])

  private[zhttp] def compile[R1 <: R, E1 >: E](
    zExec: HttpRuntime[R1],
    settings: Server.Config[R1, Throwable],
    serverTime: ServerTimeGenerator,
  )(implicit
    evE: E1 <:< Throwable,
  ): ChannelHandler =
    Handler[R1](self.asInstanceOf[HttpApp[R1, Throwable]], zExec, settings, serverTime)

  /**
   * Executes the HttpApp and produces a Response
   */
  def apply(req: Request): ZIO[R, Option[E], Response[R, E]] = self.asHttp.execute(req).evaluate.asEffect

  /**
   * Attaches the provided middleware to the HttpApp
   */
  def middleware[R1 <: R, E1 >: E](mid: HttpMiddleware[R1, E1]): HttpApp[R1, E1] = mid(self)

  /**
   * Delays the response by the provided duration
   */
  def delayAfter(duration: Duration): HttpApp[R with Clock, E] = HttpApp(asHttp.delayAfter(duration))

  /**
   * Delays the execution of the app by the provided duration
   */
  def delayBefore(duration: Duration): HttpApp[R with Clock, E] = HttpApp(asHttp.delayBefore(duration))

  /**
   * Attaches the provided middleware to the HttpApp
   */
  def @@[R1 <: R, E1 >: E](mid: HttpMiddleware[R1, E1]): HttpApp[R1, E1] = self.middleware(mid)

  /**
   * Performs a race between two apps
   */
  def race[R1 <: R, E1 >: E](other: HttpApp[R1, E1]): HttpApp[R1, E1] =
    HttpApp(self.asHttp race other.asHttp)

  /**
   * Patches the response produced by the app
   */
  def patch(patch: Patch): HttpApp[R, E] = HttpApp(self.asHttp.map(patch(_)))

  /**
   * Adds the provided headers to the response of the app
   */
  def addHeaders(headers: List[Header]): HttpApp[R, E] = self.patch(Patch.addHeaders(headers))

  /**
   * Adds the provided headers to the response of the app
   */
  def addHeader(header: Header): HttpApp[R, E] = self.patch(Patch.addHeader(header))

  /**
   * Adds the provided header to the response of the app
   */
  def addHeader(name: String, value: String): HttpApp[R, E] = self.patch(Patch.addHeader(name, value))

  /**
   * Sets the status in the response produced by the app
   */
  def setStatus(status: Status): HttpApp[R, E] = self.patch(Patch.setStatus(status))

  /**
   * Modifies the outgoing response from the app
   */
  def modifyResponse[R1 <: R, E1 >: E](f: Response[R, E] => Response[R1, E1]): HttpApp[R1, E1] =
    HttpApp(asHttp.map(f))

  /**
   * Modifies the outgoing response from the app effectfully
   */
  def modifyResponseM[R1 <: R, E1 >: E](f: Response[R, E] => ZIO[R1, E1, Response[R1, E1]]): HttpApp[R1, E1] =
    HttpApp(asHttp.mapM(f))

  /**
   * Modifies the incoming request to the app
   */
  def modifyRequest(f: Request => Request): HttpApp[R, E] =
    HttpApp(asHttp.contramap(f))

  /**
   * Modifies the incoming request to the app effectfully
   */
  def modifyRequestM[R1 <: R, E1 >: E](f: Request => ZIO[R1, E1, Request]): HttpApp[R1, E1] =
    HttpApp(asHttp.contramapM(f))
}

object HttpApp {

  final case class InvalidMessage(message: Any) extends IllegalArgumentException {
    override def getMessage: String = s"Endpoint could not handle message: ${message.getClass.getName}"
  }

  /**
   * Creates an Http app from an Http type
   */
  def fromHttp[R, E](http: Http[R, E, Request, Response[R, E]]): HttpApp[R, E] = HttpApp(http)

  /**
   * Creates an Http app which always fails with the same error.
   */
  def fail[E](cause: E): HttpApp[Any, E] = HttpApp(Http.fail(cause))

  /**
   * Creates an Http app which always responds with empty data.
   */
  def empty: HttpApp[Any, Nothing] = HttpApp(Http.empty)

  /**
   * Creates an Http app which accepts a request and produces response.
   */
  def collect[R, E](pf: PartialFunction[Request, Response[R, E]]): HttpApp[R, E] =
    HttpApp(Http.collect(pf))

  /**
   * Creates an Http app which accepts a requests and produces a ZIO as response.
   */
  def collectM[R, E](pf: PartialFunction[Request, ZIO[R, E, Response[R, E]]]): HttpApp[R, E] =
    HttpApp(Http.collectM(pf))

  /**
   * Creates an Http app from a function that returns a ZIO
   */
  def fromEffectFunction[R, E](f: Request => ZIO[R, E, Response[R, E]]): HttpApp[R, E] =
    HttpApp(Http.fromEffectFunction(f))

  /**
   * Converts a ZIO to an Http app type
   */
  def responseM[R, E](res: ZIO[R, E, Response[R, E]]): HttpApp[R, E] = HttpApp(Http.fromEffect(res))

  /**
   * Creates an Http app which always responds with the same plain text.
   */
  def text(str: String, charset: Charset = HTTP_CHARSET): HttpApp[Any, Nothing] = HttpApp(
    Http.succeed(Response.text(str, charset)),
  )

  /**
   * Creates an Http app which always responds with the same value.
   */
  def response[R, E](response: Response[R, E]): HttpApp[R, E] = HttpApp(Http.succeed(response))

  /**
   * Creates an Http app that fails with a NotFound exception.
   */
  def notFound: HttpApp[Any, Nothing] = HttpApp.fromFunction(req => HttpApp.error(HttpError.NotFound(req.url.path)))

  /**
   * Creates an HTTP app which always responds with the same status code and empty data.
   */
  def status(code: Status): HttpApp[Any, Nothing] = HttpApp(Http.succeed(Response(code)))

  /**
   * Creates an Http app that responds with 403 - Forbidden status code
   */
  def forbidden(msg: String): HttpApp[Any, Nothing] = HttpApp.error(HttpError.Forbidden(msg))

  /**
   * Creates an HTTP app which always responds with a 200 status code.
   */
  def ok: HttpApp[Any, Nothing] = status(Status.OK)

  /**
   * Creates an HTTP app with HttpError.
   */
  def error(cause: HttpError): HttpApp[Any, Nothing] = HttpApp.response(Response.fromHttpError(cause))

  /**
   * Creates an HTTP app which always responds with a 400 status code.
   */
  def badRequest(msg: String): HttpApp[Any, Nothing] = HttpApp.error(HttpError.BadRequest(msg))

  /**
   * Creates an Http app that responds with 500 status code
   */
  def error(msg: String): HttpApp[Any, Nothing] = HttpApp.error(HttpError.InternalServerError(msg))

  /**
   * Creates a Http app from a function from Request to HttpApp
   */
  def fromFunction[R, E, B](f: Request => HttpApp[R, E]): HttpApp[R, E] =
    HttpApp(Http.fromFunction[Request](f(_).asHttp).flatten)

  /**
   * Creates a Http app from a function from Request to ZIO[R,E,HttpApp[R,E]]
   */
  def fromFunctionM[R, E, B](f: Request => ZIO[R, E, HttpApp[R, E]]): HttpApp[R, E] =
    HttpApp(Http.fromFunctionM[Request](f(_).map(_.asHttp)).flatten)

  /**
   * Creates a Http app from a partial function from Request to HttpApp
   */
  def fromOptionFunction[R, E, A, B](f: Request => ZIO[R, Option[E], Response[R, E]]): HttpApp[R, E] =
    HttpApp(Http.fromPartialFunction(f))
}
