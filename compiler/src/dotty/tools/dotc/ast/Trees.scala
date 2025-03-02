package dotty.tools
package dotc
package ast

import core._
import Types._, Names._, NameOps._, Flags._, util.Positions._, Contexts._, Constants._
import SymDenotations._, Symbols._, Denotations._, StdNames._, Comments._
import annotation.tailrec
import language.higherKinds
import collection.IndexedSeqOptimized
import collection.immutable.IndexedSeq
import collection.mutable.ListBuffer
import parsing.Tokens.Token
import printing.Printer
import util.{Stats, Attachment, Property, DotClass}
import config.Config
import annotation.internal.sharable
import annotation.unchecked.uncheckedVariance
import language.implicitConversions

object Trees {

  // Note: it would be more logical to make Untyped = Nothing.
  // However, this interacts in a bad way with Scala's current type inference.
  // In fact, we cannot write something like Select(pre, name), where pre is
  // of type Tree[Nothing]; type inference will treat the Nothing as an uninstantiated
  // value and will not infer Nothing as the type parameter for Select.
  // We should come back to this issue once type inference is changed.
  type Untyped = Null

  /** The total number of created tree nodes, maintained if Stats.enabled */
  @sharable var ntrees = 0

  /** Property key for trees with documentation strings attached */
  val DocComment = new Property.StickyKey[Comment]

  @sharable private[this] var nextId = 0 // for debugging

  type LazyTree = AnyRef     /* really: Tree | Lazy[Tree] */
  type LazyTreeList = AnyRef /* really: List[Tree] | Lazy[List[Tree]] */

  /** Trees take a parameter indicating what the type of their `tpe` field
   *  is. Two choices: `Type` or `Untyped`.
   *  Untyped trees have type `Tree[Untyped]`.
   *
   *  Tree typing uses a copy-on-write implementation:
   *
   *   - You can never observe a `tpe` which is `null` (throws an exception)
   *   - So when creating a typed tree with `withType` we can re-use
   *     the existing tree transparently, assigning its `tpe` field,
   *     provided it was `null` before.
   *   - It is impossible to embed untyped trees in typed ones.
   *   - Typed trees can be embedded in untyped ones provided they are rooted
   *     in a TypedSplice node.
   *   - Type checking an untyped tree should remove all embedded `TypedSplice`
   *     nodes.
   */
  abstract class Tree[-T >: Untyped] extends Positioned
                                        with Product
                                        with Attachment.Container
                                        with printing.Showable
                                        with Cloneable {

    if (Stats.enabled) ntrees += 1

    private def nxId = {
      nextId += 1
      //assert(nextId != 199, this)
      nextId
    }

    /** A unique identifier for this tree. Used for debugging, and potentially
     *  tracking presentation compiler interactions.
     */
    @sharable private var myUniqueId: Int = nxId

    def uniqueId = myUniqueId

    /** The type  constructor at the root of the tree */
    type ThisTree[T >: Untyped] <: Tree[T]

    protected var myTpe: T @uncheckedVariance = _

    /** Destructively set the type of the tree. This should be called only when it is known that
     *  it is safe under sharing to do so. One use-case is in the withType method below
     *  which implements copy-on-write. Another use-case is in method interpolateAndAdapt in Typer,
     *  where we overwrite with a simplified version of the type itself.
     */
    private[dotc] def overwriteType(tpe: T) =
      myTpe = tpe

    /** The type of the tree. In case of an untyped tree,
     *   an UnAssignedTypeException is thrown. (Overridden by empty trees)
     */
    final def tpe: T @uncheckedVariance = {
      if (myTpe == null)
        throw new UnAssignedTypeException(this)
      myTpe
    }

    /** Copy `tpe` attribute from tree `from` into this tree, independently
     *  whether it is null or not.
    final def copyAttr[U >: Untyped](from: Tree[U]): ThisTree[T] = {
      val t1 = this.withPos(from.pos)
      val t2 =
        if (from.myTpe != null) t1.withType(from.myTpe.asInstanceOf[Type])
        else t1
      t2.asInstanceOf[ThisTree[T]]
    }
     */

    /** Return a typed tree that's isomorphic to this tree, but has given
     *  type. (Overridden by empty trees)
     */
    def withType(tpe: Type)(implicit ctx: Context): ThisTree[Type] = {
      if (tpe.isInstanceOf[ErrorType])
        assert(!Config.checkUnreportedErrors ||
               ctx.reporter.errorsReported ||
               ctx.settings.YshowPrintErrors.value
                 // under -Yshow-print-errors, errors might arise during printing, but they do not count as reported
              )
      else if (Config.checkTreesConsistent)
        checkChildrenTyped(productIterator)
      withTypeUnchecked(tpe)
    }

    /** Check that typed trees don't refer to untyped ones, except if
     *   - the parent tree is an import, or
     *   - the child tree is an identifier, or
     *   - errors were reported
     */
    private def checkChildrenTyped(it: Iterator[Any])(implicit ctx: Context): Unit =
      if (!this.isInstanceOf[Import[_]])
        while (it.hasNext)
          it.next() match {
            case x: Ident[_] => // untyped idents are used in a number of places in typed trees
            case x: Tree[_] =>
              assert(x.hasType || ctx.reporter.errorsReported,
                     s"$this has untyped child $x")
            case xs: List[_] => checkChildrenTyped(xs.iterator)
            case _ =>
          }

    def withTypeUnchecked(tpe: Type): ThisTree[Type] = {
      val tree =
        (if (myTpe == null ||
          (myTpe.asInstanceOf[AnyRef] eq tpe.asInstanceOf[AnyRef])) this
         else clone).asInstanceOf[Tree[Type]]
      tree overwriteType tpe
      tree.asInstanceOf[ThisTree[Type]]
    }

    /** Does the tree have its type field set? Note: this operation is not
     *  referentially transparent, because it can observe the withType
     *  modifications. Should be used only in special circumstances (we
     *  need it for printing trees with optional type info).
     */
    final def hasType: Boolean = myTpe != null

    final def typeOpt: Type = myTpe match {
      case tp: Type => tp
      case _ => NoType
    }

    /** The denotation referred to by this tree.
     *  Defined for `DenotingTree`s and `ProxyTree`s, NoDenotation for other
     *  kinds of trees
     */
    def denot(implicit ctx: Context): Denotation = NoDenotation

    /** Shorthand for `denot.symbol`. */
    final def symbol(implicit ctx: Context): Symbol = denot.symbol

    /** Does this tree represent a type? */
    def isType: Boolean = false

    /** Does this tree represent a term? */
    def isTerm: Boolean = false

    /** Is this a legal part of a pattern which is not at the same time a term? */
    def isPattern: Boolean = false

    /** Does this tree define a new symbol that is not defined elsewhere? */
    def isDef: Boolean = false

    /** Is this tree either the empty tree or the empty ValDef or an empty type ident? */
    def isEmpty: Boolean = false

    /** Convert tree to a list. Gives a singleton list, except
     *  for thickets which return their element trees.
     */
    def toList: List[Tree[T]] = this :: Nil

    /** if this tree is the empty tree, the alternative, else this tree */
    def orElse[U >: Untyped <: T](that: => Tree[U]): Tree[U] =
      if (this eq genericEmptyTree) that else this

    /** The number of nodes in this tree */
    def treeSize: Int = {
      var s = 1
      def addSize(elem: Any): Unit = elem match {
        case t: Tree[_] => s += t.treeSize
        case ts: List[_] => ts foreach addSize
        case _ =>
      }
      productIterator foreach addSize
      s
    }

    /** If this is a thicket, perform `op` on each of its trees
     *  otherwise, perform `op` ion tree itself.
     */
    def foreachInThicket(op: Tree[T] => Unit): Unit = op(this)

    override def toText(printer: Printer) = printer.toText(this)

    def sameTree(that: Tree[_]): Boolean = {
      def isSame(x: Any, y: Any): Boolean =
        x.asInstanceOf[AnyRef].eq(y.asInstanceOf[AnyRef]) || {
          x match {
            case x: Tree[_] =>
              y match {
                case y: Tree[_] => x.sameTree(y)
                case _ => false
              }
            case x: List[_] =>
              y match {
                case y: List[_] => x.corresponds(y)(isSame)
                case _ => false
              }
            case _ =>
              false
          }
        }
      this.getClass == that.getClass && {
        val it1 = this.productIterator
        val it2 = that.productIterator
        it1.corresponds(it2)(isSame)
      }
    }

    override def hashCode(): Int = uniqueId // for debugging; was: System.identityHashCode(this)
    override def equals(that: Any) = this eq that.asInstanceOf[AnyRef]

    override def clone: Tree[T] = {
      val tree = super.clone.asInstanceOf[Tree[T]]
      tree.myUniqueId = nxId
      tree
    }
  }

