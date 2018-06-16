package gridscale

import java.util.concurrent.TimeUnit

import com.amazonaws.auth.{ AWSCredentials, AWSCredentialsProvider }
import com.amazonaws.services.batch.AWSBatch
import com.amazonaws.services.batch.model._
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{ PutObjectRequest, PutObjectResult }
import gridscale.JobState.{ Done, Failed, Running, Submitted }
import gridscale.cluster.BatchScheduler.BatchJob

import scala.concurrent.duration.Duration

package object aws {
  // The id given to: queue, compute environment and to the job def.
  val resourceId: String = s"${java.util.UUID.randomUUID().toString}".substring(0, 5)
  //val resourceId: String = "4c96c"
  val computeEnvName: String = s"gridscale-env-$resourceId"
  val jobQueueName: String = s"job-queue-$resourceId"
  val jobDefName: String = s"job-def-$resourceId"
  val bucketName: String = s"gridscale-$resourceId"
  var resourcesCreated: Boolean = false

  case class AWSAuthentication(secretAccessKey: String, accessKeyId: String, awsRegion: String)
  //TODO: upload files yourself
  case class AWSJobConfiguration(vcpuCount: Int, memory: Int, scriptS3Address: String, inputFilesS3Address: String, cmd: String*)
  case class AWSComputeConfiguration(resourceType: CRType, minCpu: Int, desiredCpu: Int, maxCpu: Int, instanceTypes: String*)
  case class AWSJobDescription(computeConfig: AWSComputeConfiguration, jobConfig: AWSJobConfiguration)

  def submit(batchClient: AWSBatch, s3Client: AmazonS3, jobDescription: AWSJobDescription): BatchJob = {
    //TODO: Upload script file and input files.
    //val scriptFile: java.io.File = new java.io.File("/Users/ferozjilla/workspace/gridscale/script.sh")
    //val putObjectResult: PutObjectResult = uploadFileToS3(s3Client, scriptFile, jobDescription.jobConfig.scriptS3Address, scriptFile.getName)

    val uniqId: String = s"${java.util.UUID.randomUUID().toString}".substring(0, 5)
    val jobName: String = s"job-$resourceId-$uniqId"
    if (!resourcesCreated) {
      registerJobDefinition(batchClient)
      createComputeEnv(batchClient, jobDescription.computeConfig)
      createJobQueue(batchClient)
      resourcesCreated = true
    }
    val jobId: String = submitJob(batchClient, jobName, jobDescription.jobConfig)
    BatchJob(uniqId, jobId, "")
  }

  def state(client: AWSBatch, job: BatchJob): gridscale.JobState = {
/*** Get Job Result ***/

    val describeJobsRequest: DescribeJobsRequest = new DescribeJobsRequest().withJobs(job.jobId)
    print("Getting job result...")
    val describeJobsResult: DescribeJobsResult = client.describeJobs(describeJobsRequest)
    toGridscaleJobState(describeJobsResult.getJobs().get(0).getStatus)
  }

  def toGridscaleJobState(awsJobStatus: String): gridscale.JobState = {
    awsJobStatus match {
      case "SUBMITTED" ⇒ Submitted
      case "PENDING"   ⇒ Submitted
      case "RUNNABLE"  ⇒ Submitted
      case "STARTING"  ⇒ Submitted
      case "RUNNING"   ⇒ Running
      case "FAILED"    ⇒ Failed
      case "SUCCEEDED" ⇒ Done
    }
  }

  def retryDuration(timeout: Duration)(f: ⇒ Unit): Unit = {
    try {
      f
    } catch {
      case clientException: ClientException ⇒
        if (timeout.toMillis > 0) { Thread.sleep(2 * 100); retryDuration(Duration(timeout.toMillis - (2 * 100), TimeUnit.MILLISECONDS))(f) }
        else throw clientException
    }
  }

  def retry(retries: Int, msg: String)(f: ⇒ Unit): Unit = {
    try {
      f
    } catch {
      case clientException: ClientException ⇒
        if (retries > 1) { Thread.sleep(2 * 100); retry(retries - 1, msg)(f) }
        else println(msg); throw clientException
    }
  }

  def clean(batchClient: AWSBatch, s3Client: AmazonS3, job: BatchJob): Unit = {
    //batchClient.cancelJob(new CancelJobRequest().withJobId(job.jobId).withReason("Clean was called"))

    println("Cleaning up")
    batchClient.updateJobQueue(new UpdateJobQueueRequest().withJobQueue(jobQueueName).withState(JQState.DISABLED))
    try {
      retry(5 * 300, "delete job queue")(batchClient.deleteJobQueue(new DeleteJobQueueRequest().withJobQueue(jobQueueName)))
      retry(5 * 300, "disable compute env")(batchClient.updateComputeEnvironment(new UpdateComputeEnvironmentRequest().withComputeEnvironment(computeEnvName).withState(CEState.DISABLED)))
      Thread.sleep(30 * 1000) // give it 30s to propogate
      retry(5 * 300, "delete compute env")(batchClient.deleteComputeEnvironment(new DeleteComputeEnvironmentRequest().withComputeEnvironment(computeEnvName)))
      retry(5 * 300, "deregister job def")(batchClient.deregisterJobDefinition(new DeregisterJobDefinitionRequest().withJobDefinition(jobDefName)))
      retry(5 * 300, "delete bucket")(s3Client.deleteBucket(bucketName))
      //batchClient.deleteJobQueue(new DeleteJobQueueRequest().withJobQueue(jobQueueName))
      //batchClient.updateComputeEnvironment(new UpdateComputeEnvironmentRequest().withComputeEnvironment(computeEnvName).withState(CEState.DISABLED))
      //batchClient.deleteComputeEnvironment(new DeleteComputeEnvironmentRequest().withComputeEnvironment(computeEnvName))
      //batchClient.deregisterJobDefinition(new DeregisterJobDefinitionRequest().withJobDefinition(jobDefName))
      //s3Client.deleteBucket(bucketName)
    } catch {
      case clientException: ClientException ⇒ println(clientException.getCause)
    }
  }

  def uploadFileToS3(s3Client: AmazonS3, file: java.io.File, s3Address: String, fileName: String): PutObjectResult = {
    //val bucketId: String = s"bucket-$resourceId-$uniqId"
    print(s"Uploading file $fileName to s3 bucket $bucketName...")
    s3Client.putObject(bucketName, fileName, file)
    s3Client.putObject(new PutObjectRequest(bucketName, fileName, file))
  }

/*** ------------------------------Create Compute Environment------------------------------ ***/
  def createComputeEnv(client: AWSBatch, computeConfig: AWSComputeConfiguration): Unit = {
    val serviceRoleArn: String = "arn:aws:iam::221957794548:role/service-role/AWSBatchServiceRole"

    val computeResource: ComputeResource = new ComputeResource()
      // Alternative is Spot which is cheaper but without the guarentee of non-interruption
      //TODO: Set values from compute config
      .withType(computeConfig.resourceType)
      // Can explicitly state instance types but optimal matches the instance types available in the selected region, and
      // ...specified by the job to find the best fit.
      .withInstanceTypes(scala.collection.JavaConverters.seqAsJavaList(computeConfig.instanceTypes))
      // Not setting this to one means always maintaining cpus even when there is no job. Less set up time but not cost effective.
      // May set up and delete.
      .withMinvCpus(computeConfig.minCpu)
      .withDesiredvCpus(computeConfig.desiredCpu)
      // The limit to the CPUs that can be provided by the environment # aws max: 256
      .withMaxvCpus(computeConfig.maxCpu)
      // Assign the compute resources with a role that allows them to make calls to the AWS Api
      .withInstanceRole("ecsInstanceRole")
      .withSubnets("subnet-b6cb0acc", "subnet-3c6a7d71", "subnet-61179108")
      .withSecurityGroupIds("sg-3fe26c54")

    val computeEnvRequest: CreateComputeEnvironmentRequest = new CreateComputeEnvironmentRequest()
      // AWS configures and scales instances
      // Alternative is unmanaged wherein we would have to control this
      .withType(CEType.MANAGED)
      .withComputeEnvironmentName(computeEnvName)
      .withComputeResources(computeResource)
      // TODO: is this role always available?
      .withServiceRole(serviceRoleArn)
      .withState(CEState.ENABLED)

    println("Creating a compute environment")
    def createComputeEnvironmentRetry(n: Int): Unit = {
      try {
        client.createComputeEnvironment(computeEnvRequest)
      } catch {
        case clientException: ClientException ⇒
          if (n > 1) { Thread.sleep(2 * 100); createComputeEnvironmentRetry(n - 1) }
          else throw clientException
      }
    }

    println("Creating a job queue")
    createComputeEnvironmentRetry(20)
  }

/*** ------------------------------Create Job Queue------------------------------ ***/
  def createJobQueue(client: AWSBatch): Unit = {
    val createJobQueueRequest: CreateJobQueueRequest = new CreateJobQueueRequest()
      .withJobQueueName(jobQueueName)
      .withState(JQState.ENABLED)
      .withComputeEnvironmentOrder(new ComputeEnvironmentOrder()
        .withComputeEnvironment(computeEnvName)
        .withOrder(1))

    //TODO: Generalise
    def createJobQueueRetry(n: Int): Unit = {
      try {
        client.createJobQueue(createJobQueueRequest)
      } catch {
        case clientException: ClientException ⇒
          if (n > 1) { Thread.sleep(2 * 100); createJobQueueRetry(n - 1) }
          else throw clientException
      }
    }

    println("Creating a job queue")
    createJobQueueRetry(20)
  }

/*** ------------------------------Create Job Definition------------------------------ ***/
  def registerJobDefinition(client: AWSBatch): RegisterJobDefinitionResult = {
    val jobRoleArn: String = "arn:aws:iam::221957794548:role/s3-accessor"
    val repositoryUri: String = "221957794548.dkr.ecr.eu-west-2.amazonaws.com/awsbatch/test_repo_1"

    val jobDefRequest: RegisterJobDefinitionRequest = new RegisterJobDefinitionRequest()
      .withJobDefinitionName(jobDefName)
      .withType(JobDefinitionType.Container)
      .withContainerProperties(new ContainerProperties()
        .withJobRoleArn(jobRoleArn)
        .withImage(repositoryUri) // Image containing script to download s3 data
        //To be overriden
        .withVcpus(1)
        .withMemory(500)
        .withCommand("yolo", "yolo"))

    print(s"Registering job definition $jobDefName...")
    client.registerJobDefinition(jobDefRequest)
  }

  def submitJob(client: AWSBatch, jobName: String, jobConfig: AWSJobConfiguration): String = {

    val submitJobRequest: SubmitJobRequest = new SubmitJobRequest()
      .withJobName(jobName)
      .withJobDefinition(jobDefName)
      .withJobQueue(jobQueueName)
      .withContainerOverrides(new ContainerOverrides()
        .withMemory(jobConfig.memory)
        .withVcpus(jobConfig.vcpuCount)
        .withCommand(scala.collection.JavaConverters.seqAsJavaList(jobConfig.cmd))
        //TODO: Single source of knowledge
        .withEnvironment(
          new KeyValuePair().withName("BATCH_FILE_TYPE").withValue("script"),
          new KeyValuePair().withName("BATCH_FILE_S3_URL").withValue("s3://gridscale-scripts/script.sh")))

    def submitJobRetry(n: Int): String = {
      try {
        val submitJobResult = client.submitJob(submitJobRequest)
        submitJobResult.getJobId
      } catch {
        case clientException: ClientException ⇒
          if (n > 1) { Thread.sleep(2 * 100); submitJobRetry(n - 1) }
          else throw clientException
      }
    }

    print(s"Submitting Job $jobName...")
    submitJobRetry(20)
  }

}
