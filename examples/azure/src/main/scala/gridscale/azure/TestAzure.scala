package gridscale.azure

import java.io.{ByteArrayOutputStream, IOException, OutputStream}
import java.security.InvalidKeyException
import java.util
import java.util.concurrent.TimeoutException

import com.microsoft.azure.batch.DetailLevel
import com.microsoft.azure.batch.DetailLevel.Builder
import com.microsoft.azure.batch.protocol.models._

import scala.concurrent.TimeoutException

object TestAzure extends App {

  import com.microsoft.azure.batch.auth.BatchSharedKeyCredentials;
  import com.microsoft.azure.batch.BatchClient;
  import com.microsoft.azure.batch.protocol.models.{ CloudPool, ImageReference, VirtualMachineConfiguration, PoolInformation }

  val poolId = "pool-test"
  val jobId = "job-test"
  val taskId1 = "task-test-1"
  val taskId2 = "task-test-2"
  val taskId3 = "task-test-3"

  try {
    val client: BatchClient = getBatchClient()
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
  } catch {
    case batchError: BatchErrorException ⇒ printBatchError(batchError)
    case error: Exception                ⇒ println("Exception! " + error.getMessage); error.printStackTrace()
  }
}
