package gridscale.benchmark.aws

import com.amazonaws.auth.{ AWSCredentials, AWSCredentialsProvider }
import com.amazonaws.services.batch.{ AWSBatch, AWSBatchClientBuilder }
import com.amazonaws.services.batch.model.CRType
import com.amazonaws.services.s3.{ AmazonS3, AmazonS3ClientBuilder }
import gridscale.aws
import gridscale.aws.{ AWSComputeConfiguration, AWSJobConfiguration, AWSJobDescription }
import gridscale.benchmark.Benchmark
import gridscale.benchmark.util.IO
import gridscale.cluster.BatchScheduler.BatchJob
import gridscale.cluster.{ ClusterInterpreter, HeadNode }

object AwsBenchmark {

  def run(batchClient: AWSBatch, s3Client: AmazonS3)(nbJobs: Int, runs: Int) = {

    val jobConfiguration: AWSJobConfiguration = AWSJobConfiguration(
      vcpuCount = 2,
      memory = 500,
      scriptS3Address = "s3://gridscale-scripts/script.sh",
      inputFilesS3Address = "s3://gridscale-files/files.zip",
      cmd = "script.sh", "60")

    val computeConfig: AWSComputeConfiguration = AWSComputeConfiguration(
      resourceType = CRType.EC2,
      minCpu = 0,
      maxCpu = 10,
      desiredCpu = 2,
      instanceTypes = "optimal")

    val jobDesc: AWSJobDescription = AWSJobDescription(
      jobConfig = jobConfiguration,
      computeConfig = computeConfig)
    // by default flavour = Torque, there's no need to specify it

    implicit val benchmark = new Benchmark[AWSJobDescription] {
      override def submit(jobDescription: AWSJobDescription): BatchJob = aws.submit(batchClient, s3Client, jobDescription)
      override def state(job: BatchJob): gridscale.JobState = aws.state(batchClient, job)
      override def clean(job: BatchJob): Unit = aws.clean(batchClient, s3Client, job)
    }

    implicit val hn: HeadNode[AWSBatch] = null
    Benchmark.avgBenchmark(batchClient)(jobDesc, nbJobs, runs)
  }

  def main(argv: Array[String]): Unit = {
    //val params = IO.parseArgs(argv)
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

    val nbJobs = 1
    val nbRuns = 1

    val res = ClusterInterpreter { intp ⇒
      run(batchClient, s3Client)(nbJobs, nbRuns)
    }

    println(IO.format(res, nbJobs, nbRuns))
  }
}

