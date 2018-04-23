package gridscale.azure

import java.io.{ ByteArrayOutputStream, OutputStream }
import java.util

import com.microsoft.azure.batch.DetailLevel
import com.microsoft.azure.batch.DetailLevel.Builder
import com.microsoft.azure.batch.protocol.models._

object TestAzure extends App {

  import com.microsoft.azure.batch.auth.BatchSharedKeyCredentials;
  import com.microsoft.azure.batch.BatchClient;
  import com.microsoft.azure.batch.protocol.models.{ CloudPool, ImageReference, VirtualMachineConfiguration, PoolInformation }

  val client: BatchClient = getBatchClient()
  val poolId = "pool-test"
  val jobId = "job-test"
  val taskId1 = "task-test-1"
  val taskId2 = "task-test-2"
  val taskId3 = "task-test-3"

  createPoolIfNotExists(client, poolId)
  createJob(client, poolId, jobId)
  addTaskToJob(client, jobId, taskId1)
  addTaskToJob(client, jobId, taskId2)
  addTaskToJob(client, jobId, taskId3)
  waitForJobCompletion(client, jobId, 1000)
  printTaskOutput(client, jobId, taskId1)
  printTaskOutput(client, jobId, taskId2)
  printTaskOutput(client, jobId, taskId3)
  deletePool(client, poolId)

  // Get a batch client
  def getBatchClient(): BatchClient = {
    println("Creating Batch Client")
    val batchUri = sys.env("AZURE_BATCH_ENDPOINT")
    val batchAccount = sys.env("AZURE_BATCH_ACCOUNT")
    val batchKey = sys.env("AZURE_BATCH_ACCESS_KEY")
    val creds: BatchSharedKeyCredentials = new BatchSharedKeyCredentials(batchUri, batchAccount, batchKey)
    return BatchClient.open(creds)
  }

  // Create a pool
  def createPoolIfNotExists(client: BatchClient, poolId: String): CloudPool = {
    println("Creating Pool: " + poolId)
    // Return pool if already exists
    if (client.poolOperations().existsPool(poolId)) {
      return client.poolOperations().getPool(poolId)
    }

    // Assume virtual machine properties
    val POOL_DISPLAY_NAME = "Test Pool"
    val POOL_VM_SIZE = "STANDARD_A1"
    val POOL_VM_COUNT = 1
    val POOL_OS_PUBLISHER = "Canonical"
    val POOL_OS_OFFER = "UbuntuServer"
    val POOL_SKUID = "16.04-LTS"
    val POOL_STEADY_TIMEOUT_IN_SECONDS = 5 * 60 * 1000
    val NODE_AGENT_SKUID = "batch.node.ubuntu 16.04"

    // Create a virtual machine configuration
    val imgRef = new ImageReference().withOffer(POOL_OS_OFFER).withPublisher(POOL_OS_PUBLISHER).withSku(POOL_SKUID)
    val poolVMConfiguration = new VirtualMachineConfiguration()
      .withNodeAgentSKUId(NODE_AGENT_SKUID)
      .withImageReference(imgRef)

    // Create object with pool parameters
    val poolAddParameter: PoolAddParameter = new PoolAddParameter()
      .withId(poolId)
      .withDisplayName(POOL_DISPLAY_NAME)
      .withTargetDedicatedNodes(POOL_VM_COUNT)
      .withVmSize(POOL_VM_SIZE)
      .withVirtualMachineConfiguration(poolVMConfiguration)

    // Create pool if it does not exist
    if (!client.poolOperations().existsPool(poolId)) {
      client.poolOperations().createPool(poolAddParameter)
    }

    // Wait until pool created within bounds
    val startTime = System.currentTimeMillis()
    var timeElapsed = 0L
    var steady = false
    var pool: CloudPool = client.poolOperations().getPool(poolId)

    while (pool.allocationState() != AllocationState.STEADY) {
      if (timeElapsed > POOL_STEADY_TIMEOUT_IN_SECONDS) {
        throw new Exception("Could not create the pool within time")
      }
      pool = client.poolOperations().getPool(poolId)
      Thread.sleep(30 * 1000)
      timeElapsed = System.currentTimeMillis() - startTime
    }

    return client.poolOperations().getPool(poolId)
  }

  // Create job
  def createJob(client: BatchClient, poolId: String, jobId: String): Unit = {
    println("Creating Job: " + jobId)
    val poolInfo = new PoolInformation
    poolInfo.withPoolId(poolId)
    client.jobOperations.createJob(jobId, poolInfo)
  }

  // Add task to job
  def addTaskToJob(client: BatchClient, jobId: String, taskId: String): Unit = {
    println("Adding task " + taskId + " to job " + jobId)
    val task: TaskAddParameter = new TaskAddParameter
    task.withId(taskId).withCommandLine(String.format("echo idea2"))
    client.taskOperations().createTask(jobId, task)
  }

  // Waits for all tasks in a job to complete
  def waitForJobCompletion(client: BatchClient, jobId: String, expiryTimeInSeconds: Integer): Boolean = {
    println("Waiting for job to finish: " + jobId)
    val startTime = System.currentTimeMillis()
    var elapsedTime = 0L

    while (elapsedTime < expiryTimeInSeconds * 1000) {
      val tasks: util.List[CloudTask] = client.taskOperations().listTasks(jobId, new DetailLevel.Builder().withSelectClause("id, state").build())
      println("Total tasks: " + tasks.size())
      var allComplete = true
      // TODO: What is a good Scala way for something like this
      for (i ← 0 until tasks.size()) {
        if (tasks.get(i).state() != TaskState.COMPLETED) {
          allComplete = false
        }
      }

      if (allComplete) {
        println("All jobs complete")
        return true
      }

      Thread.sleep(10 * 1000)
      elapsedTime = System.currentTimeMillis() - startTime
    }

    return false
  }

  // Get stdout and stderr
  def printTaskOutput(client: BatchClient, jobId: String, taskId: String): Unit = {
    println("Fetching stdout")
    var stream = new ByteArrayOutputStream
    val file = "stdout.txt"
    client.fileOperations().getFileFromTask(jobId, taskId, file, null, stream)
    val fileContents = stream.toString("UTF-8")
    println("Task file: " + file)
    println("File output : " + fileContents)
  }

  // Delete pool
  def deletePool(client: BatchClient, poolId: String): Unit = {
    println("Deleting Pool: " + poolId)
    client.poolOperations().deletePool(poolId)
  }
}
