package gridscale.aws

import com.amazonaws.auth.{ AWSCredentials, AWSCredentialsProvider }
import com.amazonaws.services.batch.model._
import com.amazonaws.services.batch.{ AWSBatch, AWSBatchClientBuilder }

object TestAws extends App {

/*** Authentication ***/
  // The user will supply these values
  val secretAccessKey: String = sys.env("AWS_SECRET_ACCESS_KEY")
  val accessKeyId: String = sys.env("AWS_ACCESS_KEY_ID")
  //TODO: Set a region that is close to the user for best network performance
  val awsRegion: String = "eu-west-2" // This datacenter is in London! ^.^

  // Constants for names
  val JobDefinitionName = "gridscale-job-def-8"
  val JobName = "gridscale-job-8"
  val ComputeEnvName = "gridscale-env-8"
  val JobQueueName = "gridscale-job-queue-8"

  def createBatchClient(accessKeyId: String, secretAccessKey: String, awsRegion: String): AWSBatch = {
    val credentialsProvider: AWSCredentialsProvider = new AWSCredentialsProvider {
      override def getCredentials: AWSCredentials = new AWSCredentials {
        override def getAWSAccessKeyId: String = accessKeyId

        override def getAWSSecretKey: String = secretAccessKey
      }

      override def refresh(): Unit = () // no-op
    }

    AWSBatchClientBuilder.standard().withRegion(awsRegion).withCredentials(credentialsProvider).build()
  }

/*** ------------------------------Create Batch Client------------------------------ ***/
  val client: AWSBatch = createBatchClient(accessKeyId, secretAccessKey, awsRegion)

/*** ------------------------------Create Compute Environment------------------------------ ***/
  val computeResource: ComputeResource = new ComputeResource()
    // Alternative is Spot which is cheaper but without the guarentee of non-interruption
    .withType(CRType.EC2)
    // Can explicitly state instance types but optimal matches the instance types available in the selected region, and
    // ...specified by the job to find the best fit.
    .withInstanceTypes("optimal")
    // Not setting this to one means always maintaining cpus even when there is no job. Less set up time but not cost effective.
    // May set up and delete.
    .withMinvCpus(0)
    .withDesiredvCpus(10)
    // The limit to the CPUs that can be provided by the environment # aws max: 256
    .withMaxvCpus(100)
    // Assign the compute resources with a role that allows them to make calls to the AWS Api
    .withInstanceRole("ecsInstanceRole")
    .withSubnets("subnet-b6cb0acc", "subnet-3c6a7d71", "subnet-61179108")
    .withSecurityGroupIds("sg-3fe26c54")

  val computeEnvRequest: CreateComputeEnvironmentRequest = new CreateComputeEnvironmentRequest()
    // AWS configures and scales instances
    // Alternative is unmanaged wherein we would have to control this
    .withType(CEType.MANAGED)
    .withComputeEnvironmentName(ComputeEnvName)
    .withComputeResources(computeResource)
    // TODO: is this role always available?
    .withServiceRole("arn:aws:iam::221957794548:role/service-role/AWSBatchServiceRole")
    .withState(CEState.ENABLED)

  val computeEnvResult: CreateComputeEnvironmentResult = client.createComputeEnvironment(computeEnvRequest)

/*** ------------------------------Create Job Definition------------------------------ ***/
  val jobDefRequest: RegisterJobDefinitionRequest = new RegisterJobDefinitionRequest()
    .withJobDefinitionName(JobDefinitionName)
    .withType(JobDefinitionType.Container)
    .withContainerProperties(new ContainerProperties()
      .withImage("amazonlinux") // Image to
      .withVcpus(1) // Number of CPUs
      .withMemory(2000) // Memory in Megabytes
      .withCommand("echo", "hello world")
    // Maybe somewhere here will be the link to storage?
    //.withVolumes(new Volume().)
    )

  val jobDefResult: RegisterJobDefinitionResult = client.registerJobDefinition(jobDefRequest)

/*** ------------------------------Create Job Queue------------------------------ ***/
  val createJobQueueRequest: CreateJobQueueRequest = new CreateJobQueueRequest()
    .withJobQueueName(JobQueueName)
    .withState(JQState.ENABLED)
    .withComputeEnvironmentOrder(new ComputeEnvironmentOrder()
      .withComputeEnvironment(ComputeEnvName)
      .withOrder(1))

  val createJobQueueResult: CreateJobQueueResult = client.createJobQueue(createJobQueueRequest)

/*** ------------------------------Submit Job------------------------------ ***/
  val submitJobRequest: SubmitJobRequest = new SubmitJobRequest()
    .withJobName(JobName)
    .withJobDefinition(JobDefinitionName)
    .withJobQueue(JobQueueName)

  val submitJobResult: SubmitJobResult = client.submitJob(submitJobRequest)
}
