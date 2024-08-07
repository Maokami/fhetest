package fhetest.Generate

import scala.util.Random
import fhetest.Generate.Utils.*
import fhetest.Utils.ENC_TYPE
import fhetest.Utils.*

case class AbsProgram(
  absStmts: List[AbsStmt],
  libConfig: LibConfig,
  invalidFilterIdxList: List[InvalidFilterIdx],
) {
  val encParams = libConfig.encParams
  val len = libConfig.len
  val bound = libConfig.bound
  val rotateBound = libConfig.rotateBound
  val mulDepth: Int = absStmts.count {
    case Mul(_, _) | MulP(_, _) => true; case _ => false
  }

  def stringify: String = absStmts.map(_.stringify()).mkString("")

  def assignRandValues(): AbsProgram = {
    def lx() = Random.between(1, len + 1)
    def ly() = Random.between(1, len + 1)
    def vc() = if (rotateBound < 21) Random.between(0, rotateBound + 1)
    else Random.between(21, rotateBound + 1)

    // Generate Random Values
    def vxs(): (List[Int] | List[Double]) = bound match {
      case intBound: Int => List.fill(lx())(Random.between(0, intBound))
      case doubleBound: Double =>
        List.fill(lx())(Random.between(0.0, doubleBound))
    }
    def vys(): (List[Int] | List[Double]) = bound match {
      case intBound: Int => List.fill(ly())(Random.between(0, intBound))
      case doubleBound: Double =>
        List.fill(ly())(Random.between(0.0, doubleBound))
    }
    def vyP() = bound match {
      case intBound: Int       => Random.between(0, intBound)
      case doubleBound: Double => Random.between(0.0, doubleBound)
    }

    val assigned = assignValues("x", vxs()) :: absStmts
    val newStmts = assigned.flatMap {
      case op @ (Add(_, _) | Sub(_, _) | Mul(_, _)) =>
        assignValues("y", vys()) :: op :: Nil
      case op @ (AddP(_, _) | SubP(_, _) | MulP(_, _)) =>
        assignValue("yP", vyP()) :: op :: Nil
      case op @ Rot(_, _) =>
        Assign("c", vc()) :: op :: Nil
      case s => s :: Nil
    }
    AbsProgram(newStmts, libConfig, invalidFilterIdxList)
  }
  def adjustScale(
    encType: ENC_TYPE,
  ): AbsProgram = encType match {
    case ENC_TYPE.ENC_DOUBLE =>
      val newStmts = absStmts
        .foldLeft(List[AbsStmt]())((lst, stmt) =>
          stmt match {
            case Add(l, r)  => lst ++ List(MatchParams2(r, l), stmt)
            case AddP(l, _) => lst ++ List(MatchParams1(l), stmt)
            case Sub(l, r)  => lst ++ List(MatchParams2(r, l), stmt)
            case SubP(l, _) => lst ++ List(MatchParams1(l), stmt)
            case Mul(l, r) =>
              lst ++ List(MatchParams2(r, l), stmt, Rescale(l))
            case MulP(l, _) => lst ++ List(MatchParams1(l), stmt, Rescale(l))
            case _          => lst :+ stmt
          },
        )
      AbsProgram(newStmts, libConfig, invalidFilterIdxList)
    case _ => this
  }
}
