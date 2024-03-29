package fhetest.Checker

import fhetest.Phase.Check

trait CheckResult {
  val results: List[BackendResultPair]
  override def toString: String = {
    val className = this.getClass.getSimpleName.replace("$", "")
    val resultStrings = results.map {
      case BackendResultPair(backendName, executeResult) =>
        s"\n- $backendName:\n ${executeResult.toString}"
    }
    s"[$className] ${resultStrings.mkString("")}"
  }
}
case class Same(results: List[BackendResultPair]) extends CheckResult
case class Diff(
  results: List[BackendResultPair],
  fails: List[BackendResultPair],
) extends CheckResult
case class InvalidResults(results: List[BackendResultPair]) extends CheckResult
case class ParserError(results: List[BackendResultPair]) extends CheckResult