  class UnAssignedTypeException[T >: Untyped](tree: Tree[T]) extends RuntimeException {
    override def getMessage: String = s"type of $tree is not assigned"
  }

  // ------ Categories of trees -----------------------------------

  /** Instances of this class are trees for which isType is definitely true.
   *  Note that some trees have isType = true without being TypTrees (e.g. Ident, AnnotatedTree)
   */
  trait TypTree[-T >: Untyped] extends Tree[T] {
    type ThisTree[-T >: Untyped] <: TypTree[T]
    override def isType = true
  }

  /** Instances of this class are trees for which isTerm is definitely true.
   *  Note that some trees have isTerm = true without being TermTrees (e.g. Ident, AnnotatedTree)
   */
  trait TermTree[-T >: Untyped] extends Tree[T] {
    type ThisTree[-T >: Untyped] <: TermTree[T]
    override def isTerm = true
  }

  /** Instances of this class are trees which are not terms but are legal
   *  parts of patterns.
   */
  trait PatternTree[-T >: Untyped] extends Tree[T] {
    type ThisTree[-T >: Untyped] <: PatternTree[T]
    override def isPattern = true
  }

  /** Tree's denotation can be derived from its type */
  abstract class DenotingTree[-T >: Untyped] extends Tree[T] {
    type ThisTree[-T >: Untyped] <: DenotingTree[T]
    override def denot(implicit ctx: Context) = typeOpt match {
      case tpe: NamedType => tpe.denot
      case tpe: ThisType => tpe.cls.denot
      case tpe: AnnotatedType => tpe.stripAnnots match {
        case tpe: NamedType => tpe.denot
        case tpe: ThisType => tpe.cls.denot
        case _ => NoDenotation
      }
      case _ => NoDenotation
    }
  }

  /** Tree's denot/isType/isTerm properties come from a subtree
   *  identified by `forwardTo`.
   */
  abstract class ProxyTree[-T >: Untyped] extends Tree[T] {
    type ThisTree[-T >: Untyped] <: ProxyTree[T]
    def forwardTo: Tree[T]
    override def denot(implicit ctx: Context): Denotation = forwardTo.denot
    override def isTerm = forwardTo.isTerm
    override def isType = forwardTo.isType
  }

  /** Tree has a name */
  abstract class NameTree[-T >: Untyped] extends DenotingTree[T] {
    type ThisTree[-T >: Untyped] <: NameTree[T]
    def name: Name
  }

  /** Tree refers by name to a denotation */
  abstract class RefTree[-T >: Untyped] extends NameTree[T] {
    type ThisTree[-T >: Untyped] <: RefTree[T]
    def qualifier: Tree[T]
    override def isType = name.isTypeName
    override def isTerm = name.isTermName
  }

  /** Tree defines a new symbol */
  trait DefTree[-T >: Untyped] extends DenotingTree[T] {
    type ThisTree[-T >: Untyped] <: DefTree[T]
    override def isDef = true
    def namedType = tpe.asInstanceOf[NamedType]
  }

  /** Tree defines a new symbol and carries modifiers.
   *  The position of a MemberDef contains only the defined identifier or pattern.
   *  The envelope of a MemberDef contains the whole definition and has its point
   *  on the opening keyword (or the next token after that if keyword is missing).
   */
  abstract class MemberDef[-T >: Untyped] extends NameTree[T] with DefTree[T] {
    type ThisTree[-T >: Untyped] <: MemberDef[T]

    private[this] var myMods: untpd.Modifiers = null

    private[dotc] def rawMods: untpd.Modifiers =
      if (myMods == null) untpd.EmptyModifiers else myMods

    def rawComment: Option[Comment] = getAttachment(DocComment)

    def withMods(mods: untpd.Modifiers): ThisTree[Untyped] = {
      val tree = if (myMods == null || (myMods == mods)) this else clone.asInstanceOf[MemberDef[Untyped]]
      tree.setMods(mods)
      tree.asInstanceOf[ThisTree[Untyped]]
    }

    def withFlags(flags: FlagSet): ThisTree[Untyped] = withMods(untpd.Modifiers(flags))

    def setComment(comment: Option[Comment]): ThisTree[Untyped] = {
      comment.map(putAttachment(DocComment, _))
      asInstanceOf[ThisTree[Untyped]]
    }

    protected def setMods(mods: untpd.Modifiers) = myMods = mods

    /** The position of the name defined by this definition.
     *  This is a point position if the definition is synthetic, or a range position
     *  if the definition comes from source.
     *  It might also be that the definition does not have a position (for instance when synthesized by
     *  a calling chain from `viewExists`), in that case the return position is NoPosition.
     */
    def namePos =
      if (pos.exists) {
        val point = pos.point
        if (rawMods.is(Synthetic) || name.toTermName == nme.ERROR) Position(point)
        else Position(point, point + name.stripModuleClassSuffix.lastPart.length, point)
      }
      else pos
  }

  /** A ValDef or DefDef tree */
  trait ValOrDefDef[-T >: Untyped] extends MemberDef[T] with WithLazyField[Tree[T]] {
    def name: TermName
    def tpt: Tree[T]
    def unforcedRhs: LazyTree = unforced
    def rhs(implicit ctx: Context): Tree[T] = forceIfLazy
  }

  // ----------- Tree case classes ------------------------------------

  /** name */
  case class Ident[-T >: Untyped] private[ast] (name: Name)
    extends RefTree[T] {
    type ThisTree[-T >: Untyped] = Ident[T]
    def qualifier: Tree[T] = genericEmptyTree

    /** Is this a `BackquotedIdent` ? */
    def isBackquoted: Boolean = false
  }

  class BackquotedIdent[-T >: Untyped] private[ast] (name: Name)
    extends Ident[T](name) {
    override def isBackquoted: Boolean = true

    override def toString = s"BackquotedIdent($name)"
  }

  class SearchFailureIdent[-T >: Untyped] private[ast] (name: Name)
    extends Ident[T](name) {
    override def toString = s"SearchFailureIdent($name)"
  }

  /** qualifier.name, or qualifier#name, if qualifier is a type */
  case class Select[-T >: Untyped] private[ast] (qualifier: Tree[T], name: Name)
    extends RefTree[T] {
    type ThisTree[-T >: Untyped] = Select[T]
  }

  class SelectWithSig[-T >: Untyped] private[ast] (qualifier: Tree[T], name: Name, val sig: Signature)
    extends Select[T](qualifier, name) {
    override def toString = s"SelectWithSig($qualifier, $name, $sig)"
  }

  /** qual.this */
  case class This[-T >: Untyped] private[ast] (qual: untpd.Ident)
    extends DenotingTree[T] with TermTree[T] {
    type ThisTree[-T >: Untyped] = This[T]
    // Denotation of a This tree is always the underlying class; needs correction for modules.
    override def denot(implicit ctx: Context): Denotation = {
      typeOpt match {
        case tpe @ TermRef(pre, _) if tpe.symbol is Module =>
          tpe.symbol.moduleClass.denot.asSeenFrom(pre)
        case _ =>
          super.denot
      }
    }
  }

  /** C.super[mix], where qual = C.this */
  case class Super[-T >: Untyped] private[ast] (qual: Tree[T], mix: untpd.Ident)
    extends ProxyTree[T] with TermTree[T] {
    type ThisTree[-T >: Untyped] = Super[T]
    def forwardTo = qual
  }

  abstract class GenericApply[-T >: Untyped] extends ProxyTree[T] with TermTree[T] {
    type ThisTree[-T >: Untyped] <: GenericApply[T]
    val fun: Tree[T]
    val args: List[Tree[T]]
    def forwardTo = fun
  }

  /** fun(args) */
  case class Apply[-T >: Untyped] private[ast] (fun: Tree[T], args: List[Tree[T]])
    extends GenericApply[T] {
    type ThisTree[-T >: Untyped] = Apply[T]
  }

  /** fun[args] */
  case class TypeApply[-T >: Untyped] private[ast] (fun: Tree[T], args: List[Tree[T]])
    extends GenericApply[T] {
    type ThisTree[-T >: Untyped] = TypeApply[T]
  }

  /** const */
  case class Literal[-T >: Untyped] private[ast] (const: Constant)
    extends TermTree[T] {
    type ThisTree[-T >: Untyped] = Literal[T]
  }

  /** new tpt, but no constructor call */
  case class New[-T >: Untyped] private[ast] (tpt: Tree[T])
    extends TermTree[T] {
    type ThisTree[-T >: Untyped] = New[T]
  }

