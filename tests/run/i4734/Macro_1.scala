import scala.annotation.tailrec
import scala.quoted._

object Macros {
  rewrite def unrolledForeach(seq: IndexedSeq[Int], f: => Int => Unit, transparent unrollSize: Int): Unit = // or f: Int => Unit
    ~unrolledForeachImpl('(seq), '(f), unrollSize)

  def unrolledForeachImpl(seq: Expr[IndexedSeq[Int]], f: Expr[Int => Unit], unrollSize: Int): Expr[Unit] = '{
    val size = (~seq).length
    assert(size % (~unrollSize.toExpr) == 0) // for simplicity of the implementation
    var i = 0
    while (i < size) {
      ~{
        for (j <- new UnrolledRange(0, unrollSize)) '{
          val index = i + ~j.toExpr
          val element = (~seq)(index)
          ~f('(element)) // or `(~f)(element)` if `f` should not be inlined
        }
      }
      i += ~unrollSize.toExpr
    }

  }

  class UnrolledRange(start: Int, end: Int) {
    def foreach(f: Int => Expr[Unit]): Expr[Unit] = {
      @tailrec def loop(i: Int, acc: Expr[Unit]): Expr[Unit] =
        if (i >= 0) loop(i - 1, '{ ~f(i); ~acc })
      else acc
      loop(end - 1, '())
    }
  }
}
