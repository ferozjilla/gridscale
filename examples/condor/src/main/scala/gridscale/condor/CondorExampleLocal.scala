/**
 * Copyright (C) 2017 Jonathan Passerat-Palmbach
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package gridscale.condor

import freedsl.dsl._
import freedsl.errorhandler._
import freedsl.system._
import gridscale._
import gridscale.local._

object CondorExampleLocal extends App {

  val context = merge(Local, System, ErrorHandler)

  import scala.language.reflectiveCalls
  import gridscale.condor.condorDSL._
  import context.M
  import context.implicits._

  val headNode = LocalHost()

  val jobDescription = CondorJobDescription(executable = "/bin/echo", arguments = "hello from $(hostname)", workDirectory = "/homes/jpassera/test_gridscale")

  val res = for {
    job ← submit[M, LocalHost](headNode, jobDescription)
    s ← waitUntilEnded[M](state[M, LocalHost](headNode, job))
    out ← stdOut[M, LocalHost](headNode, job)
    _ ← clean[M, LocalHost](headNode, job)
  } yield (s, out)

  val interpreter = merge(Local.interpreter, System.interpreter, ErrorHandler.interpreter)
  println(interpreter.run(res))

}