  /** expr : tpt */
  case class Typed[-T >: Untyped] private[ast] (expr: Tree[T], tpt: Tree[T])
    extends ProxyTree[T] with TermTree[T] {
    type ThisTree[-T >: Untyped] = Typed[T]
    def forwardTo = expr
  }

  /** name = arg, in a parameter list */
  case class NamedArg[-T >: Untyped] private[ast] (name: Name, arg: Tree[T])
    extends Tree[T] {
    type ThisTree[-T >: Untyped] = NamedArg[T]
  }

  /** name = arg, outside a parameter list */
  case class Assign[-T >: Untyped] private[ast] (lhs: Tree[T], rhs: Tree[T])
    extends TermTree[T] {
    type ThisTree[-T >: Untyped] = Assign[T]
  }

  /** { stats; expr } */
  case class Block[-T >: Untyped] private[ast] (stats: List[Tree[T]], expr: Tree[T])
    extends TermTree[T] {
    type ThisTree[-T >: Untyped] = Block[T]
  }

  /** if cond then thenp else elsep */
  case class If[-T >: Untyped] private[ast] (cond: Tree[T], thenp: Tree[T], elsep: Tree[T])
    extends TermTree[T] {
    type ThisTree[-T >: Untyped] = If[T]
  }
  class RewriteIf[T >: Untyped] private[ast] (cond: Tree[T], thenp: Tree[T], elsep: Tree[T])
    extends If(cond, thenp, elsep) {
    override def toString = s"RewriteIf($cond, $thenp, $elsep)"
  }

  /** A closure with an environment and a reference to a method.
   *  @param env    The captured parameters of the closure
   *  @param meth   A ref tree that refers to the method of the closure.
   *                The first (env.length) parameters of that method are filled
   *                with env values.
   *  @param tpt    Either EmptyTree or a TypeTree. If tpt is EmptyTree the type
   *                of the closure is a function type, otherwise it is the type
   *                given in `tpt`, which must be a SAM type.
   */
  case class Closure[-T >: Untyped] private[ast] (env: List[Tree[T]], meth: Tree[T], tpt: Tree[T])
    extends TermTree[T] {
    type ThisTree[-T >: Untyped] = Closure[T]
  }

  /** selector match { cases } */
  case class Match[-T >: Untyped] private[ast] (selector: Tree[T], cases: List[CaseDef[T]])
    extends TermTree[T] {
    type ThisTree[-T >: Untyped] = Match[T]
  }
  class RewriteMatch[T >: Untyped] private[ast] (selector: Tree[T], cases: List[CaseDef[T]])
    extends Match(selector, cases) {
    override def toString = s"RewriteMatch($selector, $cases)"
  }

  /** case pat if guard => body; only appears as child of a Match */
  case class CaseDef[-T >: Untyped] private[ast] (pat: Tree[T], guard: Tree[T], body: Tree[T])
    extends Tree[T] {
    type ThisTree[-T >: Untyped] = CaseDef[T]
  }

  /** label[tpt]: { expr } */
  case class Labeled[-T >: Untyped] private[ast] (bind: Bind[T], expr: Tree[T])
    extends NameTree[T] {
    type ThisTree[-T >: Untyped] = Labeled[T]
    def name: Name = bind.name
  }

  /** return expr
   *  where `from` refers to the method or label from which the return takes place
   *  After program transformations this is not necessarily the enclosing method, because
   *  closures can intervene.
   */
  case class Return[-T >: Untyped] private[ast] (expr: Tree[T], from: Tree[T] = genericEmptyTree)
    extends TermTree[T] {
    type ThisTree[-T >: Untyped] = Return[T]
  }

  /** try block catch handler finally finalizer
   *
   *  Note: if the handler is a case block CASES of the form
   *
   *    { case1 ... caseN }
   *
   *  the parser returns Match(EmptyTree, CASES). Desugaring and typing this yields a closure
   *  node
   *
   *    { def $anonfun(x: Throwable) = x match CASES; Closure(Nil, $anonfun) }
   *
   *  At some later stage when we normalize the try we can revert this to
   *
   *    Match(EmptyTree, CASES)
   *
   *  or else if stack is non-empty
   *
   *    Match(EmptyTree, <case x: Throwable => $anonfun(x)>)
   */
  case class Try[-T >: Untyped] private[ast] (expr: Tree[T], cases: List[CaseDef[T]], finalizer: Tree[T])
    extends TermTree[T] {
    type ThisTree[-T >: Untyped] = Try[T]
  }

  /** Seq(elems)
   *  @param  tpt  The element type of the sequence.
   */
  case class SeqLiteral[-T >: Untyped] private[ast] (elems: List[Tree[T]], elemtpt: Tree[T])
    extends Tree[T] {
    type ThisTree[-T >: Untyped] = SeqLiteral[T]
  }

  /** Array(elems) */
  class JavaSeqLiteral[T >: Untyped] private[ast] (elems: List[Tree[T]], elemtpt: Tree[T])
    extends SeqLiteral(elems, elemtpt) {
    override def toString = s"JavaSeqLiteral($elems, $elemtpt)"
  }

  /** A tree representing inlined code.
   *
   *  @param  call      Info about the original call that was inlined
   *                    Until PostTyper, this is the full call, afterwards only
   *                    a reference to the toplevel class from which the call was inlined.
   *  @param  bindings  Bindings for proxies to be used in the inlined code
   *  @param  expansion The inlined tree, minus bindings.
   *
   *  The full inlined code is equivalent to
   *
   *      { bindings; expansion }
   *
   *  The reason to keep `bindings` separate is because they are typed in a
   *  different context: `bindings` represent the arguments to the inlined
   *  call, whereas `expansion` represents the body of the inlined function.
   */
  case class Inlined[-T >: Untyped] private[ast] (call: tpd.Tree, bindings: List[MemberDef[T]], expansion: Tree[T])
    extends Tree[T] {
    type ThisTree[-T >: Untyped] = Inlined[T]
    override def initialPos = call.pos
  }

  /** A type tree that represents an existing or inferred type */
  case class TypeTree[-T >: Untyped] ()
    extends DenotingTree[T] with TypTree[T] {
    type ThisTree[-T >: Untyped] = TypeTree[T]
    override def isEmpty = !hasType
    override def toString =
      s"TypeTree${if (hasType) s"[$typeOpt]" else ""}"
  }

  /** A type tree that defines a new type variable. Its type is always a TypeVar.
   *  Every TypeVar is created as the type of one TypeVarBinder.
   */
  class TypeVarBinder[-T >: Untyped] extends TypeTree[T]

  /** ref.type */
  case class SingletonTypeTree[-T >: Untyped] private[ast] (ref: Tree[T])
    extends DenotingTree[T] with TypTree[T] {
    type ThisTree[-T >: Untyped] = SingletonTypeTree[T]
  }

  /** left & right */
  case class AndTypeTree[-T >: Untyped] private[ast] (left: Tree[T], right: Tree[T])
    extends TypTree[T] {
    type ThisTree[-T >: Untyped] = AndTypeTree[T]
  }

  /** left | right */
  case class OrTypeTree[-T >: Untyped] private[ast] (left: Tree[T], right: Tree[T])
    extends TypTree[T] {
    type ThisTree[-T >: Untyped] = OrTypeTree[T]
  }

  /** tpt { refinements } */
  case class RefinedTypeTree[-T >: Untyped] private[ast] (tpt: Tree[T], refinements: List[Tree[T]])
    extends ProxyTree[T] with TypTree[T] {
    type ThisTree[-T >: Untyped] = RefinedTypeTree[T]
    def forwardTo = tpt
  }

  /** tpt[args] */
  case class AppliedTypeTree[-T >: Untyped] private[ast] (tpt: Tree[T], args: List[Tree[T]])
    extends ProxyTree[T] with TypTree[T] {
    type ThisTree[-T >: Untyped] = AppliedTypeTree[T]
    def forwardTo = tpt
  }

  /** [typeparams] -> tpt */
  case class LambdaTypeTree[-T >: Untyped] private[ast] (tparams: List[TypeDef[T]], body: Tree[T])
    extends TypTree[T] {
    type ThisTree[-T >: Untyped] = LambdaTypeTree[T]
  }

  /** => T */
  case class ByNameTypeTree[-T >: Untyped] private[ast] (result: Tree[T])
  extends TypTree[T] {
    type ThisTree[-T >: Untyped] = ByNameTypeTree[T]
  }

