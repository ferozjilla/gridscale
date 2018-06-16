
import sbtrelease.ReleasePlugin.autoImport.ReleaseTransformations._
import scalariform.formatter.preferences._
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

organization in ThisBuild := "fr.iscpif"
name := "gridscale"

scalaVersion in ThisBuild := "2.12.6"
crossScalaVersions in ThisBuild := Seq("2.12.6")

licenses in ThisBuild := Seq("Affero GPLv3" -> url("http://www.gnu.org/licenses/"))
homepage in ThisBuild := Some(url("https://github.com/openmole/gridscale"))

publishTo in ThisBuild := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
  else Some("releases" at nexus + "service/local/staging/deploy/maven2")}

pomIncludeRepository in ThisBuild := { _ => false}
scmInfo in ThisBuild := Some(ScmInfo(url("https://github.com/openmole/gridscale.git"), "scm:git:git@github.com:openmole/gridscale.git"))

pomExtra in ThisBuild := {
  <!-- Developer contact information -->
    <developers>
      <developer>
        <id>romainreuillon</id>
        <name>Romain Reuillon</name>
        <url>https://github.com/romainreuillon/</url>
      </developer>
      <developer>
        <id>jopasserat</id>
        <name>Jonathan Passerat-Palmbach</name>
        <url>https://github.com/jopasserat/</url>
      </developer>
    </developers>
}


releaseVersionBump := sbtrelease.Version.Bump.Minor

releaseTagComment    := s"Releasing ${(version in ThisBuild).value}"

releaseCommitMessage := s"Bump version to ${(version in ThisBuild).value}"

sonatypeProfileName := "fr.iscpif"


releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  tagRelease,
  releaseStepCommand("publishSigned"),
  setNextVersion,
  commitNextVersion,
  releaseStepCommand("sonatypeReleaseAll"),
  pushChanges
)

def javaByteCodeVersion(scalaVersion: String) = {
  val majorVersion = scalaVersion.split('.').take(2).mkString(".")
  majorVersion match {
    case "2.10" | "2.11" => "1.7"
    case "2.12" => "1.8"
    case _ => sys.error("unknown scala version " + majorVersion)
  }
}

def settings = Seq (
  libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % "1.0.6",
  // macro paradise doesn't work with scaladoc
  sources in (Compile, doc) := Nil,
  resolvers += Resolver.sonatypeRepo("snapshots")
)


def exportSettings = Seq(exportJars := true)

lazy val publishDir = settingKey[File]("Publishing directory")
lazy val publishIpfs = taskKey[Unit]("Publish to IPFS")

lazy val defaultSettings =
  settings ++
    scalariformSettings(autoformat = true) ++ Seq(
  ScalariformKeys.preferences :=
    ScalariformKeys.preferences.value
      .setPreference(AlignSingleLineCaseStatements, true)
      .setPreference(RewriteArrowSymbols, true),

  organization := "fr.iscpif.gridscale",

  publishDir := {
    import java.io.File
    val dir = new File("/export/ivy/")
    dir.mkdirs()
    dir
  },
  shellPrompt := { s => Project.extract(s).currentProject.id + " > " },

    scalacOptions ++= Seq("-target:jvm-1.8"),
    javacOptions ++= Seq("-source", "1.8", "-target", "1.8"),

  //publishMavenStyle := false,
  //publishTo := Some(Resolver.file("ipfs", publishDir.value)(Resolver.ivyStylePatterns)),
)


/* ---------------- Libraries --------------------*/

lazy val httpComponentsVersion = "4.5.3"
lazy val httpComponents = Seq("httpclient", "httpmime").map(
  "org.apache.httpcomponents" % _ % httpComponentsVersion)

lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.0.1" % "test"

/* -------------- gridscale dsl ------------------ */

val effectasideVersion = "0.2"
val monocleVersion = "1.5.0"

