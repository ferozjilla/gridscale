package gridscale.azure

import java.io.{ File, FileInputStream }

import com.microsoft.azure.batch.protocol.models._

object TestAzure extends App {

  import com.microsoft.azure.batch.BatchClient

  val poolId = "pool-test"
  val jobId = "job-test"
  val taskId1 = "task-test-1"

  try {
    val client: BatchClient = getBatchClient()
    createPoolIfNotExists(client, poolId)
    createJob(client, poolId, jobId)
    addTaskToJob(client, jobId, taskId1)
    waitForJobCompletion(client, jobId, 1000)
    printTaskOutput(client, jobId, taskId1)
    deletePool(client, poolId)
  } catch {
    case batchError: BatchErrorException ⇒ printBatchError(batchError)
    case error: Exception                ⇒ println("Exception! " + error.getMessage); error.printStackTrace()
  }
}