  /** >: lo <: hi */
  case class TypeBoundsTree[-T >: Untyped] private[ast] (lo: Tree[T], hi: Tree[T])
    extends TypTree[T] {
    type ThisTree[-T >: Untyped] = TypeBoundsTree[T]
  }

  /** name @ body */
  case class Bind[-T >: Untyped] private[ast] (name: Name, body: Tree[T])
    extends NameTree[T] with DefTree[T] with PatternTree[T] {
    type ThisTree[-T >: Untyped] = Bind[T]
    override def isType = name.isTypeName
    override def isTerm = name.isTermName
  }

  /** tree_1 | ... | tree_n */
  case class Alternative[-T >: Untyped] private[ast] (trees: List[Tree[T]])
    extends PatternTree[T] {
    type ThisTree[-T >: Untyped] = Alternative[T]
  }

  /** The typed translation of `extractor(patterns)` in a pattern. The translation has the following
   *  components:
   *
   *  @param fun       is `extractor.unapply` (or, for backwards compatibility, `extractor.unapplySeq`)
   *                   possibly with type parameters
   *  @param implicits Any implicit parameters passed to the unapply after the selector
   *  @param patterns  The argument patterns in the pattern match.
   *
   *  It is typed with same type as first `fun` argument
   *  Given a match selector `sel` a pattern UnApply(fun, implicits, patterns) is roughly translated as follows
   *
   *    val result = fun(sel)(implicits)
   *    if (result.isDefined) "match patterns against result"
   */
  case class UnApply[-T >: Untyped] private[ast] (fun: Tree[T], implicits: List[Tree[T]], patterns: List[Tree[T]])
    extends PatternTree[T] {
    type ThisTree[-T >: Untyped] = UnApply[T]
  }

  /** mods val name: tpt = rhs */
  case class ValDef[-T >: Untyped] private[ast] (name: TermName, tpt: Tree[T], private var preRhs: LazyTree)
    extends ValOrDefDef[T] {
    type ThisTree[-T >: Untyped] = ValDef[T]
    assert(isEmpty || tpt != genericEmptyTree)
    def unforced = preRhs
    protected def force(x: AnyRef) = preRhs = x
  }

  /** mods def name[tparams](vparams_1)...(vparams_n): tpt = rhs */
  case class DefDef[-T >: Untyped] private[ast] (name: TermName, tparams: List[TypeDef[T]],
      vparamss: List[List[ValDef[T]]], tpt: Tree[T], private var preRhs: LazyTree)
    extends ValOrDefDef[T] {
    type ThisTree[-T >: Untyped] = DefDef[T]
    assert(tpt != genericEmptyTree)
    def unforced = preRhs
    protected def force(x: AnyRef) = preRhs = x
  }

  /** mods class name template     or
   *  mods trait name template     or
   *  mods type name = rhs   or
   *  mods type name >: lo <: hi, if rhs = TypeBoundsTree(lo, hi) & (lo ne hi)
   */
  case class TypeDef[-T >: Untyped] private[ast] (name: TypeName, rhs: Tree[T])
    extends MemberDef[T] {
    type ThisTree[-T >: Untyped] = TypeDef[T]

    /** Is this a definition of a class? */
    def isClassDef = rhs.isInstanceOf[Template[_]]
  }

  /** extends parents { self => body } */
  case class Template[-T >: Untyped] private[ast] (constr: DefDef[T], parents: List[Tree[T]], self: ValDef[T], private var preBody: LazyTreeList)
    extends DefTree[T] with WithLazyField[List[Tree[T]]] {
    type ThisTree[-T >: Untyped] = Template[T]
    def unforcedBody = unforced
    def unforced = preBody
    protected def force(x: AnyRef) = preBody = x
    def body(implicit ctx: Context): List[Tree[T]] = forceIfLazy
  }

  /** import expr.selectors
   *  where a selector is either an untyped `Ident`, `name` or
   *  an untyped thicket consisting of `name` and `rename`.
   */
  case class Import[-T >: Untyped] private[ast] (expr: Tree[T], selectors: List[Tree[Untyped]])
    extends DenotingTree[T] {
    type ThisTree[-T >: Untyped] = Import[T]
  }

  /** package pid { stats } */
  case class PackageDef[-T >: Untyped] private[ast] (pid: RefTree[T], stats: List[Tree[T]])
    extends ProxyTree[T] {
    type ThisTree[-T >: Untyped] = PackageDef[T]
    def forwardTo = pid
  }

  /** arg @annot */
  case class Annotated[-T >: Untyped] private[ast] (arg: Tree[T], annot: Tree[T])
    extends ProxyTree[T] {
    type ThisTree[-T >: Untyped] = Annotated[T]
    def forwardTo = arg
  }

  trait WithoutTypeOrPos[-T >: Untyped] extends Tree[T] {
    override def withTypeUnchecked(tpe: Type) = this.asInstanceOf[ThisTree[Type]]
    override def pos = NoPosition
    override def setPos(pos: Position) = {}
  }

  /** Temporary class that results from translation of ModuleDefs
   *  (and possibly other statements).
   *  The contained trees will be integrated when transformed with
   *  a `transform(List[Tree])` call.
   */
  case class Thicket[-T >: Untyped](trees: List[Tree[T]])
    extends Tree[T] with WithoutTypeOrPos[T] {
    myTpe = NoType.asInstanceOf[T]

    type ThisTree[-T >: Untyped] = Thicket[T]
    override def isEmpty: Boolean = trees.isEmpty
    override def toList: List[Tree[T]] = flatten(trees)
    override def toString = if (isEmpty) "EmptyTree" else "Thicket(" + trees.mkString(", ") + ")"
    override def withPos(pos: Position): this.type = {
      val newTrees = trees.mapConserve(_.withPos(pos))
      if (trees eq newTrees)
        this
      else
        new Thicket[T](newTrees).asInstanceOf[this.type]
    }
    override def pos = (NoPosition /: trees) ((pos, t) => pos union t.pos)
    override def foreachInThicket(op: Tree[T] => Unit): Unit =
      trees foreach (_.foreachInThicket(op))
  }

  class EmptyValDef[T >: Untyped] extends ValDef[T](
    nme.WILDCARD, genericEmptyTree[T], genericEmptyTree[T]) with WithoutTypeOrPos[T] {
    myTpe = NoType.asInstanceOf[T]
    override def isEmpty: Boolean = true
    setMods(untpd.Modifiers(PrivateLocal))
  }

  @sharable val theEmptyTree: Thicket[Type] = Thicket(Nil)
  @sharable val theEmptyValDef = new EmptyValDef[Type]

  def genericEmptyValDef[T >: Untyped]: ValDef[T]       = theEmptyValDef.asInstanceOf[ValDef[T]]
  def genericEmptyTree[T >: Untyped]: Thicket[T]        = theEmptyTree.asInstanceOf[Thicket[T]]

  def flatten[T >: Untyped](trees: List[Tree[T]]): List[Tree[T]] = {
    def recur(buf: ListBuffer[Tree[T]], remaining: List[Tree[T]]): ListBuffer[Tree[T]] =
      remaining match {
        case Thicket(elems) :: remaining1 =>
          var buf1 = buf
          if (buf1 == null) {
            buf1 = new ListBuffer[Tree[T]]
            var scanned = trees
            while (scanned `ne` remaining) {
              buf1 += scanned.head
              scanned = scanned.tail
            }
          }
          recur(recur(buf1, elems), remaining1)
        case tree :: remaining1 =>
          if (buf != null) buf += tree
          recur(buf, remaining1)
        case nil =>
          buf
      }
    val buf = recur(null, trees)
    if (buf != null) buf.toList else trees
  }

  // ----- Lazy trees and tree sequences

  /** A tree that can have a lazy field
   *  The field is represented by some private `var` which is
   *  proxied `unforced` and `force`. Forcing the field will
   *  set the `var` to the underlying value.
   */
  trait WithLazyField[+T <: AnyRef] {
    def unforced: AnyRef
    protected def force(x: AnyRef): Unit
    def forceIfLazy(implicit ctx: Context): T = unforced match {
      case lzy: Lazy[T @unchecked] =>
        val x = lzy.complete
        force(x)
        x
      case x: T @ unchecked => x
    }
  }

  /** A base trait for lazy tree fields.
   *  These can be instantiated with Lazy instances which
   *  can delay tree construction until the field is first demanded.
   */
  trait Lazy[T <: AnyRef] {
    def complete(implicit ctx: Context): T
  }

  // ----- Generic Tree Instances, inherited from `tpt` and `untpd`.

  abstract class Instance[T >: Untyped <: Type] { inst =>

