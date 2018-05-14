package gridscale.azure

import java.io.{ File, FileInputStream }

import com.microsoft.azure.batch.auth.BatchSharedKeyCredentials
import com.microsoft.azure.batch.protocol.models._
import gridscale.{PoolConfiguration, TaskConfiguration}

object TestAzure extends App {

  import com.microsoft.azure.batch.BatchClient

  try {
    // Create Batch Client
    val batchUri = sys.env("AZURE_BATCH_ENDPOINT")
    val batchAccount = sys.env("AZURE_BATCH_ACCOUNT")
    val batchKey = sys.env("AZURE_BATCH_ACCESS_KEY")
    val credentials: BatchSharedKeyCredentials = new BatchSharedKeyCredentials(batchUri, batchAccount, batchKey)

    val client = BatchClient.open(credentials)

    // Create pool
    val POOL_DISPLAY_NAME = "Test Pool"
    val POOL_VM_SIZE = "STANDARD_A1"
    val POOL_VM_COUNT = 1
    val POOL_OS_PUBLISHER = "Canonical"
    val POOL_OS_OFFER = "UbuntuServer"
    val POOL_SKUID = "16.04-LTS"
    val POOL_STEADY_TIMEOUT_IN_SECONDS = 5 * 60 * 1000
    val NODE_AGENT_SKUID = "batch.node.ubuntu 16.04"

    val poolConfig = PoolConfiguration(
      //poolId = poolId,
      osPublisher = POOL_OS_PUBLISHER,
      osOffer = POOL_OS_OFFER,
      osSku = POOL_SKUID,
      vmSize = POOL_VM_SIZE,
      dedicatedNodes = POOL_VM_COUNT,
      lowPriorityNodes = 0)
    val pool = createPoolIfNotExists(client, poolConfig)

    // Upload file to Azure storage
    val fileToCat = "sample.txt"
    val LOCAL_FILE_PATH = "/Users/ferozjilla/workspace/gridscale/azure/" + fileToCat
    val uri = uploadFileToCloud(fileName = fileToCat, localFilePath = LOCAL_FILE_PATH)

    // Link file to task
    val files = new java.util.ArrayList[ResourceFile]
    val file = new ResourceFile
    file.withFilePath(fileToCat).withBlobSource(uri)
    files.add(file)
    //task.withResourceFiles(files)

    val taskConfig = TaskConfiguration(
      s"cat ${fileToCat}",
      files, // Collection of {fileName, filePath}, get uploaded, uri figured.
      // TODO: key value pairs - environmentVars
      // TODO: possibly explore - taskDependencies
      // TODO: possibly explore - applicationPackages
    )

    val taskId = submitTask(client, pool.id(), taskConfig)
    waitForTaskCompletion(client, taskId, 1000)
    printTaskOutput(client, jobId)
    //deletePool(client, poolId)
  } catch {
    case batchError: BatchErrorException ⇒ printBatchError(batchError)
    case error: Exception                ⇒ println("Exception! " + error.getMessage); error.printStackTrace()
  }
}
