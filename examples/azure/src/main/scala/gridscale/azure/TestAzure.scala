package gridscale.azure

import java.io.{ ByteArrayOutputStream, OutputStream }

import com.microsoft.azure.batch.protocol.models.TaskAddParameter

object TestAzure extends App {

  import com.microsoft.azure.batch.auth.BatchSharedKeyCredentials;
  import com.microsoft.azure.batch.BatchClient;
  import com.microsoft.azure.batch.protocol.models.{ CloudPool, ImageReference, VirtualMachineConfiguration, PoolInformation }

  val client: BatchClient = getBatchClient()
  val poolId = "test-pool-2"
  val jobId = "test-job-2"
  val taskId = "test-task-2"

  createPoolIfNotExists(client, poolId)
  createJob(client, poolId, jobId)
  addTaskToJob(client, jobId, taskId)
  waitForTaskCompletion(client, jobId, taskId)
  // Uncomment after waitForTaskCompletion is implemented
  //printTaskOutput(client, jobId, taskId)
  deletePool(client, poolId)

  // Get a batch client
  def getBatchClient(): BatchClient = {
    val batchUri = sys.env("BATCH_URI")
    val batchAccount = sys.env("BATCH_ACCOUNT")
    val batchKey = sys.env("BATCH_KEY")
    val creds: BatchSharedKeyCredentials = new BatchSharedKeyCredentials(batchUri, batchAccount, batchKey)
    return BatchClient.open(creds)
  }

  // Create a pool
  def createPoolIfNotExists(client: BatchClient, poolId: String): CloudPool = {
    // Assume pool properties
    val poolVMSize = "STANDARD_A1"
    val poolVMCount = 1

    val osPublisher = "Canonical"
    val osOffer = "UbuntuServer"
    val skuId = "16.04-LTS"
    val nodeAgentSkuId = "batch.node.ubuntu 16.04"
    val imgRef = new ImageReference().withOffer(osOffer).withPublisher(osPublisher).withSku(skuId)
    val configuration: VirtualMachineConfiguration = new VirtualMachineConfiguration()
    configuration.withNodeAgentSKUId(nodeAgentSkuId).withImageReference(imgRef)
    if (!client.poolOperations().existsPool(poolId)) {
      client.poolOperations().createPool(poolId, poolVMSize, configuration, poolVMCount)
    }

    // TODO: Add better blocking
    while (!client.poolOperations().existsPool(poolId)) {
      Thread.sleep(30000)
    }

    return client.poolOperations().getPool(poolId)
  }

  // Create job
  def createJob(client: BatchClient, poolId: String, jobId: String): Unit = {
    val poolInfo = new PoolInformation
    poolInfo.withPoolId(poolId)
    client.jobOperations.createJob(jobId, poolInfo)
  }

  // Add task to job
  def addTaskToJob(client: BatchClient, jobId: String, taskId: String): Unit = {
    val task: TaskAddParameter = new TaskAddParameter
    task.withId(taskId).withCommandLine(String.format("echo idea2"))
    client.taskOperations().createTask(jobId, task)
  }

  //TODO: Implement
  def waitForTaskCompletion(client: BatchClient, jobId: String, taskId: String): Unit = {
  }

  // Get stdout and stderr
  def printTaskOutput(client: BatchClient, jobId: String, taskId: String): Unit = {
    var stream = new ByteArrayOutputStream
    val file = "stdout.txt"
    client.fileOperations().getFileFromTask(jobId, taskId, file, null, stream)
    val fileContents = stream.toString("UTF-8")
    println("Task file: " + file)
    println("File output : " + fileContents)
  }

  // Delete pool
  def deletePool(client: BatchClient, poolId: String): Unit = {
    client.poolOperations().deletePool(poolId)
  }
}
