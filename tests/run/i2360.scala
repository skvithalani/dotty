
object Test {
  def main(args: Array[String]): Unit = {
    import Foo._
    println(foo)
  }
}

object Foo extends Bar {
  rewrite def foo: Int = bar
}

class Bar {
  def bar = 42
}