def dslSettings = defaultSettings ++ Seq(
  scalacOptions += "-Ypartial-unification",
  libraryDependencies += "fr.iscpif.effectaside" %% "effect" % effectasideVersion,

  libraryDependencies += "org.typelevel"  %% "squants"  % "1.3.0",
  libraryDependencies += "com.beachape" %% "enumeratum" % "1.5.12",

  addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.4"),
  addCompilerPlugin("org.scalameta" % "paradise" % "3.0.0-M11" cross CrossVersion.full),
  scalacOptions += "-Xplugin-require:macroparadise",

  resolvers += Resolver.sonatypeRepo("snapshots"),
  // rename to avoid conflict with publishTo resolver
  resolvers +=
    "Sonatype OSS Stagings" at "https://oss.sonatype.org/content/repositories/staging"
)

lazy val gridscale = Project(id = "gridscale", base = file("gridscale")) settings(dslSettings: _*) settings(
  libraryDependencies += scalaTest
  //libraryDependencies += "org.scala-stm" %% "scala-stm" % "0.8"
)

lazy val local = Project(id = "local", base = file("local")) settings(dslSettings: _*) dependsOn (gridscale)

lazy val ssh = Project(id = "ssh", base = file("ssh")) settings(dslSettings: _*) dependsOn (gridscale) settings (
  libraryDependencies += "com.hierynomus" % "sshj" % "0.23.0",
  libraryDependencies += "com.jcraft" % "jzlib" % "1.1.3"
)

lazy val cluster = Project(id = "cluster", base = file("cluster")) settings(dslSettings: _*) dependsOn (ssh, local) settings (
  libraryDependencies ++= Seq("monocle-core", "monocle-generic", "monocle-macro").map("com.github.julien-truffaut" %% _ % monocleVersion)
)

lazy val pbs = Project(id = "pbs", base = file("pbs")) settings(dslSettings: _*) dependsOn(gridscale, cluster)
lazy val slurm = Project(id = "slurm", base = file("slurm")) settings(dslSettings: _*) dependsOn(gridscale, cluster)
lazy val condor = Project(id = "condor", base = file("condor")) settings(dslSettings: _*) dependsOn(gridscale, cluster)
lazy val oar = Project(id = "oar", base = file("oar")) settings(dslSettings: _*) dependsOn(gridscale, cluster)
lazy val sge = Project(id = "sge", base = file("sge")) settings(dslSettings: _*) dependsOn(gridscale, cluster)

lazy val aws = Project(id = "aws", base = file("aws")) settings(dslSettings: _*) dependsOn(gridscale, cluster) settings (
  libraryDependencies += "com.amazonaws" % "aws-java-sdk"  % "1.11.338"
)

lazy val azure = Project(id = "azure", base = file("azure")) settings(dslSettings: _*) dependsOn(gridscale, cluster) settings (
  libraryDependencies += "com.microsoft.azure" % "azure-batch" % "3.1.0",
  libraryDependencies += "com.microsoft.rest" % "client-runtime" % "1.3.0",
  libraryDependencies += "com.microsoft.azure" % "azure-client-runtime" % "1.3.0",
  libraryDependencies += "com.microsoft.azure" % "azure-storage" % "7.0.0"
)


lazy val http = Project(id = "http", base = file("http")) settings(dslSettings: _*) dependsOn(gridscale) settings (
  libraryDependencies += "org.htmlparser" % "htmlparser" % "2.1",
  libraryDependencies += "com.squareup.okhttp3" % "okhttp" % "3.8.0",
  libraryDependencies ++= httpComponents
)

lazy val webdav = Project(id = "webdav", base = file("webdav")) settings(dslSettings: _*) dependsOn(gridscale, http)

lazy val dirac =  Project(id = "dirac", base = file("dirac")) settings(dslSettings: _*) dependsOn(gridscale, http) settings (
  libraryDependencies += "org.json4s" %% "json4s-jackson" % "3.5.0",
  libraryDependencies += "org.apache.commons" % "commons-compress" % "1.15"
)

lazy val egi = Project(id = "egi", base = file("egi")) settings(dslSettings: _*) dependsOn(gridscale, http, webdav) settings (
  libraryDependencies += "org.json4s" %% "json4s-jackson" % "3.5.0",
  libraryDependencies += "org.bouncycastle" % "bcpkix-jdk15on" % "1.57"
)