    type Tree = Trees.Tree[T]
    type TypTree = Trees.TypTree[T]
    type TermTree = Trees.TermTree[T]
    type PatternTree = Trees.PatternTree[T]
    type DenotingTree = Trees.DenotingTree[T]
    type ProxyTree = Trees.ProxyTree[T]
    type NameTree = Trees.NameTree[T]
    type RefTree = Trees.RefTree[T]
    type DefTree = Trees.DefTree[T]
    type MemberDef = Trees.MemberDef[T]
    type ValOrDefDef = Trees.ValOrDefDef[T]

    type Ident = Trees.Ident[T]
    type BackquotedIdent = Trees.BackquotedIdent[T]
    type SearchFailureIdent = Trees.SearchFailureIdent[T]
    type Select = Trees.Select[T]
    type SelectWithSig = Trees.SelectWithSig[T]
    type This = Trees.This[T]
    type Super = Trees.Super[T]
    type Apply = Trees.Apply[T]
    type TypeApply = Trees.TypeApply[T]
    type Literal = Trees.Literal[T]
    type New = Trees.New[T]
    type Typed = Trees.Typed[T]
    type NamedArg = Trees.NamedArg[T]
    type Assign = Trees.Assign[T]
    type Block = Trees.Block[T]
    type If = Trees.If[T]
    type RewriteIf = Trees.RewriteIf[T]
    type Closure = Trees.Closure[T]
    type Match = Trees.Match[T]
    type RewriteMatch = Trees.RewriteMatch[T]
    type CaseDef = Trees.CaseDef[T]
    type Labeled = Trees.Labeled[T]
    type Return = Trees.Return[T]
    type Try = Trees.Try[T]
    type SeqLiteral = Trees.SeqLiteral[T]
    type JavaSeqLiteral = Trees.JavaSeqLiteral[T]
    type Inlined = Trees.Inlined[T]
    type TypeTree = Trees.TypeTree[T]
    type SingletonTypeTree = Trees.SingletonTypeTree[T]
    type AndTypeTree = Trees.AndTypeTree[T]
    type OrTypeTree = Trees.OrTypeTree[T]
    type RefinedTypeTree = Trees.RefinedTypeTree[T]
    type AppliedTypeTree = Trees.AppliedTypeTree[T]
    type LambdaTypeTree = Trees.LambdaTypeTree[T]
    type ByNameTypeTree = Trees.ByNameTypeTree[T]
    type TypeBoundsTree = Trees.TypeBoundsTree[T]
    type Bind = Trees.Bind[T]
    type Alternative = Trees.Alternative[T]
    type UnApply = Trees.UnApply[T]
    type ValDef = Trees.ValDef[T]
    type DefDef = Trees.DefDef[T]
    type TypeDef = Trees.TypeDef[T]
    type Template = Trees.Template[T]
    type Import = Trees.Import[T]
    type PackageDef = Trees.PackageDef[T]
    type Annotated = Trees.Annotated[T]
    type Thicket = Trees.Thicket[T]

    @sharable val EmptyTree: Thicket = genericEmptyTree
    @sharable val EmptyValDef: ValDef = genericEmptyValDef
    @sharable val ImplicitEmptyTree: Thicket = Thicket(Nil) // an empty tree marking an implicit closure

    // ----- Auxiliary creation methods ------------------

    def Thicket(trees: List[Tree]): Thicket = new Thicket(trees)
    def Thicket(): Thicket = EmptyTree
    def Thicket(x1: Tree, x2: Tree): Thicket = Thicket(x1 :: x2 :: Nil)
    def Thicket(x1: Tree, x2: Tree, x3: Tree): Thicket = Thicket(x1 :: x2 :: x3 :: Nil)
    def flatTree(xs: List[Tree]): Tree = flatten(xs) match {
      case x :: Nil => x
      case ys => Thicket(ys)
    }

    /** Extractor for the synthetic scrutinee tree of an implicit match */
    object ImplicitScrutinee {
      def apply() = Ident(nme.IMPLICITkw)
      def unapply(id: Ident): Boolean = id.name == nme.IMPLICITkw && !id.isInstanceOf[BackquotedIdent]
    }
    // ----- Helper classes for copying, transforming, accumulating -----------------

    val cpy: TreeCopier

    /** A class for copying trees. The copy methods avoid creating a new tree
     *  If all arguments stay the same.
     *
     * Note: Some of the copy methods take a context.
     * These are exactly those methods that are overridden in TypedTreeCopier
     * so that they selectively retype themselves. Retyping needs a context.
     */
    abstract class TreeCopier {
      def postProcess(tree: Tree, copied: untpd.Tree): copied.ThisTree[T]
      def postProcess(tree: Tree, copied: untpd.MemberDef): copied.ThisTree[T]

      def finalize(tree: Tree, copied: untpd.Tree): copied.ThisTree[T] =
        postProcess(tree, copied.withPos(tree.pos).withAttachmentsFrom(tree))

      def finalize(tree: Tree, copied: untpd.MemberDef): copied.ThisTree[T] =
        postProcess(tree, copied.withPos(tree.pos).withAttachmentsFrom(tree))

