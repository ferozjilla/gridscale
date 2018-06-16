package gridscale.aws

import java.util.concurrent.TimeUnit

import com.amazonaws.auth.{ AWSCredentials, AWSCredentialsProvider }
import com.amazonaws.services.batch.model._
import com.amazonaws.services.batch.{ AWSBatch, AWSBatchClientBuilder }
import com.amazonaws.services.s3.model._
import com.amazonaws.services.s3.{ AmazonS3, AmazonS3ClientBuilder }
import gridscale.JobState.{ Done, Failed, Running, Submitted }
import gridscale.cluster.BatchScheduler.BatchJob

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration

object TestAws extends App {

  val secretAccessKey: String = sys.env("AWS_SECRET_ACCESS_KEY")
  val accessKeyId: String = sys.env("AWS_ACCESS_KEY_ID")
  val awsRegion: String = sys.env("AWS_REGION") // This datacenter is in London! ^.^

  val credentialsProvider: AWSCredentialsProvider = new AWSCredentialsProvider {
    override def getCredentials: AWSCredentials = new AWSCredentials {
      override def getAWSAccessKeyId: String = accessKeyId

      override def getAWSSecretKey: String = secretAccessKey
    }

    override def refresh(): Unit = () // no-op
  }

  val batchClient: AWSBatch = AWSBatchClientBuilder.standard().withCredentials(credentialsProvider).withRegion(awsRegion).build()
  val s3Client: AmazonS3 = AmazonS3ClientBuilder.standard().withCredentials(credentialsProvider).withRegion(awsRegion).build()

  val jobConfiguration: AWSJobConfiguration = AWSJobConfiguration(
    vcpuCount = 2,
    memory = 500,
    scriptS3Address = "s3://gridscale-scripts/script.sh",
    inputFilesS3Address = "s3://gridscale-files/files.zip",
    cmd = "script.sh", "1")

  val computeConfig: AWSComputeConfiguration = AWSComputeConfiguration(
    resourceType = CRType.EC2,
    minCpu = 0,
    maxCpu = 10,
    desiredCpu = 2,
    instanceTypes = "optimal")

  val jobDesc: AWSJobDescription = AWSJobDescription(
    jobConfig = jobConfiguration,
    computeConfig = computeConfig)

  var job: BatchJob = null

  try {
    job = submit(batchClient, s3Client, jobDesc)
    var jobState = state(batchClient, job)
    while (jobState != Done && jobState != Failed) {
      println("Job state - " + jobState + ", sleeping")
      Thread.sleep(2 * 1000)
      jobState = state(batchClient, job)
    }
    println("Yep")
  } catch {
    case clientException: ClientException ⇒ println(clientException.getCause)
    case _: Throwable                     ⇒ println("Something went wrong!")
  } finally {
    clean(batchClient, s3Client, job)
  }
}
