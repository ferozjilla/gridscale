package gridscale.aws

import sys.process._
import java.io.{ BufferedWriter, FileWriter }
import java.util
import java.util.concurrent.Future

import com.amazonaws.auth.{ AWSCredentials, AWSCredentialsProvider }
import com.amazonaws.services.apigateway.model.GetSdkResult
import com.amazonaws.services.batch.model._
import com.amazonaws.services.batch.{ AWSBatch, AWSBatchClientBuilder }
import com.amazonaws.services.ecr.model._
import com.amazonaws.services.ecr.{ AmazonECR, AmazonECRClient, AmazonECRClientBuilder }
import com.amazonaws.services.s3.model._
import com.amazonaws.services.s3.{ AmazonS3, AmazonS3ClientBuilder }

import scala.reflect.io.File

object TestAws extends App {

/*** Authentication ***/
  // The user will supply these values
  val secretAccessKey: String = "4mj3jqVweXk9QK70SbyB1CkvxiJtDoYDPuCIEJnY"
  val accessKeyId: String = "AKIAJZEQSO7B54OPVDRA"
  //TODO: Set a region that is close to the user for best network performance
  val awsRegion: String = "eu-west-2" // This datacenter is in London! ^.^

  //val secretAccessKey: String = sys.env("AWS_SECRET_ACCESS_KEY")
  //val accessKeyId: String = sys.env("AWS_ACCESS_KEY_ID")
  ////TODO: Set a region that is close to the user for best network performance

  //val awsRegion: String = sys.env("AWS_REGION") // This datacenter is in London! ^.^
  // create an id for the job
  val jobUUID: String = s"${java.util.UUID.randomUUID().toString}".substring(0, 5)

  val JobDefinitionName = s"gridscale-job-def-$jobUUID"
  val JobName = s"gridscale-job-$jobUUID"
  val ComputeEnvName = s"gridscale-env-$jobUUID"
  val JobQueueName = s"gridscale-job-queue-$jobUUID"

  val credentialsProvider: AWSCredentialsProvider = new AWSCredentialsProvider {
    override def getCredentials: AWSCredentials = new AWSCredentials {
      override def getAWSAccessKeyId: String = accessKeyId

      override def getAWSSecretKey: String = secretAccessKey
    }

    override def refresh(): Unit = () // no-op
  }

/*** ------------------------------Create S3 Client------------------------------ ***/
  val s3Client: AmazonS3 = AmazonS3ClientBuilder.standard().withRegion(awsRegion).withCredentials(credentialsProvider).build()

/*** ------------------------------Create ECR Client------------------------------ ***/
  val ecrClient: AmazonECR = AmazonECRClientBuilder.standard().withRegion(awsRegion).withCredentials(credentialsProvider).build()

/***  ------------------------------Upload Input Files------------------------------ ***/
  // bucket to upload files into
  // TODO: Create a bucket identified by task
  println("Creating job with UUID " + jobUUID)
  val bucketName: String = s"bucket-$jobUUID"
  print(s"Creating bucket with name $bucketName...")
  val bucket: Bucket = s3Client.createBucket(bucketName)
  println("done")

  // Assumed input file
  val fileName: String = "catFile"
  val filePath: String = "/Users/ferozjilla/workspace/gridscale/examples/aws/src/main/scala/gridscale/aws/catFile"
  print(s"Uploading file $fileName to s3 bucket $bucketName...")

  // Upload input file to bucket
  val putObjectRequest: PutObjectRequest = new PutObjectRequest(bucket.getName, fileName, filePath)
  val putObjectResult: PutObjectResult = s3Client.putObject(putObjectRequest)
  println("done")

/*** ------------------------------Create Run Script------------------------------ ***/
  val runScriptFileName: String = "runScript.sh"
  val cmd: String = s"cat $fileName"
  print(s"Writing run script into file $runScriptFileName...")