      def Ident(tree: Tree)(name: Name): Ident = tree match {
        case tree: BackquotedIdent =>
          if (name == tree.name) tree
          else finalize(tree, new BackquotedIdent(name))
        case tree: Ident if name == tree.name => tree
        case _ => finalize(tree, untpd.Ident(name))
      }
      def Select(tree: Tree)(qualifier: Tree, name: Name)(implicit ctx: Context): Select = tree match {
        case tree: SelectWithSig =>
          if ((qualifier eq tree.qualifier) && (name == tree.name)) tree
          else finalize(tree, new SelectWithSig(qualifier, name, tree.sig))
        case tree: Select if (qualifier eq tree.qualifier) && (name == tree.name) => tree
        case _ => finalize(tree, untpd.Select(qualifier, name))
      }
      /** Copy Ident or Select trees */
      def Ref(tree: RefTree)(name: Name)(implicit ctx: Context) = tree match {
        case Ident(_) => Ident(tree)(name)
        case Select(qual, _) => Select(tree)(qual, name)
      }
      def This(tree: Tree)(qual: untpd.Ident): This = tree match {
        case tree: This if qual eq tree.qual => tree
        case _ => finalize(tree, untpd.This(qual))
      }
      def Super(tree: Tree)(qual: Tree, mix: untpd.Ident): Super = tree match {
        case tree: Super if (qual eq tree.qual) && (mix eq tree.mix) => tree
        case _ => finalize(tree, untpd.Super(qual, mix))
      }
      def Apply(tree: Tree)(fun: Tree, args: List[Tree])(implicit ctx: Context): Apply = tree match {
        case tree: Apply if (fun eq tree.fun) && (args eq tree.args) => tree
        case _ => finalize(tree, untpd.Apply(fun, args))
      }
      def TypeApply(tree: Tree)(fun: Tree, args: List[Tree])(implicit ctx: Context): TypeApply = tree match {
        case tree: TypeApply if (fun eq tree.fun) && (args eq tree.args) => tree
        case _ => finalize(tree, untpd.TypeApply(fun, args))
      }
      def Literal(tree: Tree)(const: Constant)(implicit ctx: Context): Literal = tree match {
        case tree: Literal if const == tree.const => tree
        case _ => finalize(tree, untpd.Literal(const))
      }
      def New(tree: Tree)(tpt: Tree)(implicit ctx: Context): New = tree match {
        case tree: New if tpt eq tree.tpt => tree
        case _ => finalize(tree, untpd.New(tpt))
      }
      def Typed(tree: Tree)(expr: Tree, tpt: Tree)(implicit ctx: Context): Typed = tree match {
        case tree: Typed if (expr eq tree.expr) && (tpt eq tree.tpt) => tree
        case _ => finalize(tree, untpd.Typed(expr, tpt))
      }
      def NamedArg(tree: Tree)(name: Name, arg: Tree)(implicit ctx: Context): NamedArg = tree match {
        case tree: NamedArg if (name == tree.name) && (arg eq tree.arg) => tree
        case _ => finalize(tree, untpd.NamedArg(name, arg))
      }
      def Assign(tree: Tree)(lhs: Tree, rhs: Tree)(implicit ctx: Context): Assign = tree match {
        case tree: Assign if (lhs eq tree.lhs) && (rhs eq tree.rhs) => tree
        case _ => finalize(tree, untpd.Assign(lhs, rhs))
      }
      def Block(tree: Tree)(stats: List[Tree], expr: Tree)(implicit ctx: Context): Block = tree match {
        case tree: Block if (stats eq tree.stats) && (expr eq tree.expr) => tree
        case _ => finalize(tree, untpd.Block(stats, expr))
      }
      def If(tree: Tree)(cond: Tree, thenp: Tree, elsep: Tree)(implicit ctx: Context): If = tree match {
        case tree: RewriteIf =>
          if ((cond eq tree.cond) && (thenp eq tree.thenp) && (elsep eq tree.elsep)) tree
          else finalize(tree, untpd.RewriteIf(cond, thenp, elsep))
        case tree: If if (cond eq tree.cond) && (thenp eq tree.thenp) && (elsep eq tree.elsep) => tree
        case _ => finalize(tree, untpd.If(cond, thenp, elsep))
      }
      def Closure(tree: Tree)(env: List[Tree], meth: Tree, tpt: Tree)(implicit ctx: Context): Closure = tree match {
        case tree: Closure if (env eq tree.env) && (meth eq tree.meth) && (tpt eq tree.tpt) => tree
        case _ => finalize(tree, untpd.Closure(env, meth, tpt))
      }
      def Match(tree: Tree)(selector: Tree, cases: List[CaseDef])(implicit ctx: Context): Match = tree match {
        case tree: RewriteMatch =>
          if ((selector eq tree.selector) && (cases eq tree.cases)) tree
          else finalize(tree, untpd.RewriteMatch(selector, cases))
        case tree: Match if (selector eq tree.selector) && (cases eq tree.cases) => tree
        case _ => finalize(tree, untpd.Match(selector, cases))
      }
      def CaseDef(tree: Tree)(pat: Tree, guard: Tree, body: Tree)(implicit ctx: Context): CaseDef = tree match {
        case tree: CaseDef if (pat eq tree.pat) && (guard eq tree.guard) && (body eq tree.body) => tree
        case _ => finalize(tree, untpd.CaseDef(pat, guard, body))
      }
      def Labeled(tree: Tree)(bind: Bind, expr: Tree)(implicit ctx: Context): Labeled = tree match {
        case tree: Labeled if (bind eq tree.bind) && (expr eq tree.expr) => tree
        case _ => finalize(tree, untpd.Labeled(bind, expr))
      }
      def Return(tree: Tree)(expr: Tree, from: Tree)(implicit ctx: Context): Return = tree match {
        case tree: Return if (expr eq tree.expr) && (from eq tree.from) => tree
        case _ => finalize(tree, untpd.Return(expr, from))
      }
      def Try(tree: Tree)(expr: Tree, cases: List[CaseDef], finalizer: Tree)(implicit ctx: Context): Try = tree match {
        case tree: Try if (expr eq tree.expr) && (cases eq tree.cases) && (finalizer eq tree.finalizer) => tree
        case _ => finalize(tree, untpd.Try(expr, cases, finalizer))
      }
      def SeqLiteral(tree: Tree)(elems: List[Tree], elemtpt: Tree)(implicit ctx: Context): SeqLiteral = tree match {
        case tree: JavaSeqLiteral =>
          if ((elems eq tree.elems) && (elemtpt eq tree.elemtpt)) tree
          else finalize(tree, new JavaSeqLiteral(elems, elemtpt))
        case tree: SeqLiteral if (elems eq tree.elems) && (elemtpt eq tree.elemtpt) => tree
        case _ => finalize(tree, untpd.SeqLiteral(elems, elemtpt))
      }
      def Inlined(tree: Tree)(call: tpd.Tree, bindings: List[MemberDef], expansion: Tree)(implicit ctx: Context): Inlined = tree match {
        case tree: Inlined if (call eq tree.call) && (bindings eq tree.bindings) && (expansion eq tree.expansion) => tree
        case _ => finalize(tree, untpd.Inlined(call, bindings, expansion))
      }
      def SingletonTypeTree(tree: Tree)(ref: Tree): SingletonTypeTree = tree match {
        case tree: SingletonTypeTree if ref eq tree.ref => tree
        case _ => finalize(tree, untpd.SingletonTypeTree(ref))
      }
      def AndTypeTree(tree: Tree)(left: Tree, right: Tree): AndTypeTree = tree match {
        case tree: AndTypeTree if (left eq tree.left) && (right eq tree.right) => tree
        case _ => finalize(tree, untpd.AndTypeTree(left, right))
      }
      def OrTypeTree(tree: Tree)(left: Tree, right: Tree): OrTypeTree = tree match {
        case tree: OrTypeTree if (left eq tree.left) && (right eq tree.right) => tree
        case _ => finalize(tree, untpd.OrTypeTree(left, right))
      }
      def RefinedTypeTree(tree: Tree)(tpt: Tree, refinements: List[Tree]): RefinedTypeTree = tree match {
        case tree: RefinedTypeTree if (tpt eq tree.tpt) && (refinements eq tree.refinements) => tree
        case _ => finalize(tree, untpd.RefinedTypeTree(tpt, refinements))
      }
      def AppliedTypeTree(tree: Tree)(tpt: Tree, args: List[Tree]): AppliedTypeTree = tree match {
        case tree: AppliedTypeTree if (tpt eq tree.tpt) && (args eq tree.args) => tree
        case _ => finalize(tree, untpd.AppliedTypeTree(tpt, args))
      }
      def LambdaTypeTree(tree: Tree)(tparams: List[TypeDef], body: Tree): LambdaTypeTree = tree match {
        case tree: LambdaTypeTree if (tparams eq tree.tparams) && (body eq tree.body) => tree
        case _ => finalize(tree, untpd.LambdaTypeTree(tparams, body))
      }
      def ByNameTypeTree(tree: Tree)(result: Tree): ByNameTypeTree = tree match {
        case tree: ByNameTypeTree if result eq tree.result => tree
        case _ => finalize(tree, untpd.ByNameTypeTree(result))
      }
      def TypeBoundsTree(tree: Tree)(lo: Tree, hi: Tree): TypeBoundsTree = tree match {
        case tree: TypeBoundsTree if (lo eq tree.lo) && (hi eq tree.hi) => tree
        case _ => finalize(tree, untpd.TypeBoundsTree(lo, hi))
      }
      def Bind(tree: Tree)(name: Name, body: Tree): Bind = tree match {
        case tree: Bind if (name eq tree.name) && (body eq tree.body) => tree
        case _ => finalize(tree, untpd.Bind(name, body))
      }
      def Alternative(tree: Tree)(trees: List[Tree]): Alternative = tree match {
        case tree: Alternative if trees eq tree.trees => tree
        case _ => finalize(tree, untpd.Alternative(trees))
      }
      def UnApply(tree: Tree)(fun: Tree, implicits: List[Tree], patterns: List[Tree]): UnApply = tree match {
        case tree: UnApply if (fun eq tree.fun) && (implicits eq tree.implicits) && (patterns eq tree.patterns) => tree
        case _ => finalize(tree, untpd.UnApply(fun, implicits, patterns))
      }
      def ValDef(tree: Tree)(name: TermName, tpt: Tree, rhs: LazyTree): ValDef = tree match {
        case tree: ValDef if (name == tree.name) && (tpt eq tree.tpt) && (rhs eq tree.unforcedRhs) => tree
        case _ => finalize(tree, untpd.ValDef(name, tpt, rhs))
      }
      def DefDef(tree: Tree)(name: TermName, tparams: List[TypeDef], vparamss: List[List[ValDef]], tpt: Tree, rhs: LazyTree): DefDef = tree match {
        case tree: DefDef if (name == tree.name) && (tparams eq tree.tparams) && (vparamss eq tree.vparamss) && (tpt eq tree.tpt) && (rhs eq tree.unforcedRhs) => tree
        case _ => finalize(tree, untpd.DefDef(name, tparams, vparamss, tpt, rhs))
      }
      def TypeDef(tree: Tree)(name: TypeName, rhs: Tree): TypeDef = tree match {
        case tree: TypeDef if (name == tree.name) && (rhs eq tree.rhs) => tree
        case _ => finalize(tree, untpd.TypeDef(name, rhs))
      }
      def Template(tree: Tree)(constr: DefDef, parents: List[Tree], self: ValDef, body: LazyTreeList): Template = tree match {
        case tree: Template if (constr eq tree.constr) && (parents eq tree.parents) && (self eq tree.self) && (body eq tree.unforcedBody) => tree
        case _ => finalize(tree, untpd.Template(constr, parents, self, body))
      }
      def Import(tree: Tree)(expr: Tree, selectors: List[untpd.Tree]): Import = tree match {
        case tree: Import if (expr eq tree.expr) && (selectors eq tree.selectors) => tree
        case _ => finalize(tree, untpd.Import(expr, selectors))
      }
      def PackageDef(tree: Tree)(pid: RefTree, stats: List[Tree]): PackageDef = tree match {
        case tree: PackageDef if (pid eq tree.pid) && (stats eq tree.stats) => tree
        case _ => finalize(tree, untpd.PackageDef(pid, stats))
      }
      def Annotated(tree: Tree)(arg: Tree, annot: Tree)(implicit ctx: Context): Annotated = tree match {
        case tree: Annotated if (arg eq tree.arg) && (annot eq tree.annot) => tree
        case _ => finalize(tree, untpd.Annotated(arg, annot))
      }
      def UntypedSplice(tree: Tree)(splice: untpd.Tree) = tree match {
        case tree: tpd.UntypedSplice if tree.splice `eq` splice => tree
        case _ => finalize(tree, tpd.UntypedSplice(splice))
      }
      def Thicket(tree: Tree)(trees: List[Tree]): Thicket = tree match {
        case tree: Thicket if trees eq tree.trees => tree
        case _ => finalize(tree, untpd.Thicket(trees))
      }