/* -------------- examples ------------------ */

lazy val examples = (project in file("examples")).settings(settings: _*).
  aggregate(
    egiCreamExample,
    egiWebDAVExample,
    egiDiracExample,
    httpExample,
    sshExample,
    condorExample,
    pbsExample,
    slurmExample,
    sgeExample,
    oarExample,
    azureExample,
    awsExample
  ) settings(
  name := "gridscale-examples",
  publishArtifact := false
)

def exampleSettings = defaultSettings ++ exportSettings

lazy val egiCreamExample  = Project(id = "egicreamexample", base = file("examples/egi/cream")) settings(exampleSettings: _*) dependsOn egi
lazy val egiWebDAVExample  = Project(id = "egiwebdavexample", base = file("examples/egi/webdav")) settings(exampleSettings: _*) dependsOn (egi, webdav)
lazy val egiDiracExample  = Project(id = "egidiracexample", base = file("examples/egi/dirac")) settings(exampleSettings: _*) dependsOn (egi, dirac)
lazy val condorExample = Project(id = "condorexample", base = file("examples/condor")) settings(exampleSettings: _*) dependsOn condor
lazy val pbsExample  = Project(id = "pbsexample", base = file("examples/pbs")) settings(exampleSettings: _*) dependsOn pbs
lazy val slurmExample  = Project(id = "slurmexample", base = file("examples/slurm")) settings(exampleSettings: _*) dependsOn slurm
lazy val sgeExample    = Project(id = "sgeexample", base = file("examples/sge")) settings(exampleSettings: _*) dependsOn sge
lazy val sshExample  = Project(id = "sshexample", base = file("examples/ssh")) settings(exampleSettings: _*) dependsOn ssh
lazy val oarExample  = Project(id = "oarexample", base = file("examples/oar")) settings(exampleSettings: _*) dependsOn oar
lazy val httpExample  = Project(id = "httpexample", base = file("examples/http")) settings(exampleSettings: _*) dependsOn http
lazy val azureExample  = Project(id = "azureexample", base = file("examples/azure")) settings(exampleSettings: _*) dependsOn azure
lazy val awsExample  = Project(id = "awsexample", base = file("examples/aws")) settings(exampleSettings: _*) dependsOn aws
libraryDependencies += "com.microsoft.azure" % "azure-batch" % "3.1.0"
lazy val localExample  = Project(id = "localexample", base = file("examples/local")) settings(exampleSettings: _*) dependsOn (local, cluster)

/* -------------- benchmark ------------------ */

lazy val benchmarkSettings = defaultSettings ++ exportSettings
//++ Seq(
//  mainClass in SbtOneJar.oneJar := Some("fr.iscpif.gridscale.benchmark.Main")
//)

lazy val utilBenchmark = Project(id = "utilbenchmark", base = file("benchmark/util")) settings  (benchmarkSettings: _*) dependsOn cluster
lazy val condorBenchmark = Project(id = "condorbenchmark", base = file("benchmark/condor")) settings (benchmarkSettings: _*) dependsOn (condor, utilBenchmark)
lazy val pbsBenchmark = Project(id = "pbsbenchmark", base = file("benchmark/pbs")) settings (benchmarkSettings: _*) dependsOn (pbs, utilBenchmark)
lazy val slurmBenchmark = Project(id = "slurmbenchmark", base = file("benchmark/slurm")) settings (benchmarkSettings: _*) dependsOn (slurm, utilBenchmark)
lazy val azureBenchmark = Project(id = "azurebenchmark", base = file("benchmark/azure")) settings (benchmarkSettings: _*) dependsOn (azure, utilBenchmark)
lazy val awsBenchmark = Project(id = "awsbenchmark", base = file("benchmark/aws")) settings (benchmarkSettings: _*) dependsOn (aws, utilBenchmark)
//lazy val diracBenchmark   = Project(id = "diracbenchmark", base = file("benchmark/egi/dirac")) settings (benchmarkSettings: _*) dependsOn (egi, utilBenchmark)
