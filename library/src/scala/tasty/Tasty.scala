package scala.tasty

import scala.tasty.reflect._

abstract class Tasty
    extends TastyCore
    with CaseDefOps
    with ConstantOps
    with ContextOps
    with IdOps
    with ImportSelectorOps
    with QuotedOps
    with PatternOps
    with PositionOps
    with Printers
    with SignatureOps
    with StandardDefinitions
    with SymbolOps
    with TreeOps
    with TypeOrBoundsTreeOps
    with TypeOrBoundsOps

object Tasty {
  /** Compiler tasty context available in a top level ~ of a transparent macro */
  def macroContext: Tasty = throw new Exception("Not in transparent macro.")
}