      // Copier methods with default arguments; these demand that the original tree
      // is of the same class as the copy. We only include trees with more than 2 elements here.
      def If(tree: If)(cond: Tree = tree.cond, thenp: Tree = tree.thenp, elsep: Tree = tree.elsep)(implicit ctx: Context): If =
        If(tree: Tree)(cond, thenp, elsep)
      def Closure(tree: Closure)(env: List[Tree] = tree.env, meth: Tree = tree.meth, tpt: Tree = tree.tpt)(implicit ctx: Context): Closure =
        Closure(tree: Tree)(env, meth, tpt)
      def CaseDef(tree: CaseDef)(pat: Tree = tree.pat, guard: Tree = tree.guard, body: Tree = tree.body)(implicit ctx: Context): CaseDef =
        CaseDef(tree: Tree)(pat, guard, body)
      def Try(tree: Try)(expr: Tree = tree.expr, cases: List[CaseDef] = tree.cases, finalizer: Tree = tree.finalizer)(implicit ctx: Context): Try =
        Try(tree: Tree)(expr, cases, finalizer)
      def UnApply(tree: UnApply)(fun: Tree = tree.fun, implicits: List[Tree] = tree.implicits, patterns: List[Tree] = tree.patterns): UnApply =
        UnApply(tree: Tree)(fun, implicits, patterns)
      def ValDef(tree: ValDef)(name: TermName = tree.name, tpt: Tree = tree.tpt, rhs: LazyTree = tree.unforcedRhs): ValDef =
        ValDef(tree: Tree)(name, tpt, rhs)
      def DefDef(tree: DefDef)(name: TermName = tree.name, tparams: List[TypeDef] = tree.tparams, vparamss: List[List[ValDef]] = tree.vparamss, tpt: Tree = tree.tpt, rhs: LazyTree = tree.unforcedRhs): DefDef =
        DefDef(tree: Tree)(name, tparams, vparamss, tpt, rhs)
      def TypeDef(tree: TypeDef)(name: TypeName = tree.name, rhs: Tree = tree.rhs): TypeDef =
        TypeDef(tree: Tree)(name, rhs)
      def Template(tree: Template)(constr: DefDef = tree.constr, parents: List[Tree] = tree.parents, self: ValDef = tree.self, body: LazyTreeList = tree.unforcedBody): Template =
        Template(tree: Tree)(constr, parents, self, body)
    }

    /** Hook to indicate that a transform of some subtree should be skipped */
    protected def skipTransform(tree: Tree)(implicit ctx: Context): Boolean = false

    /** For untyped trees, this is just the identity.
     *  For typed trees, a context derived form `ctx` that records `call` as the
     *  innermost enclosing call for which the inlined version is currently
     *  processed.
     */
    protected def inlineContext(call: Tree)(implicit ctx: Context): Context = ctx

    abstract class TreeMap(val cpy: TreeCopier = inst.cpy) { self =>

      def transform(tree: Tree)(implicit ctx: Context): Tree = {
        Stats.record(s"TreeMap.transform $getClass")
        Stats.record("TreeMap.transform total")
        def localCtx =
          if (tree.hasType && tree.symbol.exists) ctx.withOwner(tree.symbol) else ctx

        if (skipTransform(tree)) tree
        else tree match {
          case Ident(name) =>
            tree
          case Select(qualifier, name) =>
            cpy.Select(tree)(transform(qualifier), name)
          case This(qual) =>
            tree
          case Super(qual, mix) =>
            cpy.Super(tree)(transform(qual), mix)
          case Apply(fun, args) =>
            cpy.Apply(tree)(transform(fun), transform(args))
          case TypeApply(fun, args) =>
            cpy.TypeApply(tree)(transform(fun), transform(args))
          case Literal(const) =>
            tree
          case New(tpt) =>
            cpy.New(tree)(transform(tpt))
          case Typed(expr, tpt) =>
            cpy.Typed(tree)(transform(expr), transform(tpt))
          case NamedArg(name, arg) =>
            cpy.NamedArg(tree)(name, transform(arg))
          case Assign(lhs, rhs) =>
            cpy.Assign(tree)(transform(lhs), transform(rhs))
          case Block(stats, expr) =>
            cpy.Block(tree)(transformStats(stats), transform(expr))
          case If(cond, thenp, elsep) =>
            cpy.If(tree)(transform(cond), transform(thenp), transform(elsep))
          case Closure(env, meth, tpt) =>
            cpy.Closure(tree)(transform(env), transform(meth), transform(tpt))
          case Match(selector, cases) =>
            cpy.Match(tree)(transform(selector), transformSub(cases))
          case CaseDef(pat, guard, body) =>
            cpy.CaseDef(tree)(transform(pat), transform(guard), transform(body))
          case Labeled(bind, expr) =>
            cpy.Labeled(tree)(transformSub(bind), transform(expr))
          case Return(expr, from) =>
            cpy.Return(tree)(transform(expr), transformSub(from))
          case Try(block, cases, finalizer) =>
            cpy.Try(tree)(transform(block), transformSub(cases), transform(finalizer))
          case SeqLiteral(elems, elemtpt) =>
            cpy.SeqLiteral(tree)(transform(elems), transform(elemtpt))
          case Inlined(call, bindings, expansion) =>
            cpy.Inlined(tree)(call, transformSub(bindings), transform(expansion)(inlineContext(call)))
          case TypeTree() =>
            tree
          case SingletonTypeTree(ref) =>
            cpy.SingletonTypeTree(tree)(transform(ref))
          case AndTypeTree(left, right) =>
            cpy.AndTypeTree(tree)(transform(left), transform(right))
          case OrTypeTree(left, right) =>
            cpy.OrTypeTree(tree)(transform(left), transform(right))
          case RefinedTypeTree(tpt, refinements) =>
            cpy.RefinedTypeTree(tree)(transform(tpt), transformSub(refinements))
          case AppliedTypeTree(tpt, args) =>
            cpy.AppliedTypeTree(tree)(transform(tpt), transform(args))
          case LambdaTypeTree(tparams, body) =>
            implicit val ctx = localCtx
            cpy.LambdaTypeTree(tree)(transformSub(tparams), transform(body))
          case ByNameTypeTree(result) =>
            cpy.ByNameTypeTree(tree)(transform(result))
          case TypeBoundsTree(lo, hi) =>
            cpy.TypeBoundsTree(tree)(transform(lo), transform(hi))
          case Bind(name, body) =>
            cpy.Bind(tree)(name, transform(body))
          case Alternative(trees) =>
            cpy.Alternative(tree)(transform(trees))
          case UnApply(fun, implicits, patterns) =>
            cpy.UnApply(tree)(transform(fun), transform(implicits), transform(patterns))
          case EmptyValDef =>
            tree
          case tree @ ValDef(name, tpt, _) =>
            implicit val ctx = localCtx
            val tpt1 = transform(tpt)
            val rhs1 = transform(tree.rhs)
            cpy.ValDef(tree)(name, tpt1, rhs1)
          case tree @ DefDef(name, tparams, vparamss, tpt, _) =>
            implicit val ctx = localCtx
            cpy.DefDef(tree)(name, transformSub(tparams), vparamss mapConserve (transformSub(_)), transform(tpt), transform(tree.rhs))
          case tree @ TypeDef(name, rhs) =>
            implicit val ctx = localCtx
            cpy.TypeDef(tree)(name, transform(rhs))
          case tree @ Template(constr, parents, self, _) =>
            cpy.Template(tree)(transformSub(constr), transform(parents), transformSub(self), transformStats(tree.body))
          case Import(expr, selectors) =>
            cpy.Import(tree)(transform(expr), selectors)
          case PackageDef(pid, stats) =>
            cpy.PackageDef(tree)(transformSub(pid), transformStats(stats)(localCtx))
          case Annotated(arg, annot) =>
            cpy.Annotated(tree)(transform(arg), transform(annot))
          case Thicket(trees) =>
            val trees1 = transform(trees)
            if (trees1 eq trees) tree else Thicket(trees1)
          case _ =>
            transformMoreCases(tree)
        }
      }

