package fhetest.Phase

import org.twc.terminator.t2dsl_compiler.T2DSLsyntaxtree.*;
import java.nio.file.{Files, Paths}

import scala.jdk.CollectionConverters._

import fhetest.Utils.formatNumber

case object Interp {

  def apply(t2ast: Goal, ring_dim: Int, modulus: Int): String =
    eval(t2ast, ring_dim, modulus)

  /* Type */
  trait T2Data
  case class T2Int(value: Int) extends T2Data
  case class T2IntArr(value: List[Int]) extends T2Data
  case class T2Double(value: Double) extends T2Data
  case class T2DoubleArr(value: List[Double]) extends T2Data
  case class T2EncInt(value: List[Int]) extends T2Data
  case class T2EncIntArr(value: List[List[Int]]) extends T2Data
  case class T2EncDouble(value: List[Double]) extends T2Data
  case class T2EncDoubleArr(value: List[List[Double]]) extends T2Data
  case class T2Bool(value: Boolean) extends T2Data

  type Env = Map[String, T2Data]
  type PrintList = List[String]

  /* Helper functions */
  def getIdentifierName(id: Identifier): String = id.f0.toString()

  // OPTION: (to encrypt n as {n, ..., n}) def encrypt(plaintxt: T2Data, ring_dim: Int): T2Data = plaintxt match {
  def encrypt(plaintxt: T2Data): T2Data = plaintxt match {
    case T2Int(v) =>
      T2EncInt(List(v)) // OPTION: T2EncInt(List.fill(ring_dim)(v))
    case T2Double(v) =>
      T2EncDouble(List(v)) // OPTION: T2EncDouble(List.fill(ring_dim)(v))
    case T2IntArr(v) =>
      T2EncIntArr(
        v.foldLeft(List[List[Int]]())((lst, i) => lst :+ List(i)),
      )
    // OPTION: T2EncIntArr(v.foldLeft(List[List[Int]]())((lst, i) => lst :+ List.fill(ring_dim)(i)))
    case T2DoubleArr(v) =>
      T2EncDoubleArr(
        v.foldLeft(List[List[Double]]())((lst, i) => lst :+ List(i)),
      )
    // OPTION: T2EncDoubleArr(v.foldLeft(List[List[Double]]())((lst, i) => lst :+ List.fill(ring_dim)(i)))
  }

  def setLengthN[A](batched: List[A], fillValue: A, ring_dim: Int): List[A] = {
    val len = batched.size
    if (len <= 0) {
      batched
    } else if (len > ring_dim) {
      throw new Error(s"[Error] Exceed ring dim")
    } else {
      val tail = List.fill(ring_dim - len)(fillValue)
      batched ++ tail
    }
  }

  def t2data_is_true(t2data: T2Data): Boolean = t2data match {
    case T2Bool(v)                     => v
    case T2Int(v)                      => v != 0
    case T2Double(v)                   => v != 0d
    case T2EncInt(v) if v.size >= 1    => v.apply(0) != 0
    case T2EncDouble(v) if v.size >= 1 => v.apply(0) != 0d
    case _ => throw new Error(s"[Error] t2data_is_ture: Wrong type ")
  }

  def intOp(op: (Int, Int) => Int, e1: T2Data, e2: T2Data): T2Data =
    (e1, e2) match {
      case (T2Int(v1), T2Int(v2))    => T2Int(op(v1, v2))
      case (T2EncInt(v1), T2Int(v2)) => T2EncInt(v1.map(i => op(i, v2)))
      case (T2Int(v1), T2EncInt(v2)) => intOp(op, e2, e1)
      case (T2EncInt(v1), T2EncInt(v2)) => {
        val len1 = v1.size
        val len2 = v2.size
        if (len1 == len2) {
          var v = List[Int]()
          for (j <- 0 to (len1 - 1)) {
            v = v :+ op(v1.apply(j), v2.apply(j))
          }
          T2EncInt(v)
        } else if (len1 == 1) { // op(non-batched, batched)
          T2EncInt(v2.map(i => op(i, v1.apply(0))))
        } else if (len2 == 1) { // op(batched, non-batched)
          T2EncInt(v1.map(i => op(i, v2.apply(0))))
        } else
          throw new Error(s"[Error] intOp: Two EncInt have different length")
      }
      case _ => throw new Error(s"[Error] intOp: Wrong operand type")
    }

  def isDouble(e: T2Data): (Boolean, T2Data) = e match {
    case T2Double(_) | T2DoubleArr(_) | T2EncDouble(_) | T2EncDoubleArr(_) =>
      (true, e)
    case T2Int(v)    => (false, T2Double(v.toDouble))
    case T2IntArr(v) => (false, T2DoubleArr(v.map(i => i.toDouble)))
    case T2EncInt(v) => (false, T2EncDouble(v.map(i => i.toDouble)))
    case T2EncIntArr(v) =>
      (false, T2EncDoubleArr(v.map(lst => lst.map(i => i.toDouble))))
    case T2Bool(_) => (false, e) // exceptional
  }

  def makeDouble(e1: T2Data, e2: T2Data): (T2Data, T2Data) = {
    val (e1_isDouble, e1_toDouble) = isDouble(e1)
    val (e2_isDouble, e2_toDouble) = isDouble(e2)
    val casted = e1_isDouble || e2_isDouble
    if (casted) { (e1_toDouble, e2_toDouble) }
    else { (e1, e2) }
  }

  def numOp(
    op_int: (Int, Int) => Int,
    op_double: (Double, Double) => Double,
    modulus: Int,
    e1: T2Data,
    e2: T2Data,
  ): T2Data = {
    val result = makeDouble(e1, e2) match {
      case (T2Bool(v1), _) => {
        val bool2int = if (v1) 1 else 0
        numOp(op_int, op_double, modulus, T2Int(bool2int), e2)
      }
      case (_, T2Bool(v2)) => {
        val bool2int = if (v2) 1 else 0
        numOp(op_int, op_double, modulus, e1, T2Int(bool2int))
      }
      case (T2Int(v1), T2Int(v2))    => T2Int(op_int(v1, v2))
      case (T2EncInt(v1), T2Int(v2)) => T2EncInt(v1.map(i => op_int(i, v2)))
      case (T2Int(v1), T2EncInt(v2)) =>
        numOp(op_int, op_double, modulus, e2, e1)
      case (T2EncInt(v1), T2EncInt(v2)) => {
        val len1 = v1.size
        val len2 = v2.size
        if (len1 == len2) {
          var v = List[Int]()
          for (j <- 0 to (len1 - 1)) {
            v = v :+ op_int(v1.apply(j), v2.apply(j))
          }
          T2EncInt(v)
        } else if (len1 == 1) { // op(non-batched, batched)
          T2EncInt(v2.map(i => op_int(i, v1.apply(0))))
        } else if (len2 == 1) { // op(batched, non-batched)
          T2EncInt(v1.map(i => op_int(i, v2.apply(0))))
        } else
          throw new Error(s"[Error] numOp: Two EncInt have different length")
      }
      case (T2Double(v1), T2Double(v2)) => T2Double(op_double(v1, v2))
      case (T2EncDouble(v1), T2Double(v2)) =>
        T2EncDouble(v1.map(i => op_double(i, v2)))
      case (T2Double(v1), T2EncDouble(v2)) =>
        numOp(op_int, op_double, modulus, e2, e1)
      case (T2EncDouble(v1), T2EncDouble(v2)) => {
        val len1 = v1.size
        val len2 = v2.size
        if (len1 == len2) {
          var v = List[Double]()
          for (j <- 0 to (len1 - 1)) {
            v = v :+ op_double(v1.apply(j), v2.apply(j))
          }
          T2EncDouble(v)
        } else if (len1 == 1) { // op(non-batched, batched)
          T2EncDouble(v2.map(i => op_double(i, v1.apply(0))))
        } else if (len2 == 1) { // op(batched, non-batched)
          T2EncDouble(v1.map(i => op_double(i, v2.apply(0))))
        } else
          throw new Error(s"[Error] numOp: Two EncDouble have different length")
      }
      case _ => throw new Error(s"[Error] numOp: Wrong operand type")
    }
    result match {
      case T2Int(v)                     => T2Int(v % modulus)
      case T2EncInt(v)                  => T2EncInt(v.map(i => i % modulus))
      case T2Double(_) | T2EncDouble(_) => result
      case _ => throw new Error(s"[Error] numOp: Unreachable")
    }
  }

  def logicalOp(
    op_int: (Int, Int) => Boolean,
    op_double: (Double, Double) => Boolean,
    e1: T2Data,
    e2: T2Data,
  ): T2Data = makeDouble(e1, e2) match {
    case (T2Int(v1), T2Int(v2))       => T2Bool(op_int(v1, v2))
    case (T2Double(v1), T2Double(v2)) => T2Bool(op_double(v1, v2))
    case (T2EncInt(v1), T2EncInt(v2)) =>
      if ((v1.size == 1) && (v2.size == 1)) {
        T2Bool(op_int(v1.apply(0), v2.apply(0)))
      } else {
        throw new Error(
          s"[Error] logicalOp: Cannot compare batched encint ",
        )
      }
    case (T2EncDouble(v1), T2EncDouble(v2)) =>
      if ((v1.size == 1) && (v2.size == 1)) {
        T2Bool(op_double(v1.apply(0), v2.apply(0)))
      } else {
        throw new Error(
          s"[Error] logicalOp: Cannot compare batched encint ",
        )
      }
    case (T2EncInt(v1), T2Int(v2)) =>
      if (v1.size == 1) {
        T2Bool(op_int(v1.apply(0), v2))
      } else {
        throw new Error(
          s"[Error] logicalOp: Cannot compare batched encint with int",
        )
      }
    case (T2Int(v1), T2EncInt(v2)) =>
      if (v2.size == 1) {
        T2Bool(op_int(v1, v2.apply(0)))
      } else {
        throw new Error(
          s"[Error] logicalOp: Cannot compare batched encint with int",
        )
      }
    case (T2EncDouble(v1), T2Double(v2)) =>
      if (v1.size == 1) {
        T2Bool(op_double(v1.apply(0), v2))
      } else {
        throw new Error(
          s"[Error] logicalOp: Cannot compare batched encdouble with double",
        )
      }
    case (T2Double(v1), T2EncDouble(v2)) =>
      if (v2.size == 1) {
        T2Bool(op_double(v1, v2.apply(0)))
      } else {
        throw new Error(
          s"[Error] logicalOp: Cannot compare batched encdouble with double",
        )
      }
    case _ => throw new Error(s"[Error] logicalOp: Wrong operand type")
  }

  /* Interpret */

  def eval(
    nodeLstOpt: NodeListOptional,
    ring_dim: Int,
    modulus: Int,
    env: Env,
    prtlst: PrintList,
  ): (Env, PrintList) = {
    var acc = (env, prtlst)
    val enumeration = nodeLstOpt.elements()
    while (enumeration.hasMoreElements) {
      val node = enumeration.nextElement()
      val (env_cur, prtlst_cur) = acc
      acc = node match {
        case valDecl: VarDeclaration => eval(valDecl, env_cur, prtlst_cur)
        // case valDeclRst: VarDeclarationRest =>  eval(valDeclRst, env_cur, prtlst_cur)
        case stmt: Statement =>
          eval(stmt, ring_dim, modulus, env_cur, prtlst_cur)
        // case batchAsgnmtStmtRst: BatchAssignmentStatementRest =>  eval(batchAsgnmtStmtRst, env_cur, prtlst_cur)
        case _ =>
          throw new Error(
            s"[Error] NodeListOptional: Unexpected node type to eval",
          )
      }
    }
    acc
  }

  /** f0 -> MainClass() f1 -> <EOF>
    */
  def eval(goal: Goal, ring_dim: Int, modulus: Int): String = {
    val prtlst = eval(goal.f0, ring_dim, modulus, Map(), List())
    prtlst.foldLeft("") { (res, str) =>
      if (res == "") { str }
      else { res + "\n" + str }
    }
  }

  /** f0 -> "int" f1 -> "main" f2 -> "(" f3 -> "void" f4 -> ")" f5 -> "{" f6 ->
    * ( VarDeclaration() )* f7 -> ( Statement() )* f8 -> "return" f9 ->
    * Expression() f10 -> ";" f11 -> "}"
    */
  def eval(
    mainClass: MainClass,
    ring_dim: Int,
    modulus: Int,
    env: Env,
    prtlst: PrintList,
  ): PrintList = {
    val varDecls = mainClass.f6
    val stmts = mainClass.f7
    val (env_f6, prtlst_f6) = eval(varDecls, ring_dim, modulus, env, prtlst)
    val (_, prtlst_f7) = eval(stmts, ring_dim, modulus, env_f6, prtlst_f6)
    prtlst_f7
  }

  /** f0 -> Type() f1 -> Identifier() f2 -> ( VarDeclarationRest() )* f3 -> ";"
    */
  def eval(
    varDecl: VarDeclaration,
    env: Env,
    prtlst: PrintList,
  ): (Env, PrintList) = {
    val t2Type = varDecl.f0.f0.choice match {
      case _: ArrayType                => T2IntArr(List())
      case _: DoubleArrayType          => T2DoubleArr(List())
      case _: EncryptedArrayType       => T2EncIntArr(List())
      case _: EncryptedDoubleArrayType => T2EncDoubleArr(List())
      case _: IntegerType              => T2Int(0)
      case _: EncryptedIntegerType     => T2EncInt(List())
      case _: DoubleType               => T2Double(0d)
      case _: EncryptedDoubleType      => T2EncDouble(List())
      case _: BooleanType              => T2Bool(true)
      case _ => throw new Error(s"Unexpected Type: Identifier")
    }
    val id = getIdentifierName(varDecl.f1)
    val new_env = env + (id -> t2Type)
    val rest_ids = if (varDecl.f2.present()) {
      val varDeclRstLst = varDecl.f2.nodes.asScala.toList
      varDeclRstLst.map(node =>
        node match {
          case varDeclRst: VarDeclarationRest => varDeclRst.f1
          case _ =>
            throw new Error(
              s"[Error] VarDeclarationRest: Not a VarDeclarationRest",
            )
        },
      )
    } else { List[Identifier]() }
    val rest_env = rest_ids.foldLeft(Map[String, T2Data]()) { (acc, id_rest) =>
      acc + (getIdentifierName(id_rest) -> t2Type)
    }
    (new_env ++ rest_env, prtlst)
  }

  /** f0 -> Block() \| CompoundArrayAssignmentStatement() ";" \|
    * ArrayAssignmentStatement() ";" \| BatchAssignmentStatement() ";" \|
    * BatchArrayAssignmentStatement() ";" \| AssignmentStatement() ";" \|
    * IncrementAssignmentStatement() ";" \| DecrementAssignmentStatement() ";"
    * \| CompoundAssignmentStatement() ";" \| IfStatement() \| WhileStatement()
    * \| ForStatement() \| PrintStatement() ";" \| PrintBatchedStatement() ";"
    * \| ReduceNoiseStatement() ";" \| MatchParamsStatement() ";" \|
    * RotateLeftStatement() ";" \| RotateRightStatement() ";" \|
    * StartTimerStatement() ";" \| StopTimerStatement() ";"
    */
  def eval(
    stmt: Statement,
    ring_dim: Int,
    modulus: Int,
    env: Env,
    prtlst: PrintList,
  ): (Env, PrintList) =
    stmt.f0.choice match {
      case nodeSeq: NodeSequence =>
        nodeSeq.nodes.elementAt(0) match {
          case cmpdArrAsgnmtStmt: CompoundArrayAssignmentStatement =>
            (eval(cmpdArrAsgnmtStmt, modulus, env), prtlst)
          case arrAsgnmtStmt: ArrayAssignmentStatement =>
            (eval(arrAsgnmtStmt, modulus, env), prtlst)
          case batchAsgnmtStmt: BatchAssignmentStatement =>
            (eval(batchAsgnmtStmt, ring_dim, modulus, env), prtlst)
          case batchArrAsgnmtStmt: BatchArrayAssignmentStatement =>
            (eval(batchArrAsgnmtStmt, ring_dim, modulus, env), prtlst)
          case asgnmtStmt: AssignmentStatement =>
            (eval(asgnmtStmt, modulus, env), prtlst)
          case incAsgnmtStmt: IncrementAssignmentStatement =>
            (eval(incAsgnmtStmt, env), prtlst)
          case decAsgnmtStmt: DecrementAssignmentStatement =>
            (eval(decAsgnmtStmt, env), prtlst)
          case cmpdAsgnmtStmt: CompoundAssignmentStatement =>
            (eval(cmpdAsgnmtStmt, modulus, env), prtlst)
          case prtStmt: PrintStatement =>
            (env, eval(prtStmt, modulus, env, prtlst))
          case prtBatchedStmt: PrintBatchedStatement =>
            (env, eval(prtBatchedStmt, modulus, env, prtlst))
          case reduceNoiseStmt: ReduceNoiseStatement => {
            eval(reduceNoiseStmt, modulus, env) // just type check
            (env, prtlst) // nop
          }
          case matchParamsStmt: MatchParamsStatement => (env, prtlst) // nop
          case rotLeftStmt: RotateLeftStatement =>
            (eval(rotLeftStmt, modulus, env), prtlst)
          case rotRightStmt: RotateRightStatement =>
            (eval(rotRightStmt, modulus, env), prtlst)
          case _: StartTimerStatement =>
            throw new Error(s"[Unsupported] Statement: StartTimerStatement")
          case _: StopTimerStatement =>
            throw new Error(s"[Unsupported] Statement: StopTimerStatement")
        }
      case block: Block =>
        eval(
          block.f1,
          ring_dim,
          modulus,
          env,
          prtlst,
        ) // recursive (block.f1: Statement)
      case ifStmt: IfStatement => eval(ifStmt, ring_dim, modulus, env, prtlst)
      case whileStmt: WhileStatement =>
        eval(whileStmt, ring_dim, modulus, env, prtlst)
      case forStmt: ForStatement =>
        eval(forStmt, ring_dim, modulus, env, prtlst)
      case s => {
        val ty = s.getClass.getName
        throw new Error(s"[Error] Statement: Cannot match $ty")
      }
    }

  /** f0 -> Identifier() f1 -> "[" f2 -> Expression() f3 -> "]" f4 ->
    * CompoundOperator() f5 -> Expression()
    */
  def eval(
    cmpdArrAsgnmtStmt: CompoundArrayAssignmentStatement,
    modulus: Int,
    env: Env,
  ): Env = {
    val id_name = getIdentifierName(cmpdArrAsgnmtStmt.f0)
    val id = eval(cmpdArrAsgnmtStmt.f0, env)
    val idx = eval(cmpdArrAsgnmtStmt.f2, modulus, env) match {
      case T2Int(v)    => v
      case T2EncInt(v) => v.apply(0)
      case _ =>
        throw new Error(
          s"[Error] CompoundArrayAssignmentStatement: Index is not integer",
        )
    }
    val value = eval(cmpdArrAsgnmtStmt.f5, modulus, env)
    val op = cmpdArrAsgnmtStmt.f4.f0.choice match {
      case tok: NodeToken => tok.tokenImage
      case _ =>
        throw new Error(
          s"[Error] CompoundArrayAssignmentStatement: Wrong compound operator",
        )
    }
    val prev_val = id match {
      case T2IntArr(arr)       => T2Int(arr.apply(idx))
      case T2DoubleArr(arr)    => T2Double(arr.apply(idx))
      case T2EncIntArr(arr)    => T2EncInt(arr.apply(idx))
      case T2EncDoubleArr(arr) => T2EncDouble(arr.apply(idx))
      case _ =>
        throw new Error(
          s"[Error] CompoundArrayAssignmentStatement: Not an array",
        )
    }
    val new_val = op match {
      case "+="   => numOp(_ + _, _ + _, modulus, prev_val, value)
      case "-="   => numOp(_ - _, _ - _, modulus, prev_val, value)
      case "*="   => numOp(_ * _, _ * _, modulus, prev_val, value)
      case "/="   => numOp(_ / _, _ / _, modulus, prev_val, value)
      case "%="   => numOp(_ % _, _ % _, modulus, prev_val, value)
      case "<<="  => intOp(_ << _, id, value)
      case ">>="  => intOp(_ >> _, id, value)
      case ">>>=" => intOp(_ >>> _, id, value)
      case "&="   => intOp(_ & _, id, value)
      case "|="   => intOp(_ | _, id, value)
      case "^="   => intOp(_ ^ _, id, value)
    }
    (id, new_val) match {
      case (T2IntArr(lhs), T2Int(rhs)) => {
        val arr_len = lhs.size
        if (idx < arr_len) {
          val new_arr = lhs.updated(idx, rhs)
          env + (id_name -> T2IntArr(new_arr))
        } else
          throw new Error(
            s"[Error] CompoundArrayAssignmentStatement: Index is out of the bound",
          )
      }
      case (T2DoubleArr(lhs), T2Double(rhs)) => {
        val arr_len = lhs.size
        if (idx < arr_len) {
          val new_arr = lhs.updated(idx, rhs)
          env + (id_name -> T2DoubleArr(new_arr))
        } else
          throw new Error(
            s"[Error] CompoundArrayAssignmentStatement: Index is out of the bound",
          )
      }
      case (T2EncIntArr(lhs), T2EncInt(rhs)) => {
        val arr_len = lhs.size
        if (idx < arr_len) {
          val new_arr = lhs.updated(idx, rhs)
          env + (id_name -> T2EncIntArr(new_arr))
        } else
          throw new Error(
            s"[Error] CompoundArrayAssignmentStatement: Index is out of the bound",
          )
      }
      case (T2EncDoubleArr(lhs), T2EncDouble(rhs)) => {
        val arr_len = lhs.size
        if (idx < arr_len) {
          val new_arr = lhs.updated(idx, rhs)
          env + (id_name -> T2EncDoubleArr(new_arr))
        } else
          throw new Error(
            s"[Error] CompoundArrayAssignmentStatement: Index is out of the bound",
          )
      }
      case _ =>
        throw new Error(
          s"[Error] CompoundArrayAssignmentStatement: Type mismatch",
        )
    }
  }

  /** f0 -> Identifier() f1 -> "[" f2 -> Expression() f3 -> "]" f4 -> "=" f5 ->
    * Expression()
    */
  def eval(
    arrAsgnmtStmt: ArrayAssignmentStatement,
    modulus: Int,
    env: Env,
  ): Env = {
    val id_name = getIdentifierName(arrAsgnmtStmt.f0)
    val id = eval(arrAsgnmtStmt.f0, env)
    val idx = eval(arrAsgnmtStmt.f2, modulus, env) match {
      case T2Int(v)    => v
      case T2EncInt(v) => v.apply(0)
      case _ =>
        throw new Error(
          s"[Error] ArrayAssignmentStatement: Index is not integer",
        )
    }
    val value = eval(arrAsgnmtStmt.f5, modulus, env)
    id match {
      case T2IntArr(lhs) =>
        value match {
          case T2Int(rhs) => {
            val arr_len = lhs.size
            if (idx < arr_len) {
              val new_arr = lhs.updated(idx, rhs)
              env + (id_name -> T2IntArr(new_arr))
            } else
              throw new Error(
                s"[Error] ArrayAssignmentStatement: Index is out of the bound",
              )
          }
          case T2Bool(rhs) => {
            val bool2int = if (rhs) 1 else 0
            val arr_len = lhs.size
            if (idx < arr_len) {
              val new_arr = lhs.updated(idx, bool2int)
              env + (id_name -> T2IntArr(new_arr))
            } else
              throw new Error(
                s"[Error] ArrayAssignmentStatement: Index is out of the bound",
              )
          }
          case _ =>
            throw new Error(
              s"[Error] ArrayAssignmentStatement: Wrong type for assignment",
            )
        }
      case T2DoubleArr(lhs) =>
        value match {
          case T2Double(rhs) => {
            val arr_len = lhs.size
            if (idx < arr_len) {
              val new_arr = lhs.updated(idx, rhs)
              env + (id_name -> T2DoubleArr(new_arr))
            } else
              throw new Error(
                s"[Error] ArrayAssignmentStatement: Index is out of the bound",
              )
          }
          case T2Bool(rhs) => {
            val bool2double = if (rhs) 1d else 0d
            val arr_len = lhs.size
            if (idx < arr_len) {
              val new_arr = lhs.updated(idx, bool2double)
              env + (id_name -> T2DoubleArr(new_arr))
            } else
              throw new Error(
                s"[Error] ArrayAssignmentStatement: Index is out of the bound",
              )
          }
          case _ =>
            throw new Error(
              s"[Error] ArrayAssignmentStatement: Wrong type for assignment",
            )
        }
      case T2EncIntArr(lhs) =>
        value match {
          case T2EncInt(rhs) => {
            val arr_len = lhs.size
            if (idx < arr_len) {
              val new_arr = lhs.updated(idx, rhs)
              env + (id_name -> T2EncIntArr(new_arr))
            } else
              throw new Error(
                s"[Error] ArrayAssignmentStatement: Index is out of the bound",
              )
          }
          case T2Int(rhs) => {
            val arr_len = lhs.size
            if (idx < arr_len) {
              val enc_rhs = encrypt(T2Int(rhs)) match {
                case T2EncInt(v) => v
                case _ =>
                  throw new Error(
                    s"[Error] ArrayAssignmentStatement: Encryption error",
                  )
              }
              val new_arr = lhs.updated(idx, enc_rhs)
              env + (id_name -> T2EncIntArr(new_arr))
            } else
              throw new Error(
                s"[Error] ArrayAssignmentStatement: Index is out of the bound",
              )
          }
          case T2Bool(rhs) => {
            val bool2int = if (rhs) 1 else 0
            val arr_len = lhs.size
            if (idx < arr_len) {
              val enc_rhs = encrypt(T2Int(bool2int)) match {
                case T2EncInt(v) => v
                case _ =>
                  throw new Error(
                    s"[Error] ArrayAssignmentStatement: Encryption error",
                  )
              }
              val new_arr = lhs.updated(idx, enc_rhs)
              env + (id_name -> T2EncIntArr(new_arr))
            } else
              throw new Error(
                s"[Error] ArrayAssignmentStatement: Index is out of the bound",
              )
          }
          case _ =>
            throw new Error(
              s"[Error] ArrayAssignmentStatement: Wrong type for assignment",
            )
        }
      case T2EncDoubleArr(lhs) =>
        value match {
          case T2EncDouble(rhs) => {
            val arr_len = lhs.size
            if (idx < arr_len) {
              val new_arr = lhs.updated(idx, rhs)
              env + (id_name -> T2EncDoubleArr(new_arr))
            } else
              throw new Error(
                s"[Error] ArrayAssignmentStatement: Index is out of the bound",
              )
          }
          case T2Double(rhs) => {
            val arr_len = lhs.size
            if (idx < arr_len) {
              val enc_rhs = encrypt(T2Double(rhs)) match {
                case T2EncDouble(v) => v
                case _ =>
                  throw new Error(
                    s"[Error] ArrayAssignmentStatement: Encryption error",
                  )
              }
              val new_arr = lhs.updated(idx, enc_rhs)
              env + (id_name -> T2EncDoubleArr(new_arr))
            } else
              throw new Error(
                s"[Error] ArrayAssignmentStatement: Index is out of the bound",
              )
          }
          case T2Bool(rhs) => {
            val bool2double = if (rhs) then 1d else 0d
            val arr_len = lhs.size
            if (idx < arr_len) {
              val enc_rhs = encrypt(T2Double(bool2double)) match {
                case T2EncDouble(v) => v
                case _ =>
                  throw new Error(
                    s"[Error] ArrayAssignmentStatement: Encryption error",
                  )
              }
              val new_arr = lhs.updated(idx, enc_rhs)
              env + (id_name -> T2EncDoubleArr(new_arr))
            } else
              throw new Error(
                s"[Error] ArrayAssignmentStatement: Index is out of the bound",
              )
          }
          case _ =>
            throw new Error(
              s"[Error] ArrayAssignmentStatement: Wrong type for assignment",
            )
        }
      case _ =>
        throw new Error(s"[Error] ArrayAssignmentStatement: Not an array")
    }
  }

  /** f0 -> Identifier() f1 -> "=" f2 -> "{" f3 -> Expression() f4 -> (
    * BatchAssignmentStatementRest() )* f5 -> "}"
    */
  def eval(
    batchAsgnmtStmt: BatchAssignmentStatement,
    ring_dim: Int,
    modulus: Int,
    env: Env,
  ): Env = {
    val id_name = getIdentifierName(batchAsgnmtStmt.f0)
    val id = eval(batchAsgnmtStmt.f0, env)
    val exp = eval(batchAsgnmtStmt.f3, modulus, env)
    val rest_exps = if (batchAsgnmtStmt.f4.present()) {
      val batchAsgnmtStmtRstLst = batchAsgnmtStmt.f4.nodes.asScala.toList
      val explst = batchAsgnmtStmtRstLst.map(node =>
        node match {
          case batchAsgnmtStmtRst: BatchAssignmentStatementRest =>
            batchAsgnmtStmtRst.f1
          case _ =>
            throw new Error(
              s"[Error] BatchAssignmentStatementRest: Not a BatchAssignmentStatementRest",
            )
        },
      )
      explst.map(expr => eval(expr, modulus, env))
    } else { List[T2Data]() }
    val exps = exp :: rest_exps
    id match {
      case T2IntArr(_) => {
        val new_val = exps.map(t2data =>
          t2data match {
            case T2Int(v)  => v
            case T2Bool(v) => if (v) 1 else 0
            case _ =>
              throw new Error(
                s"[Error] BatchAssignmentStatement: Wrong type of data",
              )
          },
        )
        env + (id_name -> T2IntArr(new_val))
      }
      case T2DoubleArr(_) => {
        val new_val = exps.map(t2data =>
          t2data match {
            case T2Double(v) => v
            case T2Bool(v)   => if (v) 1d else 0d
            case _ =>
              throw new Error(
                s"[Error] BatchAssignmentStatement: Wrong type of data",
              )
          },
        )
        env + (id_name -> T2DoubleArr(new_val))
      }
      case T2EncInt(_) => {
        val new_val = exps.map(t2data =>
          t2data match {
            case T2Int(v)  => v
            case T2Bool(v) => if (v) 1 else 0
            case _ =>
              throw new Error(
                s"[Error] BatchAssignmentStatement: Wrong type of data",
              )
          },
        )
        val shaped_val = setLengthN(new_val, 0, ring_dim)
        env + (id_name -> T2EncInt(shaped_val))
      }
      case T2EncDouble(_) => {
        val new_val = exps.map(t2data =>
          t2data match {
            case T2Double(v) => v
            case T2Bool(v)   => if (v) 1d else 0d
            case _ =>
              throw new Error(
                s"[Error] BatchAssignmentStatement: Wrong type of data",
              )
          },
        )
        val shaped_val = setLengthN(new_val, 0d, ring_dim)
        env + (id_name -> T2EncDouble(shaped_val))
      }
      case T2EncIntArr(_) => {
        val new_val = exps.map(t2data =>
          t2data match {
            case T2Bool(v)   => if (v) List(1) else List(0) // encrypt
            case T2Int(v)    => List(v) // encrypt
            case T2EncInt(v) => v
            case _ =>
              throw new Error(
                s"[Error] BatchAssignmentStatement: Wrong type of data",
              )
          },
        )
        env + (id_name -> T2EncIntArr(new_val))
      }
      case T2EncDoubleArr(_) => {
        val new_val = exps.map(t2data =>
          t2data match {
            case T2Bool(v)      => if (v) List(1d) else List(0d) // encrypt
            case T2Double(v)    => List(v) // encrypt
            case T2EncDouble(v) => v
            case _ =>
              throw new Error(
                s"[Error] BatchAssignmentStatement: Wrong type of data",
              )
          },
        )
        env + (id_name -> T2EncDoubleArr(new_val))
      }
      case _ => throw new Error(s"[Error] BatchAssignmentStatement: Wrong type")
    }
  }

  /** f0 -> Identifier() f1 -> "[" f2 -> Expression() f3 -> "]" f4 -> "=" f5 ->
    * "{" f6 -> Expression() f7 -> ( BatchAssignmentStatementRest() )* f8 -> "}"
    */
  def eval(
    batchArrAsgnmtStmt: BatchArrayAssignmentStatement,
    ring_dim: Int,
    modulus: Int,
    env: Env,
  ): Env = {
    val id_name = getIdentifierName(batchArrAsgnmtStmt.f0)
    val id = eval(batchArrAsgnmtStmt.f0, env)
    val idx = eval(batchArrAsgnmtStmt.f2, modulus, env) match {
      case T2Int(v)    => v
      case T2EncInt(v) => v.apply(0)
      case _ =>
        throw new Error(
          s"[Error] BatchArrayAssignmentStatement: Index is not integer",
        )
    }
    val exp = eval(batchArrAsgnmtStmt.f6, modulus, env)
    val rest_exps = if (batchArrAsgnmtStmt.f7.present()) {
      val batchAsgnmtStmtRstLst = batchArrAsgnmtStmt.f7.nodes.asScala.toList
      val explst = batchAsgnmtStmtRstLst.map(node =>
        node match {
          case batchAsgnmtStmtRst: BatchAssignmentStatementRest =>
            batchAsgnmtStmtRst.f1
          case _ =>
            throw new Error(
              s"[Error] BatchAssignmentStatementRest: Not a BatchAssignmentStatementRest",
            )
        },
      )
      explst.map(expr => eval(expr, modulus, env))
    } else { List[T2Data]() }
    val exps = exp :: rest_exps
    id match {
      case T2EncIntArr(lhs) => {
        val arr_len = lhs.size
        if (idx < arr_len) {
          val new_val = exps.map(t2data =>
            t2data match {
              case T2Int(v)  => v
              case T2Bool(v) => if (v) 1 else 0
              case _ =>
                throw new Error(
                  s"[Error] BatchArrayAssignmentStatement: Wrong type of data",
                )
            },
          )
          val shaped_val = setLengthN(new_val, 0, ring_dim)
          val new_arr = lhs.updated(idx, shaped_val)
          env + (id_name -> T2EncIntArr(new_arr))
        } else
          throw new Error(
            s"[Error] BatchArrayAssignmentStatement: Index is out of the bound",
          )
      }
      case T2EncDoubleArr(lhs) => {
        val arr_len = lhs.size
        if (idx < arr_len) {
          val new_val = exps.map(t2data =>
            t2data match {
              case T2Double(v) => v
              case T2Bool(v)   => if (v) 1d else 0d
              case _ =>
                throw new Error(
                  s"[Error] BatchArrayAssignmentStatement: Wrong type of data",
                )
            },
          )
          val shaped_val = setLengthN(new_val, 0d, ring_dim)
          val new_arr = lhs.updated(idx, shaped_val)
          env + (id_name -> T2EncDoubleArr(new_arr))
        } else
          throw new Error(
            s"[Error] BatchArrayAssignmentStatement: Index is out of the bound",
          )
      }
      case _ =>
        throw new Error(s"[Error] BatchArrayAssignmentStatement: Wrong type")
    }
  }

  /** f0 -> Identifier() f1 -> "=" f2 -> Expression()
    */
  def eval(
    asgnmtStmt: AssignmentStatement,
    modulus: Int,
    env: Env,
  ): Env = {
    val id_name = getIdentifierName(asgnmtStmt.f0)
    val id = eval(asgnmtStmt.f0, env)
    val exp = eval(asgnmtStmt.f2, modulus, env)
    (id, exp) match {
      case (T2Bool(_), T2Bool(_)) | (T2Int(_), T2Int(_)) |
          (T2Double(_), T2Double(_)) | (T2IntArr(_), T2IntArr(_)) |
          (T2DoubleArr(_), T2DoubleArr(_)) | (T2EncInt(_), T2EncInt(_)) |
          (T2EncDouble(_), T2EncDouble(_)) | (T2EncIntArr(_), T2EncIntArr(_)) |
          (T2EncDoubleArr(_), T2EncDoubleArr(_)) =>
        env + (id_name -> exp)
      case (T2EncInt(_), T2Int(_)) | (T2EncDouble(_), T2Double(_)) |
          (T2EncIntArr(_), T2IntArr(_)) | (T2EncDoubleArr(_), T2DoubleArr(_)) =>
        env + (id_name -> encrypt(exp)) // encrypt
      case (_, T2Bool(v)) => {
        val bool2int = if (v) 1 else 0
        val bool2double = if (v) 1d else 0d
        id match {
          case T2Int(_)    => env + (id_name -> T2Int(bool2int))
          case T2Double(_) => env + (id_name -> T2Double(bool2double))
          case T2EncInt(_) => env + (id_name -> T2EncInt(List(bool2int)))
          case T2EncDouble(_) =>
            env + (id_name -> T2EncDouble(List(bool2double)))
        }
      }
      case (e1, e2) => {
        val e1_name = e1.getClass.getName
        val e2_name = e2.getClass.getName
        throw new Error(
          s"[Error] AssignmentStatement: Wrong type ($e1_name, $e2_name)",
        )
      }
    }
  }

  /** f0 -> Identifier() f1 -> "++"
    */
  def eval(incAsgnmtStmt: IncrementAssignmentStatement, env: Env): Env = {
    val id_name = getIdentifierName(incAsgnmtStmt.f0)
    val id = eval(incAsgnmtStmt.f0, env)
    val new_val = id match {
      case T2Int(v)       => T2Int(v + 1)
      case T2Double(v)    => T2Double(v + 1d)
      case T2EncInt(v)    => T2EncInt(v.map(i => i + 1))
      case T2EncDouble(v) => T2EncDouble(v.map(i => i + 1d))
      case _ =>
        throw new Error(
          s"[Error] IncrementAssignmentStatement: Wrong operand type",
        )
    }
    env + (id_name -> new_val)
  }

  /** f0 -> Identifier() f1 -> "--"
    */
  def eval(decAsgnmtStmt: DecrementAssignmentStatement, env: Env): Env = {
    val id_name = getIdentifierName(decAsgnmtStmt.f0)
    val id = eval(decAsgnmtStmt.f0, env)
    val new_val = id match {
      case T2Int(v)       => T2Int(v - 1)
      case T2Double(v)    => T2Double(v - 1d)
      case T2EncInt(v)    => T2EncInt(v.map(i => i - 1))
      case T2EncDouble(v) => T2EncDouble(v.map(i => i - 1d))
      case _ =>
        throw new Error(
          s"[Error] DecrementAssignmentStatement: Wrong operand type",
        )
    }
    env + (id_name -> new_val)
  }

  /** f0 -> Identifier() f1 -> CompoundOperator() f2 -> Expression()
    */
  def eval(
    cmpdAsgnmtStmt: CompoundAssignmentStatement,
    modulus: Int,
    env: Env,
  ): Env = {
    val id_name = getIdentifierName(cmpdAsgnmtStmt.f0)
    val id = eval(cmpdAsgnmtStmt.f0, env)
    val exp = eval(cmpdAsgnmtStmt.f2, modulus, env)
    val op = cmpdAsgnmtStmt.f1.f0.choice match {
      case tok: NodeToken => tok.tokenImage
      case _ =>
        throw new Error(
          s"[Error] CompoundAssignmentStatement: Wrong compound operator",
        )
    }
    val new_val = op match {
      case "+="   => numOp(_ + _, _ + _, modulus, id, exp)
      case "-="   => numOp(_ - _, _ - _, modulus, id, exp)
      case "*="   => numOp(_ * _, _ * _, modulus, id, exp)
      case "/="   => numOp(_ / _, _ / _, modulus, id, exp)
      case "%="   => numOp(_ % _, _ % _, modulus, id, exp)
      case "<<="  => intOp(_ << _, id, exp)
      case ">>="  => intOp(_ >> _, id, exp)
      case ">>>=" => intOp(_ >>> _, id, exp)
      case "&="   => intOp(_ & _, id, exp)
      case "|="   => intOp(_ | _, id, exp)
      case "^="   => intOp(_ ^ _, id, exp)
    }
    env + (id_name -> new_val)
  }

  /** f0 -> IfthenElseStatement() \| IfthenStatement()
    */
  def eval(
    ifStmt: IfStatement,
    ring_dim: Int,
    modulus: Int,
    env: Env,
    prtlst: PrintList,
  ): (Env, PrintList) =
    ifStmt.f0.choice match {
      case ifthenElseStmt: IfthenElseStatement => {
        val cond = eval(ifthenElseStmt.f2, modulus, env)
        val then_stmt = eval(ifthenElseStmt.f4, ring_dim, modulus, env, prtlst)
        val else_stmt = eval(ifthenElseStmt.f6, ring_dim, modulus, env, prtlst)
        cond match {
          case T2Bool(v) =>
            if (v) { then_stmt }
            else { else_stmt }
          case T2Int(v) =>
            if (v != 0) { then_stmt }
            else { else_stmt }
          case T2EncInt(v) =>
            if (v.apply(0) != 0) { then_stmt }
            else { else_stmt }
          case _ => throw new Error(s"[Error] IfthenElseStatement")
        }
      }
      case ifthenStmt: IfthenStatement => {
        val cond = eval(ifthenStmt.f2, modulus, env)
        cond match {
          case T2Bool(v) if (v) =>
            eval(ifthenStmt.f4, ring_dim, modulus, env, prtlst)
          case T2Int(v) if (v != 0) =>
            eval(ifthenStmt.f4, ring_dim, modulus, env, prtlst)
          case T2EncInt(v) if (v.apply(0) != 0) =>
            eval(ifthenStmt.f4, ring_dim, modulus, env, prtlst)
          case _ => (env, prtlst)
        }
      }
      case _ => throw new Error(s"[Error] IfStatement")
    }

  /** f0 -> "while" f1 -> "(" f2 -> Expression() f3 -> ")" f4 -> Statement()
    */
  def eval(
    whileStmt: WhileStatement,
    ring_dim: Int,
    modulus: Int,
    env: Env,
    prtlst: PrintList,
  ): (Env, PrintList) = {
    var env_updated = env
    var prtlst_updated = prtlst
    var cond_exp = whileStmt.f2
    while (t2data_is_true(eval(cond_exp, modulus, env_updated))) {
      val (new_env, new_prtlst) =
        eval(whileStmt.f4, ring_dim, modulus, env_updated, prtlst_updated)
      env_updated = new_env
      prtlst_updated = new_prtlst
    }
    (env_updated, prtlst_updated)
  }

  /** f0 -> "for" f1 -> "(" f2 -> AssignmentStatement() f3 -> ";" f4 ->
    * Expression() f5 -> ";" f6 -> ( AssignmentStatement() |
    * IncrementAssignmentStatement() | DecrementAssignmentStatement() |
    * CompoundAssignmentStatement() ) f7 -> ")" f8 -> Statement()
    */
  def eval(
    forStmt: ForStatement,
    ring_dim: Int,
    modulus: Int,
    env: Env,
    prtlst: PrintList,
  ): (Env, PrintList) = {
    // Capture original env
    val prev_id_name = getIdentifierName(forStmt.f2.f0)
    val prev_id = eval(forStmt.f2.f0, env)
    // for loop
    var env_updated = eval(forStmt.f2, modulus, env)
    var prtlst_updated = prtlst
    var cond_exp = forStmt.f4
    while (t2data_is_true(eval(cond_exp, modulus, env_updated))) {
      val (new_env, new_prtlst) =
        eval(forStmt.f8, ring_dim, modulus, env_updated, prtlst_updated)
      env_updated = forStmt.f6.choice match {
        case asgnmtStmt: AssignmentStatement =>
          eval(asgnmtStmt, modulus, new_env)
        case incAsgnmtStmt: IncrementAssignmentStatement =>
          eval(incAsgnmtStmt, new_env)
        case decAsgnmtStmt: DecrementAssignmentStatement =>
          eval(decAsgnmtStmt, new_env)
        case cmpdAsgnmtStmt: CompoundAssignmentStatement =>
          eval(cmpdAsgnmtStmt, modulus, new_env)
      }
      prtlst_updated = new_prtlst
    }
    env_updated = env_updated + (prev_id_name -> prev_id)
    (env_updated, prtlst_updated)
  }

  /** f0 -> "print" f1 -> "(" f2 -> Expression() f3 -> ")"
    */
  def eval(
    prtStmt: PrintStatement,
    modulus: Int,
    env: Env,
    prtlst: PrintList,
  ): PrintList = {
    val t2data = eval(prtStmt.f2, modulus, env)
    t2data match {
      case T2Int(v)    => prtlst :+ v.toString
      case T2Double(v) => prtlst :+ v.toString
      case T2EncInt(v) =>
        prtlst :+ v.apply(0).toString
      case T2EncDouble(v) =>
        prtlst :+ v.apply(0).toString
      case T2IntArr(_) | T2DoubleArr(_) | T2EncIntArr(_) | T2EncDoubleArr(_) =>
        throw new Error(s"PrintStatement: Bad type")
      case T2Bool(v) =>
        if (v) { prtlst :+ "true" }
        else { prtlst :+ "false" }
    }
  }

  /** f0 -> "print_batched" f1 -> "(" f2 -> Expression() f3 -> "," f4 ->
    * Expression() f5 -> ")"
    */
  def eval(
    prtBatchedStmt: PrintBatchedStatement,
    modulus: Int,
    env: Env,
    prtlst: PrintList,
  ): PrintList = {
    val t2data = eval(prtBatchedStmt.f2, modulus, env)
    val size = eval(prtBatchedStmt.f4, modulus, env) match {
      case T2Int(v) => v
      case _ =>
        throw new Error(s"[Error] PrintBatchedStatement: Size is not integer")
    }
    t2data match {
      case T2EncInt(v) => {
        var str = ""
        if (size <= v.size) {
          for (i <- v.slice(0, size)) { str = str + formatNumber(i) + " " }
        } else if (v.size == 1) { // non-batched
          val n = v.apply(0)
          for (i <- 0 to (size - 1)) { str = str + formatNumber(n) + " " }
        }
        prtlst :+ str
      }
      case T2EncDouble(v) => {
        var str = ""
        if (size <= v.size) {
          for (i <- v.slice(0, size)) { str = str + formatNumber(i) + " " }
        } else if (v.size == 1) { // non-batched
          val n = v.apply(0)
          for (i <- 0 to (size - 1)) { str = str + formatNumber(n) + " " }
        }
        prtlst :+ str
      }
      case _ =>
        throw new Error(s"[Error] PrintBatchedStatement: Wrong expression type")
    }
  }

  /** f0 -> <REDUCE_NOISE> f1 -> "(" f2 -> Expression() f3 -> ")"
    */
  def eval(
    reduceNoiseStmt: ReduceNoiseStatement,
    modulus: Int,
    env: Env,
  ): Unit =
    eval(reduceNoiseStmt.f2, modulus, env) match {
      case T2EncInt(_) | T2EncDouble(_) | T2EncIntArr(_) | T2EncDoubleArr(_) =>
        ()
      case _ =>
        throw new Error(s"[Error] ReduceNoiseStatement: Wrong expression type")
    }

  /** f0 -> <ROTATE_LEFT> f1 -> "(" f2 -> Expression() f3 -> "," f4 ->
    * Expression() f5 -> ")"
    */
  def eval(rotLeftStmt: RotateLeftStatement, modulus: Int, env: Env): Env = {
    val id_expr = rotLeftStmt.f2.f0.choice match {
      case clause: Clause =>
        clause.f0.choice match {
          case primExpr: PrimaryExpression =>
            primExpr.f0.choice match {
              case id: Identifier => id
              case _ =>
                throw new Error(
                  s"[Error] RotateLeftStatement: Should rotate an identifier",
                )
            }
          case _ =>
            throw new Error(
              s"[Error] RotateLeftStatement: Should rotate an identifier",
            )
        }
      case _ =>
        throw new Error(
          s"[Error] RotateLeftStatement: Should rotate an identifier",
        )
    }
    val id_name = getIdentifierName(id_expr)
    var id = eval(id_expr, env)
    val n = eval(rotLeftStmt.f4, modulus, env) match {
      case T2Int(v) => v
      case _ =>
        throw new Error(s"[Error] RotateLeftStatement: n is not integer")
    }
    id match {
      case T2Int(_) | T2Double(_) | T2Bool(_) =>
        throw new Error(s"[Error] RotateLeftStatement: Can not rotate")
      case T2IntArr(v) => {
        val size = v.size
        val (front, tail) = v.splitAt(n % size)
        env + (id_name -> T2IntArr(tail ++ front))
      }
      case T2DoubleArr(v) => {
        val size = v.size
        val (front, tail) = v.splitAt(n % size)
        env + (id_name -> T2DoubleArr(tail ++ front))
      }
      case T2EncInt(v) => {
        val size = v.size
        val (front, tail) = v.splitAt(n % size)
        env + (id_name -> T2EncInt(tail ++ front))
      }
      case T2EncDouble(v) => {
        val size = v.size
        val (front, tail) = v.splitAt(n % size)
        env + (id_name -> T2EncDouble(tail ++ front))
      }
      case T2EncIntArr(v) => {
        val size = v.size
        val (front, tail) = v.splitAt(n % size)
        env + (id_name -> T2EncIntArr(tail ++ front))
      }
      case T2EncDoubleArr(v) => {
        val size = v.size
        val (front, tail) = v.splitAt(n % size)
        env + (id_name -> T2EncDoubleArr(tail ++ front))
      }
    }
  }

  /** f0 -> <ROTATE_RIGHT> f1 -> "(" f2 -> Expression() f3 -> "," f4 ->
    * Expression() f5 -> ")"
    */
  def eval(rotRightStmt: RotateRightStatement, modulus: Int, env: Env): Env = {
    val id_expr = rotRightStmt.f2.f0.choice match {
      case clause: Clause =>
        clause.f0.choice match {
          case primExpr: PrimaryExpression =>
            primExpr.f0.choice match {
              case id: Identifier => id
              case _ =>
                throw new Error(
                  s"[Error] RotateLeftStatement: Should rotate an identifier",
                )
            }
          case _ =>
            throw new Error(
              s"[Error] RotateLeftStatement: Should rotate an identifier",
            )
        }
      case _ =>
        throw new Error(
          s"[Error] RotateLeftStatement: Should rotate an identifier",
        )
    }
    val id_name = getIdentifierName(id_expr)
    var id = eval(id_expr, env)
    val n = eval(rotRightStmt.f4, modulus, env) match {
      case T2Int(v) => v
      case _ =>
        throw new Error(s"[Error] RotateLeftStatement: n is not integer")
    }
    id match {
      case T2Int(_) | T2Double(_) | T2Bool(_) =>
        throw new Error(s"[Error] RotateLeftStatement: Can not rotate")
      case T2IntArr(v) => {
        val size = v.size
        val (front, tail) = v.splitAt(size - (n % size))
        env + (id_name -> T2IntArr(tail ++ front))
      }
      case T2DoubleArr(v) => {
        val size = v.size
        val (front, tail) = v.splitAt(size - (n % size))
        env + (id_name -> T2DoubleArr(tail ++ front))
      }
      case T2EncInt(v) => {
        val size = v.size
        val (front, tail) = v.splitAt(size - (n % size))
        env + (id_name -> T2EncInt(tail ++ front))
      }
      case T2EncDouble(v) => {
        val size = v.size
        val (front, tail) = v.splitAt(size - (n % size))
        env + (id_name -> T2EncDouble(tail ++ front))
      }
      case T2EncIntArr(v) => {
        val size = v.size
        val (front, tail) = v.splitAt(size - (n % size))
        env + (id_name -> T2EncIntArr(tail ++ front))
      }
      case T2EncDoubleArr(v) => {
        val size = v.size
        val (front, tail) = v.splitAt(size - (n % size))
        env + (id_name -> T2EncDoubleArr(tail ++ front))
      }
    }
  }

  /** f0 -> LogicalAndExpression() \| LogicalOrExpression() \|
    * BinaryExpression() \| BinNotExpression() \| ArrayLookup() \| ArrayLength()
    * \| TernaryExpression() \| Clause()
    */
  def eval(expr: Expression, modulus: Int, env: Env): T2Data =
    expr.f0.choice match {
      case andExpr: LogicalAndExpression => eval(andExpr, modulus, env)
      case orExpr: LogicalOrExpression   => eval(orExpr, modulus, env)
      case binExpr: BinaryExpression     => eval(binExpr, modulus, env)
      case binNotExpr: BinNotExpression  => eval(binNotExpr, modulus, env)
      case arrLookup: ArrayLookup        => eval(arrLookup, modulus, env)
      case arrLen: ArrayLength           => eval(arrLen, modulus, env)
      case terExpr: TernaryExpression    => eval(terExpr, modulus, env)
      case clause: Clause                => eval(clause, modulus, env)
    }

  /** f0 -> Clause() f1 -> "&&" f2 -> Clause()
    */
  def eval(andExpr: LogicalAndExpression, modulus: Int, env: Env): T2Data = {
    val e1 = eval(andExpr.f0, modulus, env)
    val e2 = eval(andExpr.f2, modulus, env)
    if (t2data_is_true(e1) && t2data_is_true(e2)) { T2Bool(true) }
    else { T2Bool(false) }
  }

  /** f0 -> Clause() f1 -> "||" f2 -> Clause()
    */
  def eval(orExpr: LogicalOrExpression, modulus: Int, env: Env): T2Data = {
    val e1 = eval(orExpr.f0, modulus, env)
    val e2 = eval(orExpr.f2, modulus, env)
    if (t2data_is_true(e1) || t2data_is_true(e2)) { T2Bool(true) }
    else { T2Bool(false) }
  }

  /** f0 -> PrimaryExpression() f1 -> BinOperator() f2 -> PrimaryExpression()
    */
  def eval(binExpr: BinaryExpression, modulus: Int, env: Env): T2Data = {
    val e1 = eval(binExpr.f0, modulus, env)
    val e2 = eval(binExpr.f2, modulus, env)
    val op = binExpr.f1.f0.choice match {
      case tok: NodeToken => tok.tokenImage
      case _ =>
        throw new Error(s"[Error] BinaryExpression: Wrong binary operator")
    }
    op match {
      case "&"   => intOp(_ & _, e1, e2)
      case "|"   => intOp(_ | _, e1, e2)
      case "^"   => intOp(_ ^ _, e1, e2)
      case "<<"  => intOp(_ << _, e1, e2)
      case ">>"  => intOp(_ >> _, e1, e2)
      case ">>>" => intOp(_ >>> _, e1, e2)
      case "+"   => numOp(_ + _, _ + _, modulus, e1, e2)
      case "-"   => numOp(_ - _, _ - _, modulus, e1, e2)
      case "*"   => numOp(_ * _, _ * _, modulus, e1, e2)
      case "/"   => numOp(_ / _, _ / _, modulus, e1, e2)
      case "%"   => numOp(_ % _, _ % _, modulus, e1, e2)
      case "=="  => logicalOp(_ == _, _ == _, e1, e2)
      case "!="  => logicalOp(_ != _, _ != _, e1, e2)
      case "<"   => logicalOp(_ < _, _ < _, e1, e2)
      case "<="  => logicalOp(_ <= _, _ <= _, e1, e2)
      case ">"   => logicalOp(_ > _, _ > _, e1, e2)
      case ">="  => logicalOp(_ >= _, _ >= _, e1, e2)
    }
  }

  /** f0 -> "~" f1 -> PrimaryExpression()
    */
  def eval(binNotExpr: BinNotExpression, modulus: Int, env: Env): T2Data =
    eval(binNotExpr.f1, modulus, env) match {
      case T2Int(v)    => T2Int(~v)
      case T2EncInt(v) => T2EncInt(v.map(i => ~i))
    }

  /** f0 -> PrimaryExpression() f1 -> "[" f2 -> PrimaryExpression() f3 -> "]"
    */
  def eval(arrLookup: ArrayLookup, modulus: Int, env: Env): T2Data = {
    val arr = eval(arrLookup.f0, modulus, env)
    val idx = eval(arrLookup.f2, modulus, env) match {
      case T2Int(v)    => v
      case T2EncInt(v) => v.apply(0)
      case _ => throw new Error(s"[Error] ArrayLookup: Index is not integer")
    }
    arr match {
      case T2IntArr(v)       => T2Int(v.apply(idx))
      case T2DoubleArr(v)    => T2Double(v.apply(idx))
      case T2EncIntArr(v)    => T2EncInt(v.apply(idx))
      case T2EncDoubleArr(v) => T2EncDouble(v.apply(idx))
      case _ => throw new Error(s"[Error] ArrayLookup: Not an array")
    }
  }

  /** f0 -> PrimaryExpression() f1 -> "." f2 -> <ARRAY_SIZE> //"size"
    */
  def eval(arrLen: ArrayLength, modulus: Int, env: Env): T2Data =
    eval(arrLen.f0, modulus, env) match {
      case T2IntArr(v)       => T2Int(v.size)
      case T2DoubleArr(v)    => T2Int(v.size)
      case T2EncIntArr(v)    => T2Int(v.size)
      case T2EncDoubleArr(v) => T2Int(v.size)
      case _ => throw new Error(s"[Error] ArrayLength: Not an array")
    }

  /** f0 -> "(" f1 -> Expression() f2 -> ")" f3 -> "?" f4 -> Expression() f5 ->
    * ":" f6 -> Expression()
    */
  def eval(terExpr: TernaryExpression, modulus: Int, env: Env): T2Data = {
    val cond = eval(terExpr.f1, modulus, env)
    val then_expr = eval(terExpr.f4, modulus, env)
    val else_expr = eval(terExpr.f6, modulus, env)
    cond match {
      case T2Bool(v) =>
        if (v) { then_expr }
        else { else_expr }
      case T2Int(v) =>
        if (v != 0) { then_expr }
        else { else_expr }
      case T2EncInt(v) =>
        if (v.apply(0) != 0) { then_expr }
        else { else_expr }
      case _ => throw new Error(s"[Error] TernaryExpression")
    }
  }

  /** f0 -> NotExpression() \| PrimaryExpression()
    */
  def eval(clause: Clause, modulus: Int, env: Env): T2Data =
    clause.f0.choice match {
      case notExpr: NotExpression      => eval(notExpr, modulus, env)
      case primExpr: PrimaryExpression => eval(primExpr, modulus, env)
    }

  /** f0 -> "!" f1 -> Clause()
    */
  def eval(notExpr: NotExpression, modulus: Int, env: Env): T2Data = {
    val clause = eval(notExpr.f1, modulus, env)
    if (t2data_is_true(clause)) { T2Bool(false) }
    else { T2Bool(true) }
  }

  /** f0 -> IntegerLiteral() \| DoubleLiteral() \| TrueLiteral() \|
    * FalseLiteral() \| Identifier() \| ArrayAllocationExpression() \|
    * EncryptedArrayAllocationExpression() \| ArrayDoubleAllocationExpression()
    * \| EncryptedArrayDoubleAllocationExpression() \| BracketExpression()
    */
  def eval(primExpr: PrimaryExpression, modulus: Int, env: Env): T2Data =
    primExpr.f0.choice match {
      case intLitreral: IntegerLiteral => T2Int(intLitreral.f0.tokenImage.toInt)
      case doubleLiteral: DoubleLiteral =>
        T2Double(doubleLiteral.f0.tokenImage.toDouble)
      case _: TrueLiteral  => T2Bool(true)
      case _: FalseLiteral => T2Bool(false)
      case id: Identifier  => eval(id, env)
      case intArrAllocExpr: ArrayAllocationExpression =>
        eval(intArrAllocExpr, modulus, env)
      case encIntArrAllocExpr: EncryptedArrayAllocationExpression =>
        eval(encIntArrAllocExpr, modulus, env)
      case doubleArrAllocExpr: ArrayDoubleAllocationExpression =>
        eval(doubleArrAllocExpr, modulus, env)
      case encDoubleArrAllocExpr: EncryptedArrayDoubleAllocationExpression =>
        eval(encDoubleArrAllocExpr, modulus, env)
      case bracketExpr: BracketExpression => eval(bracketExpr.f1, modulus, env)
    }

  /** f0 -> <IDENTIFIER>
    */
  def eval(id: Identifier, env: Env): T2Data = {
    val id_name = getIdentifierName(id)
    env.get(id_name) match {
      case None =>
        throw new Error(s"[Error] Identifier: Free Identifier $id_name")
      case Some(v) => v
    }
  }

  /** f0 -> "new" f1 -> "int" f2 -> "[" f3 -> Expression() f4 -> "]"
    */
  def eval(
    intArrAllocExpr: ArrayAllocationExpression,
    modulus: Int,
    env: Env,
  ): T2Data = {
    val size = eval(intArrAllocExpr.f3, modulus: Int, env) match {
      case T2Int(v)    => v
      case T2EncInt(v) => v.apply(0)
      case _ =>
        throw new Error(
          s"[Error] ArrayAllocationExpression: alloc size is not int",
        )
    }
    var lst = (1 to size).foldLeft(List[Int]()) { (acc, _) => 0 :: acc }
    T2IntArr(lst)
  }

  /** f0 -> "new" f1 -> "EncInt" f2 -> "[" f3 -> Expression() f4 -> "]"
    */
  def eval(
    encIntArrAllocExpr: EncryptedArrayAllocationExpression,
    modulus: Int,
    env: Env,
  ): T2Data = {
    val size = eval(encIntArrAllocExpr.f3, modulus, env) match {
      case T2Int(v)    => v
      case T2EncInt(v) => v.apply(0)
      case _ =>
        throw new Error(
          s"[Error] ArrayAllocationExpression: alloc size is not int",
        )
    }
    var lst = (1 to size).foldLeft(List[List[Int]]()) { (acc, _) =>
      List[Int]() :: acc
    }
    T2EncIntArr(lst)
  }

  /** f0 -> "new" f1 -> "double" f2 -> "[" f3 -> Expression() f4 -> "]"
    */
  def eval(
    doubleArrAllocExpr: ArrayDoubleAllocationExpression,
    modulus: Int,
    env: Env,
  ): T2Data = {
    val size = eval(doubleArrAllocExpr.f3, modulus, env) match {
      case T2Int(v)    => v
      case T2EncInt(v) => v.apply(0)
      case _ =>
        throw new Error(
          s"[Error] ArrayDoubleAllocationExpression: alloc size is not int",
        )
    }
    var lst = (1 to size).foldLeft(List[Double]()) { (acc, _) => 0d :: acc }
    T2DoubleArr(lst)
  }

  /** f0 -> "new" f1 -> "EncDouble" f2 -> "[" f3 -> Expression() f4 -> "]"
    */
  def eval(
    encDoubleArrAllocExpr: EncryptedArrayDoubleAllocationExpression,
    modulus: Int,
    env: Env,
  ): T2Data = {
    val size = eval(encDoubleArrAllocExpr.f3, modulus, env) match {
      case T2Int(v)    => v
      case T2EncInt(v) => v.apply(0)
      case _ =>
        throw new Error(
          s"[Error] ArrayDoubleAllocationExpression: alloc size is not int",
        )
    }
    var lst = (1 to size).foldLeft(List[List[Double]]()) { (acc, _) =>
      List[Double]() :: acc
    }
    T2EncDoubleArr(lst)
  }

}
