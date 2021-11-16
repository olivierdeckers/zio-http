import sbtghactions.GenerativePlugin.autoImport.{UseRef, WorkflowJob, WorkflowStep}

object BenchmarkWorkFlow {
  object benchmark {
    def apply(): Seq[WorkflowJob] = Seq(
      WorkflowJob(
        id = "runBenchMarks",
        name = "Benchmarks",
        steps = List(
          WorkflowStep.Checkout,
          WorkflowStep.Use(
            UseRef.Public("actions", "checkout", s"v2"),
            Map(
              "repository" -> "amitksingh1490/FrameworkBenchmarks",
              "path"       -> "FrameworkBenchMarks",
            ),
          ),
          WorkflowStep.Run(
            commands = List(
              "pwd",
              "ls ./",
              "cd ./FrameworkBenchMarks",
              "echo ::set-output name=benchmarks::$(./tfb  --test zio-http | grep -i -B 18 'Concurrency: 256 for plaintext')",
            ),
          ),
        ),
      ),
    )
  }

}