      def transformStats(trees: List[Tree])(implicit ctx: Context): List[Tree] =
        transform(trees)
      def transform(trees: List[Tree])(implicit ctx: Context): List[Tree] =
        flatten(trees mapConserve (transform(_)))
      def transformSub[Tr <: Tree](tree: Tr)(implicit ctx: Context): Tr =
        transform(tree).asInstanceOf[Tr]
      def transformSub[Tr <: Tree](trees: List[Tr])(implicit ctx: Context): List[Tr] =
        transform(trees).asInstanceOf[List[Tr]]

      protected def transformMoreCases(tree: Tree)(implicit ctx: Context): Tree = tree match {
        case tpd.UntypedSplice(usplice) =>
          // For a typed tree map: homomorphism on the untyped part with
          // recursive mapping of typed splices.
          // The case is overridden in UntypedTreeMap.##
          val untpdMap = new untpd.UntypedTreeMap {
            override def transform(tree: untpd.Tree)(implicit ctx: Context): untpd.Tree = tree match {
              case untpd.TypedSplice(tsplice) =>
                untpd.cpy.TypedSplice(tree)(self.transform(tsplice).asInstanceOf[tpd.Tree])
                  // the cast is safe, since the UntypedSplice case is overridden in UntypedTreeMap.
              case _ => super.transform(tree)
            }
          }
          cpy.UntypedSplice(tree)(untpdMap.transform(usplice))
        case _ if ctx.reporter.errorsReported => tree
      }
    }

    abstract class TreeAccumulator[X] { self =>
      // Ties the knot of the traversal: call `foldOver(x, tree))` to dive in the `tree` node.
      def apply(x: X, tree: Tree)(implicit ctx: Context): X

      def apply(x: X, trees: Traversable[Tree])(implicit ctx: Context): X = (x /: trees)(apply)
      def foldOver(x: X, tree: Tree)(implicit ctx: Context): X = {
        Stats.record(s"TreeAccumulator.foldOver $getClass")
        Stats.record("TreeAccumulator.foldOver total")
        def localCtx =
          if (tree.hasType && tree.symbol.exists) ctx.withOwner(tree.symbol) else ctx
        tree match {
          case Ident(name) =>
            x
          case Select(qualifier, name) =>
            this(x, qualifier)
          case This(qual) =>
            x
          case Super(qual, mix) =>
            this(x, qual)
          case Apply(fun, args) =>
            this(this(x, fun), args)
          case TypeApply(fun, args) =>
            this(this(x, fun), args)
          case Literal(const) =>
            x
          case New(tpt) =>
            this(x, tpt)
          case Typed(expr, tpt) =>
            this(this(x, expr), tpt)
          case NamedArg(name, arg) =>
            this(x, arg)
          case Assign(lhs, rhs) =>
            this(this(x, lhs), rhs)
          case Block(stats, expr) =>
            this(this(x, stats), expr)
          case If(cond, thenp, elsep) =>
            this(this(this(x, cond), thenp), elsep)
          case Closure(env, meth, tpt) =>
            this(this(this(x, env), meth), tpt)
          case Match(selector, cases) =>
            this(this(x, selector), cases)
          case CaseDef(pat, guard, body) =>
            this(this(this(x, pat), guard), body)
          case Labeled(bind, expr) =>
            this(this(x, bind), expr)
          case Return(expr, from) =>
            this(this(x, expr), from)
          case Try(block, handler, finalizer) =>
            this(this(this(x, block), handler), finalizer)
          case SeqLiteral(elems, elemtpt) =>
            this(this(x, elems), elemtpt)
          case Inlined(call, bindings, expansion) =>
            this(this(x, bindings), expansion)(inlineContext(call))
          case TypeTree() =>
            x
          case SingletonTypeTree(ref) =>
            this(x, ref)
          case AndTypeTree(left, right) =>
            this(this(x, left), right)
          case OrTypeTree(left, right) =>
            this(this(x, left), right)
          case RefinedTypeTree(tpt, refinements) =>
            this(this(x, tpt), refinements)
          case AppliedTypeTree(tpt, args) =>
            this(this(x, tpt), args)
          case LambdaTypeTree(tparams, body) =>
            implicit val ctx = localCtx
            this(this(x, tparams), body)
          case ByNameTypeTree(result) =>
            this(x, result)
          case TypeBoundsTree(lo, hi) =>
            this(this(x, lo), hi)
          case Bind(name, body) =>
            this(x, body)
          case Alternative(trees) =>
            this(x, trees)
          case UnApply(fun, implicits, patterns) =>
            this(this(this(x, fun), implicits), patterns)
          case tree @ ValDef(name, tpt, _) =>
            implicit val ctx = localCtx
            this(this(x, tpt), tree.rhs)
          case tree @ DefDef(name, tparams, vparamss, tpt, _) =>
            implicit val ctx = localCtx
            this(this((this(x, tparams) /: vparamss)(apply), tpt), tree.rhs)
          case TypeDef(name, rhs) =>
            implicit val ctx = localCtx
            this(x, rhs)
          case tree @ Template(constr, parents, self, _) =>
            this(this(this(this(x, constr), parents), self), tree.body)
          case Import(expr, selectors) =>
            this(x, expr)
          case PackageDef(pid, stats) =>
            this(this(x, pid), stats)(localCtx)
          case Annotated(arg, annot) =>
            this(this(x, arg), annot)
          case Thicket(ts) =>
            this(x, ts)
          case _ =>
            foldMoreCases(x, tree)
        }
      }

      def foldMoreCases(x: X, tree: Tree)(implicit ctx: Context): X = tree match {
        case tpd.UntypedSplice(usplice) =>
          // For a typed tree accumulator: skip the untyped part and fold all typed splices.
          // The case is overridden in UntypedTreeAccumulator.
          val untpdAcc = new untpd.UntypedTreeAccumulator[X] {
            override def apply(x: X, tree: untpd.Tree)(implicit ctx: Context): X = tree match {
              case untpd.TypedSplice(tsplice) => self(x, tsplice)
              case _ => foldOver(x, tree)
            }
          }
          untpdAcc(x, usplice)
        case _ if ctx.reporter.errorsReported || ctx.mode.is(Mode.Interactive) =>
          // In interactive mode, errors might come from previous runs.
          // In case of errors it may be that typed trees point to untyped ones.
          // The IDE can still traverse inside such trees, either in the run where errors
          // are reported, or in subsequent ones.
          x
      }
    }

    abstract class TreeTraverser extends TreeAccumulator[Unit] {
      def traverse(tree: Tree)(implicit ctx: Context): Unit
      def apply(x: Unit, tree: Tree)(implicit ctx: Context) = traverse(tree)
      protected def traverseChildren(tree: Tree)(implicit ctx: Context) = foldOver((), tree)
    }

    /** Fold `f` over all tree nodes, in depth-first, prefix order */
    class DeepFolder[X](f: (X, Tree) => X) extends TreeAccumulator[X] {
      def apply(x: X, tree: Tree)(implicit ctx: Context): X = foldOver(f(x, tree), tree)
    }

    /** Fold `f` over all tree nodes, in depth-first, prefix order, but don't visit
     *  subtrees where `f` returns a different result for the root, i.e. `f(x, root) ne x`.
     */
    class ShallowFolder[X](f: (X, Tree) => X) extends TreeAccumulator[X] {
      def apply(x: X, tree: Tree)(implicit ctx: Context): X = {
        val x1 = f(x, tree)
        if (x1.asInstanceOf[AnyRef] ne x.asInstanceOf[AnyRef]) x1
        else foldOver(x1, tree)
      }
    }

    def rename(tree: NameTree, newName: Name)(implicit ctx: Context): tree.ThisTree[T] = {
      tree match {
        case tree: Ident => cpy.Ident(tree)(newName)
        case tree: Select => cpy.Select(tree)(tree.qualifier, newName)
        case tree: Bind => cpy.Bind(tree)(newName, tree.body)
        case tree: ValDef => cpy.ValDef(tree)(name = newName.asTermName)
        case tree: DefDef => cpy.DefDef(tree)(name = newName.asTermName)
        case tree: TypeDef => cpy.TypeDef(tree)(name = newName.asTypeName)
      }
    }.asInstanceOf[tree.ThisTree[T]]
  }
}