  //TODO: Generate the script string dynamically
  val runScriptString: String =
    s"""|#!/bin/bash
       |
       |aws s3 cp s3://$bucketName/$fileName .
       |
       |$cmd > $fileName.output
       |
       |aws s3 cp $fileName.output s3://$bucketName/$fileName.output
  """.stripMargin

  //TODO: File error handling?
  //TODO: Make the run script executable
  writeFile(runScriptFileName, runScriptString)
  val chmodCmd: String = s"chmod u+x $runScriptFileName"
  val chmodResult: Int = chmodCmd.!
  if (chmodResult != 0) {
    println("Error in changing file permissions for the run script " + runScriptFileName)
    sys.exit(1)
  }
  println("done")

/*** ------------------------------Write Dockerfile------------------------------ ***/
  //TODO: Checkout docker APIs for scala
  val dockerFileName: String = "Dockerfile"
  print(s"Writing dockerfile $dockerFileName..")

  // A dockerfile that will allow us to call the script above from the batch job
  val dockerFileString: String =
    s"""
       |FROM amazonlinux:latest
       |
    |RUN yum -y install unzip aws-cli
       |ADD $runScriptFileName /usr/local/bin/$runScriptFileName
       |WORKDIR /tmp
       |USER nobody
       |
    |ENTRYPOINT ["/usr/local/bin/$runScriptFileName"]
  """.stripMargin

  //TODO: File error handling?
  writeFile(dockerFileName, dockerFileString)
  println("done")

/*** ------------------------------Build Dockerfile and upload to registry------------------------------ ***/
  // Build dockerfile
  //TODO: Checkout docker APIs for scala
  val dockerImageName: String = s"awsbatch/fetch_and_run_$jobUUID"
  print(s"Building docker image $dockerImageName...")
  val dockerBuildCmd: String = s"docker build -t $dockerImageName ."
  val dockerBuildResult: Int = dockerBuildCmd.!
  if (dockerBuildResult != 0) {
    println("[docker build] The docker command returned with exit code : " + dockerBuildResult)
    println("[docker build] This is a problem")
  }
  println("done")

  //TODO: Check if a repo has previously been created
  val repositoryName: String = s"awsbatch/source_$jobUUID"
  print(s"Creating ECR registry named $repositoryName...")
  val createRepositoryRequest: CreateRepositoryRequest = new CreateRepositoryRequest().withRepositoryName(repositoryName)
  val createRepositoryResult: CreateRepositoryResult = ecrClient.createRepository(createRepositoryRequest)
  println("done")

  val repositoryUri: String = createRepositoryResult.getRepository().getRepositoryUri
  val registryId: String = createRepositoryResult.getRepository.getRegistryId

  val getAuthorizationTokenRequest: GetAuthorizationTokenRequest = new GetAuthorizationTokenRequest().withRegistryIds(registryId)
  val getAuthorizationTokenResult: GetAuthorizationTokenResult = ecrClient.getAuthorizationToken(getAuthorizationTokenRequest)

  val authToken: String = getAuthorizationTokenResult.getAuthorizationData().get(0).getAuthorizationToken
  val proxyEndpoint: String = getAuthorizationTokenResult.getAuthorizationData().get(0).getProxyEndpoint

  val awsGetDockerLoginCmd: String = s"aws ecr get-login --region $awsRegion"
  val awsGetDockerLoginResult: String = awsGetDockerLoginCmd.!!

  print("Connecting docker client to ECR...")
  val dockerLoginCmd: String = awsGetDockerLoginResult
    .split(" ")
    .filter((str: String) ⇒ str != "-e" && str != "none")
    .mkString(" ")
  val dockerLoginExitCode: Int = dockerLoginCmd.!
  if (dockerTagExitCode != 0) {
    println("[docker login] " + dockerTagCmd + " failed with exit code " + dockerTagExitCode)
    sys.exit(1)
  }
  println("done")

  print("Tagging docker image...")
  val dockerTagCmd: String = s"docker tag $dockerImageName:latest $repositoryUri:latest"
  val dockerTagExitCode: Int = dockerTagCmd.!
  if (dockerTagExitCode != 0) {
    println("[docker tag] " + dockerTagCmd + " failed with exit code " + dockerTagExitCode)
    sys.exit(1)
  }
  println("done")

