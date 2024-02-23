package fhetest.Generate

import scala.util.Random

object Utils {
  def assignValue(name: String, v: (Int | Double)): AbsStmt =
    Assign(name, v)
  def assignValues(name: String, vs: (List[Int] | List[Double])): AbsStmt =
    AssignVec(name, vs)

  extension (s: AbsStmt)
    def stringify: String = s match
      case Assign(l, r)    => s"$l = ${formatNumber(r)};"
      case AssignVec(l, r) => s"$l = {${r.map(formatNumber).mkString(",")}};"
      case Add(_, _)       => "x += y;"
      case AddP(_, _)      => "x += yP;"
      case Sub(_, _)       => "x -= y;"
      case SubP(_, _)      => "x -= yP;"
      case Mul(_, _)       => "x *= y;"
      case MulP(_, _)      => "x *= yP;"
      case Rot(_, _)       => "rotate_left(x, c);"

  extension (t: Template)
    def stringify: String = t.map(_.stringify).mkString("")
    def getMulDepth: Int = t.count {
      case Mul(_, _) => true; case _ => false
    }

    def assignRandValues(len: Int, bound: (Int | Double)): Template = {
      val lx = Random.between(1, len + 1)
      val ly = Random.between(1, len + 1)

      // Generate Random Values
      val vxs: (List[Int] | List[Double]) = bound match {
        case intBound: Int => List.fill(lx)(Random.between(0, intBound))
        case doubleBound: Double =>
          List.fill(lx)(Random.between(0.0, doubleBound))
      }
      val vys: (List[Int] | List[Double]) = bound match {
        case intBound: Int => List.fill(ly)(Random.between(0, intBound))
        case doubleBound: Double =>
          List.fill(ly)(Random.between(0.0, doubleBound))
      }
      val vyP = bound match {
        case intBound: Int       => Random.between(0, intBound)
        case doubleBound: Double => Random.between(0.0, doubleBound)
      }

      val assigned = assignValues("x", vxs) :: t
      assigned.flatMap {
        case op @ (Add(_, _) | Sub(_, _) | Mul(_, _)) =>
          assignValues("y", vys) :: op :: Nil
        case op @ (AddP(_, _) | SubP(_, _) | MulP(_, _)) =>
          assignValue("yP", vyP) :: op :: Nil
        case op @ Rot(_, _) =>
          Assign("c", Random.between(0, 10)) :: op :: Nil
        case s => s :: Nil
      }
    }
}