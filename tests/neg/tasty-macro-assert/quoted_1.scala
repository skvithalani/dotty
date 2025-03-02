import scala.quoted._

import scala.tasty._

object Asserts {

  implicit class Ops[T](left: T) {
    def ===(right: T): Boolean = left == right
    def !==(right: T): Boolean = left != right
  }

  object Ops

  rewrite def macroAssert(cond: => Boolean): Unit =
    ~impl('(cond))

  def impl(cond: Expr[Boolean])(implicit tasty: Tasty): Expr[Unit] = {
    import tasty._

    val tree = cond.toTasty

    def isOps(tpe: TypeOrBounds): Boolean = tpe match {
      case Type.SymRef(sym, _) =>
        sym.tree match {
          case Some(DefDef("Ops", _, _, _, _)) => true // TODO check that the parent is Asserts
          case _ => false
        }
      case _ => false
    }

    object OpsTree {
      def unapply(arg: Term): Option[Term] = arg match {
        case Term.Apply(Term.TypeApply(term, _), left :: Nil) if isOps(term.tpe) =>
          Some(left)
        case _ => None
      }
    }

    tree match {
      case Term.Inlined(_, Nil, Term.Apply(Term.Select(OpsTree(left), op, _), right :: Nil)) =>
        '(assertTrue(~left.toExpr[Boolean])) // Buggy code. To generate the errors
      case _ =>
        '(assertTrue(~cond))
    }

  }

  def assertEquals[T](left: T, right: T): Unit = {
    if (left != right) {
      println(
        s"""Error left did not equal right:
           |  left  = $left
           |  right = $right""".stripMargin)
    }

  }

  def assertNotEquals[T](left: T, right: T): Unit = {
    if (left == right) {
      println(
        s"""Error left was equal to right:
           |  left  = $left
           |  right = $right""".stripMargin)
    }

  }

  def assertTrue(cond: Boolean): Unit = {
    if (!cond)
      println("Condition was false")
  }

}
