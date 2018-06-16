package gridscale

import java.io.{ ByteArrayOutputStream, File, FileInputStream, IOException }
import java.security.InvalidKeyException
import java.util.Date
import java.util.concurrent.TimeoutException

import com.microsoft.azure.AzureClient
import com.microsoft.azure.batch.{ BatchClient, DetailLevel }
import com.microsoft.azure.batch.auth.BatchSharedKeyCredentials
import com.microsoft.azure.batch.protocol.models._
import com.microsoft.azure.storage.{ CloudStorageAccount, StorageCredentialsAccountAndKey, StorageException, blob }
import gridscale.JobState.Submitted
import gridscale.cluster.BatchScheduler.BatchJob

package object azure {

  case class AzureBatchAuthentication(batchAccountName: String, batchAccountUri: String, batchAccountKey: String)
  case class AzureStorageAuthentication(storageAccountName: String, storageAccountKey: String)

  case class AzurePoolConfiguration(
    osPublisher: String,
    osOffer: String,
    osSku: String,
    vmSize: String,
    dedicatedNodes: Int,
    lowPriorityNodes: Int)

  case class AzureTaskConfiguration(cmdLine: String, resourceFiles: java.util.ArrayList[ResourceFile])

  case class AzureJobDescription(poolConfig: AzurePoolConfiguration, taskConfig: AzureTaskConfiguration)

  // random job and pool name
  val jobId = s"job-${java.util.UUID.randomUUID().toString}"
  val poolId = s"pool-${java.util.UUID.randomUUID().toString}"
  val taskRandom = s"task-${java.util.UUID.randomUUID().toString}"
  var taskCount = 0

/*** ---------------------------Authentication--------------------------- ***/

  // Get a batch client
  @throws(classOf[NoSuchElementException])
  @throws(classOf[IllegalArgumentException])
  def getBatchClient(azureAuthentication: AzureBatchAuthentication): BatchClient = {
    // TODO: Create account if necessary?
    println("Creating Batch Client")

    val creds: BatchSharedKeyCredentials = new BatchSharedKeyCredentials(
      azureAuthentication.batchAccountUri,
      azureAuthentication.batchAccountName,
      azureAuthentication.batchAccountKey)

    return BatchClient.open(creds)
  }

/*** ---------------------------Resource Creation--------------------------- ***/
  // Create a pool
  @throws(classOf[InterruptedException])
  @throws(classOf[TimeoutException])
  @throws(classOf[BatchErrorException])
  @throws(classOf[IllegalArgumentException])
  @throws(classOf[IOException])
  def createPoolIfNotExists(client: BatchClient, config: AzurePoolConfiguration): CloudPool = {

    println("Creating Pool: " + poolId)
    // Return pool if already exists
    if (client.poolOperations().existsPool(poolId)) {
      return client.poolOperations().getPool(poolId)
    }

    val NODE_AGENT_SKUID = "batch.node.ubuntu 16.04" // TODO: get from os sku?
    val POOL_NAME = "gridscale-pool" // TODO: randomly generate name
    val POOL_STEADY_TIMEOUT_IN_MINUTES = 5 * 60 * 1000

    // Create a virtual machine configuration
    val imgRef = new ImageReference().withOffer(config.osOffer).withPublisher(config.osPublisher).withSku(config.osSku)
    val poolVMConfiguration = new VirtualMachineConfiguration()
      .withNodeAgentSKUId(NODE_AGENT_SKUID)
      .withImageReference(imgRef)

    // Create object with pool parameters
    val poolAddParameter: PoolAddParameter = new PoolAddParameter()
      .withEnableAutoScale(true)
      .withAutoScaleFormula(
        """startingNumberOfVMs = 1
          |maxNumberofVMs = 25
          |pendingTaskSamplePercent = $PendingTasks.GetSamplePercent(180 * TimeInterval_Second)
          |pendingTaskSamples = if (pendingTaskSamplePercent < 70) {
          |startingNumberOfVMs
          |}
          |else {
          |avg($PendingTasks.GetSample(180 * TimeInterval_Second))
          |}
          |$TargetDedicatedNodes = min(maxNumberofVMs, pendingTaskSamples)")
        """.stripMargin)
      .withId(poolId)
      .withDisplayName(POOL_NAME)
      //.withTargetDedicatedNodes(config.dedicatedNodes)
      .withVmSize(config.vmSize)
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
      if (timeElapsed > POOL_STEADY_TIMEOUT_IN_MINUTES) {
        throw new TimeoutException("Could not create the pool within time")
      }
      pool = client.poolOperations().getPool(poolId)
      Thread.sleep(30 * 1000)
      timeElapsed = System.currentTimeMillis() - startTime
    }

    // Return pool
    return client.poolOperations().getPool(poolId)
  }

