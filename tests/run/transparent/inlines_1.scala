package p
import collection.mutable

object transparents {
  final val monitored = false

  rewrite def f(x: Int): Int = x * x

  val hits = new mutable.HashMap[String, Int] {
    override def default(key: String): Int = 0
  }

  def record(fn: String, n: Int = 1) = {
    if (monitored) {
      val name = if (fn.startsWith("member-")) "member" else fn
      hits(name) += n
    }
  }

  @volatile private var stack: List[String] = Nil

  rewrite def track[T](fn: String)(op: => T) =
    if (monitored) {
      stack = fn :: stack
      record(fn)
      try op
      finally stack = stack.tail
    } else op

  class Outer {
    def f = "Outer.f"
    class Inner {
      val msg = " Inner"
      rewrite def m = msg
      rewrite def g = f
      rewrite def h = f ++ m
    }
    val inner = new Inner
  }

  class C[T](private[transparents] val x: T) {
    private[transparents] def next[U](y: U): (T, U) = (xx, y)
    private[transparents] var xx: T =  _
  }

  class TestPassing {
    rewrite def foo[A](x: A): (A, Int) = {
      val c = new C[A](x)
      c.xx = c.x
      c.next(1)
    }
    rewrite def bar[A](x: A): (A, String) = {
      val c = new C[A](x)
      c.xx = c.x
      c.next("")
    }
  }
}
