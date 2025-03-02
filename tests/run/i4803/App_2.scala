
class Num2(x: Double) {
  rewrite def power(transparent n: Long) = ~PowerMacro.powerCode('(x), n)
}

object Test {
  def main(args: Array[String]): Unit = {
    val n = new Num(1.5)
    println(n.power(0))
    println(n.power(1))
    println(n.power(2))
    println(n.power(5))

    val n2 = new Num2(1.5)
    println(n.power(0))
    println(n.power(1))
    println(n.power(2))
    println(n.power(5))
  }
}