/*** Storage - file upload ***/

  import com.microsoft.azure.storage.StorageException
  import com.microsoft.azure.storage.blob.CloudBlobContainer
  import com.microsoft.azure.storage.blob.CloudBlockBlob
  import com.microsoft.azure.storage.blob.SharedAccessBlobPermissions
  import com.microsoft.azure.storage.blob.SharedAccessBlobPolicy
  import java.io.IOException
  import java.net.URISyntaxException
  import java.util.Calendar
  import java.util

  /**
   * Upload file to cloud and return uri to identify the file as a cloud resource
   *
   * @param fileName  the file name of blob
   * @param localFilePath  the local file path
   * @return Uri of the uploaded file
   * @throws URISyntaxException
   * @throws IOException
   * @throws InvalidKeyException
   * @throws StorageException
   */
  @throws[URISyntaxException]
  @throws[IOException]
  @throws[InvalidKeyException]
  @throws[StorageException]
  def uploadFileToCloud(azureStorageAuthentication: AzureStorageAuthentication, fileName: String, localFilePath: String): String = {
    // Create container, upload blob file to cloud storage
    val container = createBlobContainer(azureStorageAuthentication.storageAccountName, azureStorageAuthentication.storageAccountKey)

    println("Uploading file " + localFilePath + " as " + fileName)
    container.createIfNotExists
    // Upload file
    val blob = container.getBlockBlobReference(fileName)
    val source = new File(localFilePath)
    blob.upload(new FileInputStream(source), source.length)

    // Create policy with 1 day read permission
    val policy = new SharedAccessBlobPolicy
    val perEnumSet = util.EnumSet.of(SharedAccessBlobPermissions.READ)
    policy.setPermissions(perEnumSet)

    val c = Calendar.getInstance
    c.setTime(new Date)
    c.add(Calendar.DATE, 1)
    policy.setSharedAccessExpiryTime(c.getTime)

    // Create SAS key
    val sas = blob.generateSharedAccessSignature(policy, null)
    val uri = blob.getUri + "?" + sas

    println("Uploaded file with uri: " + uri)

    return uri
  }

  import java.net.URISyntaxException

  /**
   * Create blob container in order to upload file
   *
   * @param storageAccountName storage account name
   * @param storageAccountKey  storage account key
   * @return CloudBlobContainer instance
   * @throws URISyntaxException
   * @throws StorageException
   */
  @throws[URISyntaxException]
  @throws[StorageException]
  private def createBlobContainer(storageAccountName: String, storageAccountKey: String): CloudBlobContainer = {
    println("Creating a container for storage")

    val CONTAINER_NAME = "poolsandresourcefiles"
    // Create storage credential from name and key
    val credentials = new StorageCredentialsAccountAndKey(storageAccountName, storageAccountKey)
    // Create storage account
    val storageAccount = new CloudStorageAccount(credentials)
    // Create the blob client
    val blobClient = storageAccount.createCloudBlobClient
    // Get a reference to a container.
    // The container name must be lower case
    val container: CloudBlobContainer = blobClient.getContainerReference(CONTAINER_NAME)

    println("Storage container created")

    return container
  }

  trait Benchmark[D] {
    def submit(jobDescription: D): BatchJob
    def state(job: BatchJob): gridscale.JobState
    def clean(job: BatchJob): Unit
  }

  def submit(client: BatchClient, jobDescription: AzureJobDescription): BatchJob = {
    // create pool
    val uniqId: String = s"${java.util.UUID.randomUUID()}"
    val pool: CloudPool = createPoolIfNotExists(client, jobDescription.poolConfig)
    val taskId = submitTask(client, pool.id(), jobDescription.taskConfig)

    BatchJob(uniqId, taskId, "")
  }

  def toGridscaleState(azureTaskState: TaskState): gridscale.JobState = {
    azureTaskState match {
      case TaskState.RUNNING   ⇒ gridscale.JobState.Running
      case TaskState.PREPARING ⇒ gridscale.JobState.Running
      case TaskState.COMPLETED ⇒ gridscale.JobState.Done
      case TaskState.ACTIVE    ⇒ gridscale.JobState.Submitted
    }
  }

  def state(client: BatchClient, job: BatchJob): gridscale.JobState = {
    //JOB ID MAY GO HORRIBLY WRONG
    val task: CloudTask = client.taskOperations().getTask(jobId, job.jobId)
    toGridscaleState(task.state())
  }

  def clean(client: BatchClient, job: BatchJob): Unit = {
    // Clean Pool
    deletePool(client, poolId)
    // Clean Storage
    //TODO: Clean storage
  }

  @throws(classOf[BatchErrorException])
  @throws(classOf[IOException])
  @throws(classOf[InvalidKeyException])
  def submitTask(client: BatchClient, poolId: String, taskConfig: AzureTaskConfiguration): String = {
    println("Creating job with jobId: " + jobId)
    val poolInfo = new PoolInformation
    poolInfo.withPoolId(poolId)
    client.jobOperations().createJob(jobId, poolInfo)

    var taskId = taskRandom + taskCount
    taskCount += 1
    //TODO: Upload resource files to cloud
    println("Adding task " + taskId + " to job " + jobId)

    // Create task
    val task: TaskAddParameter = new TaskAddParameter
    //String.format("cat " + BLOB_FILE_NAME)
    task.withId(taskId).withCommandLine(taskConfig.cmdLine)
    task.withResourceFiles(taskConfig.resourceFiles)

    // Add task to job
    client.taskOperations().createTask(jobId, task)

    return taskId
  }

  // Waits for all tasks in a job to complete
  @throws(classOf[BatchErrorException])
  @throws(classOf[TimeoutException])
  @throws(classOf[InterruptedException])
  @throws(classOf[IOException])
  def waitForTaskCompletion(client: BatchClient, taskId: String, expiryTimeInSeconds: Integer): Unit = {
    println("Waiting for job to finish: " + jobId)
    val startTime = System.currentTimeMillis()
    var elapsedTime = 0L

    while (elapsedTime < expiryTimeInSeconds * 1000) {
      val tasks: util.List[CloudTask] = client.taskOperations().listTasks(jobId, new DetailLevel.Builder().withSelectClause("id, state").build())
      var allComplete = true
      // TODO: What is a good Scala way for something like this
      for (i ← 0 until tasks.size()) {
        if (tasks.get(i).state() != TaskState.COMPLETED) {
          allComplete = false
        }
      }

      if (allComplete) {
        println("All jobs complete\n")
        return
      }

      Thread.sleep(10 * 1000)
      elapsedTime = System.currentTimeMillis() - startTime
    }

    throw new TimeoutException("The tasks of the job " + jobId + "did not complete withing the given expiry time: " + expiryTimeInSeconds + "seconds")
  }

  // Waits for all tasks in a job to complete
  @throws(classOf[BatchErrorException])
  @throws(classOf[TimeoutException])
  @throws(classOf[InterruptedException])
  @throws(classOf[IOException])
  def waitForJobCompletion(client: BatchClient, jobId: String, expiryTimeInSeconds: Integer): Unit = {
    println("Waiting for job to finish: " + jobId)
    val startTime = System.currentTimeMillis()
    var elapsedTime = 0L

    while (elapsedTime < expiryTimeInSeconds * 1000) {
      val tasks: util.List[CloudTask] = client.taskOperations().listTasks(jobId, new DetailLevel.Builder().withSelectClause("id, state").build())
      var allComplete = true
      // TODO: What is a good Scala way for something like this
      for (i ← 0 until tasks.size()) {
        if (tasks.get(i).state() != TaskState.COMPLETED) {
          allComplete = false
        }
      }

      if (allComplete) {
        println("All jobs complete\n")
        return
      }

      Thread.sleep(10 * 1000)
      elapsedTime = System.currentTimeMillis() - startTime
    }

    throw new TimeoutException("The tasks of the job " + jobId + "did not complete withing the given expiry time: " + expiryTimeInSeconds + "seconds")
  }

  // Get stdout and stderr
  @throws(classOf[BatchErrorException])
  def printTaskOutput(client: BatchClient, taskId: String): Unit = {
    println("Fetching stdout for task " + taskId)
    var stream = new ByteArrayOutputStream
    val file = "stdout.txt"
    client.fileOperations().getFileFromTask(jobId, taskId, file, null, stream)
    val fileContents = stream.toString("UTF-8")
    println("Task file: " + file)
    println("File output : " + fileContents)
  }

  // Delete pool
  @throws(classOf[BatchErrorException])
  @throws(classOf[InterruptedException])
  def deletePool(client: BatchClient, poolId: String): Unit = {
    println("Deleting Pool: " + poolId)
    client.poolOperations().deletePool(poolId)
  }

  // Print batch error exceptions
  def printBatchError(batchError: BatchErrorException) = {
    println("Batch error: " + batchError.toString)
    if (batchError.body() != null) {
      println("Error code: " + batchError.body().code() + ", message: " + batchError.body().message().value())
      if (batchError.body().values() != null) {
        for (i ← 0 to batchError.body().values().size()) {
          val detail = batchError.body().values().get(i)
          println("detail: " + detail.key() + "=" + detail.value())
        }
      }
    }

  }

}

