import com.microsoft.azure.batch.BatchClient
import gridscale.azure
import gridscale.azure.{ AzureBatchAuthentication, AzureJobDescription, AzurePoolConfiguration, AzureTaskConfiguration }
import gridscale.benchmark.Benchmark
import gridscale.benchmark.util.IO
import gridscale.cluster.BatchScheduler.BatchJob
import gridscale.cluster.{ ClusterInterpreter, HeadNode }

object AzureBenchmark {

  def run(client: BatchClient)(nbJobs: Int, runs: Int) = {

    // Create pool
    val POOL_DISPLAY_NAME = "Test Pool"
    val POOL_VM_SIZE = "STANDARD_A1"
    val POOL_VM_COUNT = 1
    val POOL_OS_PUBLISHER = "Canonical"
    val POOL_OS_OFFER = "UbuntuServer"
    val POOL_SKUID = "16.04-LTS"
    val POOL_STEADY_TIMEOUT_IN_SECONDS = 5 * 60 * 1000
    val NODE_AGENT_SKUID = "batch.node.ubuntu 16.04"

    val poolConfig = AzurePoolConfiguration(
      osPublisher = POOL_OS_PUBLISHER,
      osOffer = POOL_OS_OFFER,
      osSku = POOL_SKUID,
      vmSize = POOL_VM_SIZE,
      dedicatedNodes = POOL_VM_COUNT,
      lowPriorityNodes = 0)

    val cmd: String = s"sleep 1000"

    val taskConfig: AzureTaskConfiguration = AzureTaskConfiguration(
      cmdLine = cmd,
      resourceFiles = null)

    val jobDescription: AzureJobDescription = AzureJobDescription(poolConfig, taskConfig)

    implicit val benchmark = new Benchmark[AzureJobDescription] {
      override def submit(jobDescription: AzureJobDescription): BatchJob = azure.submit(client, jobDescription)
      override def state(job: BatchJob): gridscale.JobState = azure.state(client, job)
      override def clean(job: BatchJob): Unit = azure.clean(client, job)
    }

    implicit val headnode: HeadNode[BatchClient] = null

    Benchmark.avgBenchmark(client)(jobDescription, nbJobs, runs)
  }

  def main(argv: Array[String]): Unit = {
    //val params = IO.parseArgs(argv)
    val nbJobs: Int = 1
    val nbRuns: Int = 1

    val res = ClusterInterpreter { intp ⇒
      import intp._

      val auth: AzureBatchAuthentication = AzureBatchAuthentication(
        batchAccountName = "batchtrial",
        batchAccountUri = "https://batchtrial.westeurope.batch.azure.com",
        batchAccountKey = "SLQLeXs4G43OcRF51K2qpJ8uGBxHPvpQ8LVX2JmmkmKB/OxUX6hA0MUZ10JA8GpWTb3c9F9wiOSipjjJpJjWZQ::")

      val client = azure.getBatchClient(auth)

      run(client)(nbJobs, nbRuns)
    }

    println(IO.format(res, nbJobs, nbRuns))
  }
}
