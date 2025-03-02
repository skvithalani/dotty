import scala.annotation.switch

class Test {
  import Test._

  def test1(x: Int): Int = (x: @switch) match {
    case 1 => 1
    case 2 | 3 | 4 => 2
    case 65 => 3
    case 72 => 4
  }

  def test2(c: Char): Boolean = (c: @switch) match {
    case LF | CR | FF | SU => true
    case _ => false
  }

  // #1313
  def test3(x: Int, y: Int): Int = (x: @switch) match {
    case 6 if y > 5 => 1
    case 6 => 2
    case 12 => 3
    case 14 => 4
    case _ => 5
  }
}

object Test {
  final val LF = '\u000A'
  final val CR = '\u000D'
  final val FF = '\u000C'
  final val SU = '\u001A'
}