  println("Pushing docker image to ECR...")
  val dockerPushCmd: String = s"docker push $repositoryUri:latest"
  val dockerPushResult: String = dockerPushCmd.!!
  println(dockerPushResult)
  println("done")

  //TODO: Create a role that allows access to s3 and get it's arn
  //This has been done manually for now under the role: "s3-accessor"
  val jobRoleArn: String = "arn:aws:iam::221957794548:role/s3-accessor"

  //val batchGetImageRequest: BatchGetImageRequest = new BatchGetImageRequest().withRepositoryName("awsbatch/test_repo_1")
  //val batchGetImageResult: BatchGetImageResult = ecrClient.batchGetImage(batchGetImageRequest)

/*** ------------------------------Create Batch Client------------------------------ ***/

  val batchClient: AWSBatch = AWSBatchClientBuilder.standard().withRegion(awsRegion).withCredentials(credentialsProvider).build()

/*** ------------------------------Create Job Definition------------------------------ ***/
  val jobDefRequest: RegisterJobDefinitionRequest = new RegisterJobDefinitionRequest()
    .withJobDefinitionName(JobDefinitionName)
    .withType(JobDefinitionType.Container)
    .withContainerProperties(new ContainerProperties()
      .withJobRoleArn(jobRoleArn)
      .withImage(repositoryUri) // Image containing script to download s3 data
      .withVcpus(1) // Number of CPUs
      .withMemory(2000) // Memory in Megabytes
      .withCommand("cat", fileName)
    // Maybe somewhere here will be the link to storage?
    //.withVolumes(new Volume().)
    )

  print(s"Creating job definition $JobDefinitionName...")
  val jobDefResult: RegisterJobDefinitionResult = batchClient.registerJobDefinition(jobDefRequest)
  println("done")

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

  print(s"Creating compute environment $ComputeEnvName...")
  val computeEnvResult: CreateComputeEnvironmentResult = batchClient.createComputeEnvironment(computeEnvRequest)
  println("done")

/*** ------------------------------Create Job Queue------------------------------ ***/
  val createJobQueueRequest: CreateJobQueueRequest = new CreateJobQueueRequest()
    .withJobQueueName(JobQueueName)
    .withState(JQState.ENABLED)
    .withComputeEnvironmentOrder(new ComputeEnvironmentOrder()
      .withComputeEnvironment(ComputeEnvName)
      .withOrder(1))

  // Queue creation depends on environment being ready

  Thread.sleep(5 * 1000)
  print(s"Creating job queue $JobQueueName...")
  val createJobQueueResult: CreateJobQueueResult = batchClient.createJobQueue(createJobQueueRequest)
  println("done")

/*** ------------------------------Submit Job------------------------------ ***/

  val submitJobRequest: SubmitJobRequest = new SubmitJobRequest()
    .withJobName(JobName)
    .withJobDefinition(JobDefinitionName)
    .withJobQueue(JobQueueName)

  Thread.sleep(5 * 1000)
  print(s"Submitting Job $JobName...")
  val submitJobResult: SubmitJobResult = batchClient.submitJob(submitJobRequest)
  val jobId: String = submitJobResult.getJobId
  println("done. Job has AWS ID: " + jobId)

/*** Get Job Result ***/
  val describeJobsRequest: DescribeJobsRequest = new DescribeJobsRequest().withJobs(jobId)
  print("Getting job result...")
  val describeJobsResult: DescribeJobsResult = batchClient.describeJobs(describeJobsRequest)
  val jobStatus = describeJobsResult.getJobs().get(0).getStatus
  println(jobStatus)

/*** ------------------------------Utilities------------------------------ ***/
  private def writeFile(fileName: String, fileContents: String): Unit = {
    val file: java.io.File = new java.io.File(fileName)
    val bw: BufferedWriter = new BufferedWriter(new FileWriter(file))
    bw.write(fileContents)
    bw.close()
  }
}
