package gridscale

import java.io.{ ByteArrayOutputStream, File, FileInputStream, IOException }
import java.security.InvalidKeyException
import java.util.Date
import java.util.concurrent.TimeoutException

import com.microsoft.azure.batch.{ BatchClient, DetailLevel }
import com.microsoft.azure.batch.auth.BatchSharedKeyCredentials
import com.microsoft.azure.batch.protocol.models._
import com.microsoft.azure.storage.blob.CloudBlobContainer
import com.microsoft.azure.storage.{ CloudStorageAccount, StorageCredentialsAccountAndKey, StorageException, blob }

package object azure {
  // Get a batch client
  @throws(classOf[NoSuchElementException])
  @throws(classOf[IllegalArgumentException])
  def getBatchClient(): BatchClient = {
    println("Creating Batch Client")

    val batchUri = sys.env("AZURE_BATCH_ENDPOINT")
    val batchAccount = sys.env("AZURE_BATCH_ACCOUNT")
    val batchKey = sys.env("AZURE_BATCH_ACCESS_KEY")
    val creds: BatchSharedKeyCredentials = new BatchSharedKeyCredentials(batchUri, batchAccount, batchKey)
    return BatchClient.open(creds)
  }

  // Create a pool
  @throws(classOf[InterruptedException])
  @throws(classOf[TimeoutException])
  @throws(classOf[BatchErrorException])
  @throws(classOf[IllegalArgumentException])
  @throws(classOf[IOException])
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
        throw new TimeoutException("Could not create the pool within time")
      }
      pool = client.poolOperations().getPool(poolId)
      Thread.sleep(30 * 1000)
      timeElapsed = System.currentTimeMillis() - startTime
    }

    // Return pool
    return client.poolOperations().getPool(poolId)
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
   * Upload file to blob container and return sas key
   *
   * @param container blob container
   * @param fileName  the file name of blob
   * @param filePath  the local file path
   * @return SAS key for the uploaded file
   * @throws URISyntaxException
   * @throws IOException
   * @throws InvalidKeyException
   * @throws StorageException
   */
  @throws[URISyntaxException]
  @throws[IOException]
  @throws[InvalidKeyException]
  @throws[StorageException]
  private def uploadFileToCloud(container: CloudBlobContainer, fileName: String, filePath: String): String = { // Create the container if it does not exist.
    println("Uploading file " + filePath + " as " + fileName)
    container.createIfNotExists
    // Upload file
    val blob = container.getBlockBlobReference(fileName)
    val source = new File(filePath)
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

  // Create job
  @throws(classOf[BatchErrorException])
  @throws(classOf[IOException])
  @throws(classOf[InvalidKeyException])
  def createJob(client: BatchClient, poolId: String, jobId: String): Unit = {
    println("Creating Job: " + jobId)
    val poolInfo = new PoolInformation
    poolInfo.withPoolId(poolId)
    client.jobOperations.createJob(jobId, poolInfo)
  }

  // Add task to job
  @throws(classOf[BatchErrorException])
  @throws(classOf[IOException])
  @throws(classOf[InvalidKeyException])
  def addTaskToJob(client: BatchClient, jobId: String, taskId: String): Unit = {
    val BLOB_FILE_NAME = "sample.txt"
    val LOCAL_FILE_PATH = "/Users/ferozjilla/workspace/gridscale/azure/" + BLOB_FILE_NAME

    println("Adding task " + taskId + " to job " + jobId)

    // Create task
    val task: TaskAddParameter = new TaskAddParameter
    task.withId(taskId).withCommandLine(String.format("cat " + BLOB_FILE_NAME))

    // Create container, upload blob file to cloud storage
    val STORAGE_ACCOUNT_NAME = sys.env("STORAGE_ACCOUNT_NAME")
    val STORAGE_ACCOUNT_KEY = sys.env("STORAGE_ACCOUNT_KEY")
    var container = createBlobContainer(STORAGE_ACCOUNT_NAME, STORAGE_ACCOUNT_KEY)
    val blobUri = uploadFileToCloud(container, BLOB_FILE_NAME, LOCAL_FILE_PATH)

    // Associate blob file with task
    val file = new ResourceFile
    file.withFilePath(BLOB_FILE_NAME).withBlobSource(blobUri)
    var files = new util.ArrayList[ResourceFile]
    files.add(file)
    task.withResourceFiles(files)

    // Add task to job
    client.taskOperations().createTask(jobId, task)
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
  def printTaskOutput(client: BatchClient, jobId: String, taskId: String): Unit = {
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
