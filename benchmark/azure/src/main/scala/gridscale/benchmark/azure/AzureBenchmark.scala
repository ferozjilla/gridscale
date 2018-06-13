
object AzureBenchmark {

  val poolConfig = AzurePoolConfiguration(
    //poolId = poolId,
    osPublisher = POOL_OS_PUBLISHER,
    osOffer = POOL_OS_OFFER,
    osSku = POOL_SKUID,
    vmSize = POOL_VM_SIZE,
    dedicatedNodes = POOL_VM_COUNT,
    lowPriorityNodes = 0)

  val taskConfig = AzureTaskConfiguration(
    s"sleep 1000",
    files, // Collection of {fileName, filePath}, get uploaded, uri figured.
    // TODO: key value pairs - environmentVars
    // TODO: possibly explore - taskDependencies
    // TODO: possibly explore - applicationPackages
  )

  val jobDescription: AzureJobDescription = AzureJobDescription(poolConfig, taskConfig)

}