package gridscale

import gridscale.tools.shell.BashShell

import scala.util.Try
import scala.language.{ higherKinds, postfixOps }

package object ssh {

  import cats._
  import cats.implicits._
  import gridscale.ssh.sshj.{ SFTPClient, SSHClient }
  import gridscale.authentication._
  import freedsl.dsl._
  import freedsl.system._
  import squants._
  import time.TimeConversions._
  import gridscale.tools.cache._

  object SSH {

    object Authentication {

      implicit def userPassword = new Authentication[UserPassword] {
        override def authenticate(t: UserPassword, sshClient: SSHClient): Try[Unit] =
          sshClient.authPassword(t.user, t.password)
      }

      implicit def key = new Authentication[PrivateKey] {
        override def authenticate(t: PrivateKey, sshClient: SSHClient): Try[Unit] =
          sshClient.authPrivateKey(t)
      }

    }

    trait Authentication[T] {
      def authenticate(t: T, sshClient: SSHClient): util.Try[Unit]
    }

    def interpreter = new Interpreter {

      def client(server: SSHServer) = {
        val ssh =
          util.Try {
            val ssh = new SSHClient
            // disable strict host key checking
            ssh.disableHostChecking()
            ssh.useCompression()
            ssh.setConnectTimeout(server.timeout.millis.toInt)
            ssh.setTimeout(server.timeout.millis.toInt)
            ssh.connect(server.host, server.port)
            ssh
          }.toEither.leftMap { t ⇒ ConnectionError(s"Error connecting to $server", t) }

        def authenticate(ssh: SSHClient) = {
          server.authenticate(ssh) match {
            case util.Success(_) ⇒ util.Right(ssh)
            case util.Failure(e) ⇒
              ssh.disconnect()
              util.Left(AuthenticationException(s"Error authenticating to $server", e))
          }
        }

        for {
          client ← ssh
          a ← authenticate(client)
        } yield a
      }

      val clientCache = KeyValueCache(client)

      def execute(server: SSHServer, s: String)(implicit context: Context) =
        for {
          c ← clientCache.get(server)
          r ← result(SSHClient.exec(c, BashShell.remoteBashCommand(s)).toEither.leftMap(t ⇒ ExecutionError(s"Error executing $s on $server", t)))
        } yield r

      def sftp[T](server: SSHServer, f: SFTPClient ⇒ util.Try[T])(implicit context: Context) =
        for {
          c ← clientCache.get(server)
          r ← result(SSHClient.sftp(c, f).flatten.toEither.leftMap(t ⇒ SFTPError(s"Error in sftp transfer on $server", t)))
        } yield r

      def readFile[T](server: SSHServer, path: String, f: java.io.InputStream ⇒ T)(implicit context: Context) =
        for {
          c ← clientCache.get(server)
          res ← result(SSHClient.sftp(c, s ⇒ s.readAheadFileInputStream(path).map(f)).flatten.toEither.leftMap(t ⇒ SFTPError(s"Error in sftp transfer on $server", t)))
        } yield res

      def wrongReturnCode(server: String, command: String, executionResult: ExecutionResult)(implicit context: Context) = failure(ReturnCodeError(server, command, executionResult))

      override def terminate(implicit context: Context) = Right {
        clientCache.values.flatMap(_.right.toOption).foreach(_.close())
        clientCache.clear()
        ()
      }
    }

    case class ConnectionError(message: String, t: Throwable) extends Exception(message, t) with Error
    case class ExecutionError(message: String, t: Throwable) extends Exception(message, t) with Error
    case class SFTPError(message: String, t: Throwable) extends Exception(message, t) with Error
    case class ReturnCodeError(server: String, command: String, executionResult: ExecutionResult) extends Exception with Error {
      override def toString = ExecutionResult.error(command, executionResult) + " on server $server"
    }

  }

  @dsl trait SSH[M[_]] {
    def execute(server: SSHServer, s: String): M[ExecutionResult]
    def sftp[T](server: SSHServer, f: SFTPClient ⇒ util.Try[T]): M[T]
    def readFile[T](server: SSHServer, path: String, f: java.io.InputStream ⇒ T): M[T]
    def wrongReturnCode(server: String, command: String, executionResult: ExecutionResult): M[Unit]
  }

  object SSHServer {
    def apply[A: SSH.Authentication](host: String, port: Int = 22, timeout: Time = 1 minutes)(authentication: A): SSHServer =
      SSHServer(host, port, timeout, (sshClient: SSHClient) ⇒ implicitly[SSH.Authentication[A]].authenticate(authentication, sshClient))
  }

  case class SSHServer(host: String, port: Int, timeout: Time, authenticate: SSHClient ⇒ Try[Unit]) {
    override def toString = s"ssh server $host:$port"
  }

  case class JobId(jobId: String, workDirectory: String)

  /* ----------------------- Job managment --------------------- */

  def submit[M[_]: Monad: System](server: SSHServer, description: SSHJobDescription)(implicit ssh: SSH[M]) = for {
    j ← SSHJobDescription.toScript[M](description)
    (command, jobId) = j
    _ ← ssh.execute(server, command)
  } yield JobId(jobId, description.workDirectory)

  def stdOut[M[_]](server: SSHServer, jobId: JobId)(implicit ssh: SSH[M]) =
    readFile(
      server,
      SSHJobDescription.outFile(jobId.workDirectory, jobId.jobId),
      io.Source.fromInputStream(_).mkString)

  def stdErr[M[_]](server: SSHServer, jobId: JobId)(implicit ssh: SSH[M]) =
    readFile(
      server,
      SSHJobDescription.errFile(jobId.workDirectory, jobId.jobId),
      io.Source.fromInputStream(_).mkString)

  def state[M[_]: Monad](server: SSHServer, jobId: JobId)(implicit ssh: SSH[M]) =
    SSHJobDescription.jobIsRunning[M](server, jobId).flatMap {
      case true ⇒ (JobState.Running: JobState).pure[M]
      case false ⇒
        fileExists[M](server, SSHJobDescription.endCodeFile(jobId.workDirectory, jobId.jobId)).flatMap {
          case true ⇒
            for {
              // FIXME Limit the size of the read
              content ← ssh.readFile(
                server,
                SSHJobDescription.endCodeFile(jobId.workDirectory, jobId.jobId),
                is ⇒ io.Source.fromInputStream(is).mkString)
            } yield SSHJobDescription.translateState(content.takeWhile(_.isDigit).toInt)
          case false ⇒ (JobState.Failed: JobState).pure[M]
        }
    }

  def clean[M[_]: Monad](server: SSHServer, job: JobId)(implicit ssh: SSH[M]) = {
    val kill = s"kill `cat ${SSHJobDescription.pidFile(job.workDirectory, job.jobId)}`;"
    val rm = s"rm -rf ${job.workDirectory}/${job.jobId}*"
    for {
      k ← ssh.execute(server, kill)
      _ ← ssh.execute(server, rm)
      _ ← k.returnCode match {
        case 0 | 1 ⇒ ().pure[M]
        case _     ⇒ ssh.wrongReturnCode(server.toString, kill, k)
      }
    } yield ()
  }

  case class SSHJobDescription(command: String, workDirectory: String)

  object SSHJobDescription {

    def jobIsRunning[M[_]: Monad](server: SSHServer, job: JobId)(implicit ssh: SSH[M]) = {
      val cde = s"ps -p `cat ${pidFile(job.workDirectory, job.jobId)}`"
      ssh.execute(server, cde).map(_.returnCode == 0)
    }

    def translateState(retCode: Int): JobState =
      retCode match {
        case 0 ⇒ JobState.Done
        case _ ⇒ JobState.Failed
      }

    def file(dir: String, jobId: String, suffix: String) = dir + "/" + jobId + "." + suffix
    def pidFile(dir: String, jobId: String) = file(dir, jobId, "pid")
    def endCodeFile(dir: String, jobId: String) = file(dir, jobId, "end")
    def outFile(dir: String, jobId: String) = file(dir, jobId, "out")
    def errFile(dir: String, jobId: String) = file(dir, jobId, "err")

    def toScript[M[_]: Monad](description: SSHJobDescription, background: Boolean = true)(implicit system: System[M]) = {
      for {
        jobId ← system.randomUUID.map(_.toString)
      } yield {

        def executable = description.command

        def command =
          s"""
             |mkdir -p ${description.workDirectory}
             |cd ${description.workDirectory}
             |($executable >${outFile(description.workDirectory, jobId)} 2>${errFile(description.workDirectory, jobId)} ; echo \\$$? >${endCodeFile(description.workDirectory, jobId)}) ${if (background) "&" else ""}
             |echo \\$$! >${pidFile(description.workDirectory, jobId)}
           """.stripMargin

        (BashShell.remoteBashCommand(command), jobId)
      }
    }
  }

  /* ------------------------ sftp ---------------------------- */

  object FilePermission {

    sealed abstract class FilePermission
    case object USR_RWX extends FilePermission
    case object GRP_RWX extends FilePermission
    case object OTH_RWX extends FilePermission

    def toMask(fps: Set[FilePermission]): Int = {

      import net.schmizz.sshj.xfer.{ FilePermission ⇒ SSHJFilePermission }

      import collection.JavaConverters._

      SSHJFilePermission.toMask(
        fps map {
          case USR_RWX ⇒ SSHJFilePermission.USR_RWX
          case GRP_RWX ⇒ SSHJFilePermission.GRP_RWX
          case OTH_RWX ⇒ SSHJFilePermission.OTH_RWX
        } asJava
      )
    }
  }

  def fileExists[M[_]](server: SSHServer, path: String)(implicit ssh: SSH[M]) = ssh.sftp(server, _.exists(path))
  def readFile[M[_], T](server: SSHServer, path: String, f: java.io.InputStream ⇒ T)(implicit ssh: SSH[M]) = ssh.readFile(server, path, f)
  def writeFile[M[_]](server: SSHServer, is: java.io.InputStream, path: String)(implicit ssh: SSH[M]): M[Unit] = ssh.sftp(server, _.writeFile(is, path))
  def home[M[_]](server: SSHServer)(implicit ssh: SSH[M]) = ssh.sftp(server, _.canonicalize("."))
  def exists[M[_]](server: SSHServer, path: String)(implicit ssh: SSH[M]) = ssh.sftp(server, _.exists(path))
  def chmod[M[_]](server: SSHServer, path: String, perms: FilePermission.FilePermission*)(implicit ssh: SSH[M]) =
    ssh.sftp(server, _.chmod(path, FilePermission.toMask(perms.toSet[FilePermission.FilePermission])))
  def list[M[_]](server: SSHServer, path: String)(implicit ssh: SSH[M]) = ssh.sftp(server, _.ls(path)(e ⇒ e == "." || e == ".."))
  def makeDir[M[_]](server: SSHServer, path: String)(implicit ssh: SSH[M]) = ssh.sftp(server, _.mkdir(path))

  def rmDir[M[_]: Monad](server: SSHServer, path: String)(implicit ssh: SSH[M]): M[Unit] = {
    import cats.implicits._

    def remove(entry: ListEntry): M[Unit] = {
      import FileType._
      val child = path + "/" + entry.name
      entry.`type` match {
        case File      ⇒ rmFile[M](server, child)
        case Link      ⇒ rmFile[M](server, child)
        case Directory ⇒ rmDir[M](server, child)
        case Unknown   ⇒ rmFile[M](server, child)
      }
    }

    for {
      entries ← list[M](server, path)
      _ ← entries.traverse(remove)
      _ ← ssh.sftp(server, _.rmdir(path))
    } yield ()
  }

  def rmFile[M[_]](server: SSHServer, path: String)(implicit ssh: SSH[M]) = ssh.sftp(server, _.rm(path))
  def mv[M[_]](server: SSHServer, from: String, to: String)(implicit ssh: SSH[M]) = ssh.sftp(server, _.rename(from, to))

}

