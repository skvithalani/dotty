package dotty.tools
package dotc
package typer

import core._
import ast._
import Trees._, Constants._, StdNames._, Scopes._, Denotations._, Comments._
import Contexts._, Symbols._, Types._, SymDenotations._, Names._, NameOps._, Flags._, Decorators._
import NameKinds.DefaultGetterName
import ast.desugar, ast.desugar._
import ProtoTypes._
import util.Positions._
import util.{Property, SourcePosition, DotClass}
import collection.mutable
import annotation.tailrec
import ErrorReporting._
import tpd.ListOfTreeDecorator
import config.Config
import config.Printers.{typr, noPrinter}
import Annotations._
import Inferencing._
import transform.ValueClasses._
import TypeApplications._
import language.implicitConversions
import reporting.diagnostic.messages._

trait NamerContextOps { this: Context =>
  import NamerContextOps._

  def typer = ctx.typeAssigner match {
    case typer: Typer => typer
    case _ => new Typer
  }

  /** Enter symbol into current class, if current class is owner of current context,
   *  or into current scope, if not. Should always be called instead of scope.enter
   *  in order to make sure that updates to class members are reflected in
   *  finger prints.
   */
  def enter(sym: Symbol): Symbol = {
    ctx.owner match {
      case cls: ClassSymbol => cls.enter(sym)
      case _ => this.scope.openForMutations.enter(sym)
    }
    sym
  }

  /** The denotation with the given name in current context */
  def denotNamed(name: Name): Denotation =
    if (owner.isClass)
      if (outer.owner == owner) { // inner class scope; check whether we are referring to self
        if (scope.size == 1) {
          val elem = scope.lastEntry
          if (elem.name == name) return elem.sym.denot // return self
        }
        owner.thisType.member(name)
      }
      else // we are in the outermost context belonging to a class; self is invisible here. See inClassContext.
        owner.findMember(name, owner.thisType, EmptyFlags)
    else
      scope.denotsNamed(name).toDenot(NoPrefix)

  /** Either the current scope, or, if the current context owner is a class,
   *  the declarations of the current class.
   */
  def effectiveScope: Scope =
    if (owner != null && owner.isClass) owner.asClass.unforcedDecls
    else scope

  /** The symbol (stored in some typer's symTree) of an enclosing context definition */
  def symOfContextTree(tree: untpd.Tree) = {
    def go(ctx: Context): Symbol = {
      ctx.typeAssigner match {
        case typer: Typer =>
          tree.getAttachment(typer.SymOfTree) match {
            case Some(sym) => sym
            case None =>
              var cx = ctx.outer
              while (cx.typeAssigner eq typer) cx = cx.outer
              go(cx)
          }
        case _ => NoSymbol
      }
    }
    go(this)
  }

  /** Context where `sym` is defined, assuming we are in a nested context. */
  def defContext(sym: Symbol) =
    outersIterator
      .dropWhile(_.owner != sym)
      .dropWhile(_.owner == sym)
      .next()

  /** A fresh local context with given tree and owner.
   *  Owner might not exist (can happen for self valdefs), in which case
   *  no owner is set in result context
   */
  def localContext(tree: untpd.Tree, owner: Symbol): FreshContext = {
    val freshCtx = fresh.setTree(tree)
    if (owner.exists) freshCtx.setOwner(owner) else freshCtx
  }

  /** A new context for the interior of a class */
  def inClassContext(selfInfo: AnyRef /* Should be Type | Symbol*/): Context = {
    val localCtx: Context = ctx.fresh.setNewScope
    selfInfo match {
      case sym: Symbol if sym.exists && sym.name != nme.WILDCARD => localCtx.scope.openForMutations.enter(sym)
      case _ =>
    }
    localCtx
  }

  def packageContext(tree: untpd.PackageDef, pkg: Symbol): Context =
    if (pkg is Package) ctx.fresh.setOwner(pkg.moduleClass).setTree(tree)
    else ctx

  /** The given type, unless `sym` is a constructor, in which case the
   *  type of the constructed instance is returned
   */
  def effectiveResultType(sym: Symbol, typeParams: List[Symbol], given: Type) =
    if (sym.name == nme.CONSTRUCTOR) sym.owner.typeRef.appliedTo(typeParams.map(_.typeRef))
    else given

  /** if isConstructor, make sure it has one non-implicit parameter list */
  def normalizeIfConstructor(termParamss: List[List[Symbol]], isConstructor: Boolean) =
    if (isConstructor &&
      (termParamss.isEmpty || termParamss.head.nonEmpty && (termParamss.head.head is Implicit)))
      Nil :: termParamss
    else
      termParamss

  /** The method type corresponding to given parameters and result type */
  def methodType(typeParams: List[Symbol], valueParamss: List[List[Symbol]], resultType: Type, isJava: Boolean = false)(implicit ctx: Context): Type = {
    val monotpe =
      (valueParamss :\ resultType) { (params, resultType) =>
        val (isImplicit, isErased) =
          if (params.isEmpty) (false, false)
          else (params.head is Implicit, params.head is Erased)
        val make = MethodType.maker(isJava, isImplicit, isErased)
        if (isJava)
          for (param <- params)
            if (param.info.isDirectRef(defn.ObjectClass)) param.info = defn.AnyType
        make.fromSymbols(params.asInstanceOf[List[TermSymbol]], resultType)
      }
    if (typeParams.nonEmpty) PolyType.fromParams(typeParams.asInstanceOf[List[TypeSymbol]], monotpe)
    else if (valueParamss.isEmpty) ExprType(monotpe)
    else monotpe
  }

  /** Add moduleClass or sourceModule functionality to completer
   *  for a module or module class
   */
  def adjustModuleCompleter(completer: LazyType, name: Name) = {
    val scope = this.effectiveScope
    if (name.isTermName)
      completer withModuleClass (implicit ctx => findModuleBuddy(name.moduleClassName, scope))
    else
      completer withSourceModule (implicit ctx => findModuleBuddy(name.sourceModuleName, scope))
  }
}

object NamerContextOps {
  /** Find moduleClass/sourceModule in effective scope */
  private def findModuleBuddy(name: Name, scope: Scope)(implicit ctx: Context) = {
    val it = scope.lookupAll(name).filter(_ is Module)
    if (it.hasNext) it.next()
    else NoSymbol.assertingErrorsReported(s"no companion $name in $scope")
  }
}

/** This class creates symbols from definitions and imports and gives them
 *  lazy types.
 *
 *  Timeline:
 *
 *  During enter, trees are expanded as necessary, populating the expandedTree map.
 *  Symbols are created, and the symOfTree map is set up.
 *
 *  Symbol completion causes some trees to be already typechecked and typedTree
 *  entries are created to associate the typed trees with the untyped expanded originals.
 *
 *  During typer, original trees are first expanded using expandedTree. For each
 *  expanded member definition or import we extract and remove the corresponding symbol
 *  from the symOfTree map and complete it. We then consult the typedTree map to see
 *  whether a typed tree exists already. If yes, the typed tree is returned as result.
 *  Otherwise, we proceed with regular type checking.
 *
 *  The scheme is designed to allow sharing of nodes, as long as each duplicate appears
 *  in a different method.
 */
class Namer { typer: Typer =>

  import untpd._

  val TypedAhead = new Property.Key[tpd.Tree]
  val ExpandedTree = new Property.Key[Tree]
  val SymOfTree = new Property.Key[Symbol]

  /** A partial map from unexpanded member and pattern defs and to their expansions.
   *  Populated during enterSyms, emptied during typer.
   */
  //lazy val expandedTree = new mutable.AnyRefMap[DefTree, Tree]
  /*{
    override def default(tree: DefTree) = tree // can't have defaults on AnyRefMaps :-(
  }*/

  /** A map from expanded MemberDef, PatDef or Import trees to their symbols.
   *  Populated during enterSyms, emptied at the point a typed tree
   *  with the same symbol is created (this can be when the symbol is completed
   *  or at the latest when the tree is typechecked.
   */
  //lazy val symOfTree = new mutable.AnyRefMap[Tree, Symbol]

  /** A map from expanded trees to their typed versions.
   *  Populated when trees are typechecked during completion (using method typedAhead).
   */
  // lazy val typedTree = new mutable.AnyRefMap[Tree, tpd.Tree]

  /** A map from method symbols to nested typers.
   *  Populated when methods are completed. Emptied when they are typechecked.
   *  The nested typer contains new versions of the four maps above including this
   *  one, so that trees that are shared between different DefDefs can be independently
   *  used as indices. It also contains a scope that contains nested parameters.
   */
  lazy val nestedTyper = new mutable.AnyRefMap[Symbol, Typer]

  /** The scope of the typer.
   *  For nested typers this is a place parameters are entered during completion
   *  and where they survive until typechecking. A context with this typer also
   *  has this scope.
   */
  val scope = newScope

  /** We are entering symbols coming from a SourceLoader */
  private[this] var lateCompile = false

  /** The symbol of the given expanded tree. */
  def symbolOfTree(tree: Tree)(implicit ctx: Context): Symbol = {
    val xtree = expanded(tree)
    xtree.getAttachment(TypedAhead) match {
      case Some(ttree) => ttree.symbol
      case none => xtree.attachment(SymOfTree)
    }
  }

  /** The enclosing class with given name; error if none exists */
  def enclosingClassNamed(name: TypeName, pos: Position)(implicit ctx: Context): Symbol = {
    if (name.isEmpty) NoSymbol
    else {
      val cls = ctx.owner.enclosingClassNamed(name)
      if (!cls.exists) ctx.error(s"no enclosing class or object is named $name", pos)
      cls
    }
  }

  /** Record `sym` as the symbol defined by `tree` */
  def recordSym(sym: Symbol, tree: Tree)(implicit ctx: Context): Symbol = {
    for (refs <- tree.removeAttachment(References); ref <- refs) ref.watching(sym)
    tree.pushAttachment(SymOfTree, sym)
    sym
  }

  /** If this tree is a member def or an import, create a symbol of it
   *  and store in symOfTree map.
   */
  def createSymbol(tree: Tree)(implicit ctx: Context): Symbol = {

    def privateWithinClass(mods: Modifiers) =
      enclosingClassNamed(mods.privateWithin, mods.pos)

    /** Check that flags are OK for symbol. This is done early to avoid
     *  catastrophic failure when we create a TermSymbol with TypeFlags, or vice versa.
     *  A more complete check is done in checkWellformed.
     */
    def checkFlags(flags: FlagSet) =
      if (flags.isEmpty) flags
      else {
        val (ok, adapted, kind) = tree match {
          case tree: TypeDef => (flags.isTypeFlags, flags.toTypeFlags, "type")
          case _ => (flags.isTermFlags, flags.toTermFlags, "value")
        }
        if (!ok)
          ctx.error(i"modifier(s) `$flags' incompatible with $kind definition", tree.pos)
        adapted
      }

    /** Add moduleClass/sourceModule to completer if it is for a module val or class */
    def adjustIfModule(completer: LazyType, tree: MemberDef) =
      if (tree.mods is Module) ctx.adjustModuleCompleter(completer, tree.name)
      else completer

    typr.println(i"creating symbol for $tree in ${ctx.mode}")

    def checkNoConflict(name: Name): Name = {
      def errorName(msg: => String) = {
        ctx.error(msg, tree.pos)
        name.freshened
      }
      def preExisting = ctx.effectiveScope.lookup(name)
      if (ctx.owner is PackageClass)
        if (preExisting.isDefinedInCurrentRun)
          errorName(s"${preExisting.showLocated} has already been compiled once during this run")
        else name
      else
        if ((!ctx.owner.isClass || name.isTypeName) && preExisting.exists)
          errorName(i"$name is already defined as $preExisting")
        else name
    }

    /** Create new symbol or redefine existing symbol under lateCompile. */
    def createOrRefine[S <: Symbol](
        tree: MemberDef, name: Name, flags: FlagSet, infoFn: S => Type,
        symFn: (FlagSet, S => Type, Symbol) => S): Symbol = {
      val prev =
        if (lateCompile && ctx.owner.is(Package)) ctx.effectiveScope.lookup(name)
        else NoSymbol
      val sym =
        if (prev.exists) {
          prev.flags = flags
          prev.info = infoFn(prev.asInstanceOf[S])
          prev.privateWithin = privateWithinClass(tree.mods)
          prev
        }
        else symFn(flags, infoFn, privateWithinClass(tree.mods))
      recordSym(sym, tree)
    }

    tree match {
      case tree: TypeDef if tree.isClassDef =>
        val name = checkNoConflict(tree.name).asTypeName
        val flags = checkFlags(tree.mods.flags &~ Implicit)
        val cls =
          createOrRefine[ClassSymbol](tree, name, flags,
            cls => adjustIfModule(new ClassCompleter(cls, tree)(ctx), tree),
            ctx.newClassSymbol(ctx.owner, name, _, _, _, tree.namePos, ctx.source.file))
        cls.completer.asInstanceOf[ClassCompleter].init()
        cls
      case tree: MemberDef =>
        val name = checkNoConflict(tree.name)
        val flags = checkFlags(tree.mods.flags)
        val isDeferred = lacksDefinition(tree)
        val deferred = if (isDeferred) Deferred else EmptyFlags
        val method = if (tree.isInstanceOf[DefDef]) Method else EmptyFlags
        val higherKinded = tree match {
          case TypeDef(_, LambdaTypeTree(_, _)) if isDeferred => HigherKinded
          case _ => EmptyFlags
        }

        // to complete a constructor, move one context further out -- this
        // is the context enclosing the class. Note that the context in which a
        // constructor is recorded and the context in which it is completed are
        // different: The former must have the class as owner (because the
        // constructor is owned by the class), the latter must not (because
        // constructor parameters are interpreted as if they are outside the class).
        // Don't do this for Java constructors because they need to see the import
        // of the companion object, and it is not necessary for them because they
        // have no implementation.
        val cctx = if (tree.name == nme.CONSTRUCTOR && !(tree.mods is JavaDefined)) ctx.outer else ctx

        val completer = tree match {
          case tree: TypeDef => new TypeDefCompleter(tree)(cctx)
          case _ => new Completer(tree)(cctx)
        }
        val info = adjustIfModule(completer, tree)
        createOrRefine[Symbol](tree, name, flags | deferred | method | higherKinded,
          _ => info,
          (fs, _, pwithin) => ctx.newSymbol(ctx.owner, name, fs, info, pwithin, tree.namePos))
      case tree: Import =>
        recordSym(ctx.newImportSymbol(ctx.owner, new Completer(tree), tree.pos), tree)
      case _ =>
        NoSymbol
    }
  }

   /** If `sym` exists, enter it in effective scope. Check that
    *  package members are not entered twice in the same run.
    */
  def enterSymbol(sym: Symbol)(implicit ctx: Context) = {
    if (sym.exists) {
      typr.println(s"entered: $sym in ${ctx.owner}")
      ctx.enter(sym)
    }
    sym
  }

  /** Create package if it does not yet exist. */
  private def createPackageSymbol(pid: RefTree)(implicit ctx: Context): Symbol = {
    val pkgOwner = pid match {
      case Ident(_) => if (ctx.owner eq defn.EmptyPackageClass) defn.RootClass else ctx.owner
      case Select(qual: RefTree, _) => createPackageSymbol(qual).moduleClass
    }
    val existing = pkgOwner.info.decls.lookup(pid.name)

    if ((existing is Package) && (pkgOwner eq existing.owner)) {
      existing.moduleClass.denot match {
        case d: PackageClassDenotation =>
          // Remove existing members coming from a previous compilation of this file,
          // they are obsolete.
          d.unlinkFromFile(ctx.source.file)
        case _ =>
      }
      existing
    }
    else {
      /** If there's already an existing type, then the package is a dup of this type */
      val existingType = pkgOwner.info.decls.lookup(pid.name.toTypeName)
      if (existingType.exists) {
        ctx.error(PkgDuplicateSymbol(existingType), pid.pos)
        ctx.newCompletePackageSymbol(pkgOwner, (pid.name ++ "$_error_").toTermName).entered
      }
      else ctx.newCompletePackageSymbol(pkgOwner, pid.name.asTermName).entered
    }
  }

  /** Expand tree and store in `expandedTree` */
  def expand(tree: Tree)(implicit ctx: Context): Unit = tree match {
    case mdef: DefTree =>
      val expanded = desugar.defTree(mdef)
      typr.println(i"Expansion: $mdef expands to $expanded")
      if (expanded ne mdef) mdef.pushAttachment(ExpandedTree, expanded)
    case _ =>
  }

  /** The expanded version of this tree, or tree itself if not expanded */
  def expanded(tree: Tree)(implicit ctx: Context): Tree = tree match {
    case ddef: DefTree => ddef.attachmentOrElse(ExpandedTree, ddef)
    case _ => tree
  }

  /** For all class definitions `stat` in `xstats`: If the companion class if
    * not also defined in `xstats`, invalidate it by setting its info to
    * NoType.
    */
  def invalidateCompanions(pkg: Symbol, xstats: List[untpd.Tree])(implicit ctx: Context): Unit = {
    val definedNames = xstats collect { case stat: NameTree => stat.name }
    def invalidate(name: TypeName) =
      if (!(definedNames contains name)) {
        val member = pkg.info.decl(name).asSymDenotation
        if (member.isClass && !(member is Package)) member.info = NoType
      }
    xstats foreach {
      case stat: TypeDef if stat.isClassDef =>
        invalidate(stat.name.moduleClassName)
      case _ =>
    }
  }

  /** Expand tree and create top-level symbols for statement and enter them into symbol table */
  def index(stat: Tree)(implicit ctx: Context): Context = {
    expand(stat)
    indexExpanded(stat)
  }

  /** Create top-level symbols for all statements in the expansion of this statement and
   *  enter them into symbol table
   */
  def indexExpanded(origStat: Tree)(implicit ctx: Context): Context = {
    def recur(stat: Tree): Context = stat match {
      case pcl: PackageDef =>
        val pkg = createPackageSymbol(pcl.pid)
        index(pcl.stats)(ctx.fresh.setOwner(pkg.moduleClass))
        invalidateCompanions(pkg, Trees.flatten(pcl.stats map expanded))
        setDocstring(pkg, stat)
        ctx
      case imp: Import =>
        ctx.importContext(imp, createSymbol(imp))
      case mdef: DefTree =>
        val sym = enterSymbol(createSymbol(mdef))
        setDocstring(sym, origStat)
        addEnumConstants(mdef, sym)
        ctx
      case stats: Thicket =>
        stats.toList.foreach(recur)
        ctx
      case _ =>
        ctx
    }
    recur(expanded(origStat))
  }

  /** Determines whether this field holds an enum constant.
    * To qualify, the following conditions must be met:
    *  - The field's class has the ENUM flag set
    *  - The field's class extends java.lang.Enum
    *  - The field has the ENUM flag set
    *  - The field is static
    *  - The field is stable
    */
  def isEnumConstant(vd: ValDef)(implicit ctx: Context) = {
    // val ownerHasEnumFlag =
    // Necessary to check because scalac puts Java's static members into the companion object
    // while Scala's enum constants live directly in the class.
    // We don't check for clazz.superClass == JavaEnumClass, because this causes a illegal
    // cyclic reference error. See the commit message for details.
    //  if (ctx.compilationUnit.isJava) ctx.owner.companionClass.is(Enum) else ctx.owner.is(Enum)
    vd.mods.is(JavaEnumValue) // && ownerHasEnumFlag
  }

  /** Add java enum constants */
  def addEnumConstants(mdef: DefTree, sym: Symbol)(implicit ctx: Context): Unit = mdef match {
    case vdef: ValDef if (isEnumConstant(vdef)) =>
      val enumClass = sym.owner.linkedClass
      if (!(enumClass is Flags.Sealed)) enumClass.setFlag(Flags.AbstractSealed)
      enumClass.addAnnotation(Annotation.Child(sym))
    case _ =>
  }


  def setDocstring(sym: Symbol, tree: Tree)(implicit ctx: Context) = tree match {
    case t: MemberDef if t.rawComment.isDefined =>
      ctx.docCtx.foreach(_.addDocstring(sym, t.rawComment))
    case _ => ()
  }

  /** Create top-level symbols for statements and enter them into symbol table */
  def index(stats: List[Tree])(implicit ctx: Context): Context = {

    // module name -> (stat, moduleCls | moduleVal)
    val moduleClsDef = mutable.Map[TypeName, (Tree, TypeDef)]()
    val moduleValDef = mutable.Map[TermName, (Tree, ValDef)]()

    /** Remove the subtree `tree` from the expanded tree of `mdef` */
    def removeInExpanded(mdef: Tree, tree: Tree): Unit = {
      val Thicket(trees) = expanded(mdef)
      mdef.putAttachment(ExpandedTree, Thicket(trees.filter(_ != tree)))
    }

    /** Transfer all references to `from` to `to` */
    def transferReferences(from: ValDef, to: ValDef): Unit = {
      val fromRefs = from.removeAttachment(References).getOrElse(Nil)
      val toRefs = to.removeAttachment(References).getOrElse(Nil)
      to.putAttachment(References, fromRefs ++ toRefs)
    }

    /** Merge the module class `modCls` in the expanded tree of `mdef` with the given stats */
    def mergeModuleClass(mdef: Tree, modCls: TypeDef, stats: List[Tree]): TypeDef = {
      var res: TypeDef = null
      val Thicket(trees) = expanded(mdef)
      val merged = trees.map { tree =>
        if (tree == modCls) {
          val impl = modCls.rhs.asInstanceOf[Template]
          res = cpy.TypeDef(modCls)(rhs = cpy.Template(impl)(body = stats ++ impl.body))
          res
        }
        else tree
      }

      mdef.putAttachment(ExpandedTree, Thicket(merged))

      res
    }

    /** Merge `fromCls` of `fromStat` into `toCls` of `toStat`
     *  if the former is synthetic and the latter not.
     *
     *  Note:
     *    1. `fromStat` and `toStat` could be the same stat
     *    2. `fromCls` and `toCls` are necessarily different
     */
    def mergeIfSynthetic(fromStat: Tree, fromCls: TypeDef, toStat: Tree, toCls: TypeDef): Unit =
      if (fromCls.mods.is(Synthetic) && !toCls.mods.is(Synthetic)) {
        removeInExpanded(fromStat, fromCls)
        val mcls = mergeModuleClass(toStat, toCls, fromCls.rhs.asInstanceOf[Template].body)
        moduleClsDef(fromCls.name) = (toStat, mcls)
      }

    /** Merge the definitions of a synthetic companion generated by a case class
     *  and the real companion, if both exist.
     */
    def mergeCompanionDefs() = {
      def valid(mdef: MemberDef): Boolean = mdef.mods.is(Module, butNot = Package)

      for (stat <- stats)
        expanded(stat) match {
          case Thicket(trees) => // companion object always expands to thickets
            trees.map {
              case mcls @ TypeDef(name, impl: Template) if valid(mcls) =>
                (moduleClsDef.get(name): @unchecked) match {
                  case Some((stat1, mcls1@TypeDef(_, impl1: Template))) =>
                    mergeIfSynthetic(stat, mcls, stat1, mcls1)
                    mergeIfSynthetic(stat1, mcls1, stat, mcls)
                  case None =>
                    moduleClsDef(name) = (stat, mcls)
                }

              case vdef @ ValDef(name, _, _) if valid(vdef) =>
                moduleValDef.get(name) match {
                  case Some((stat1, vdef1)) =>
                    if (vdef.mods.is(Synthetic) && !vdef1.mods.is(Synthetic)) {
                      transferReferences(vdef, vdef1)
                      removeInExpanded(stat, vdef)
                    }
                    else if (!vdef.mods.is(Synthetic) && vdef1.mods.is(Synthetic)) {
                      transferReferences(vdef1, vdef)
                      removeInExpanded(stat1, vdef1)
                      moduleValDef(name) = (stat, vdef)
                    }
                    else {
                      // double definition of objects or case classes, handled elsewhere
                    }
                  case None =>
                    moduleValDef(name) = (stat, vdef)
                }

              case _ =>
            }
          case _ =>
        }
    }

    /** Create links between companion object and companion class */
    def createLinks(classTree: TypeDef, moduleTree: TypeDef)(implicit ctx: Context) = {
      val claz = ctx.effectiveScope.lookup(classTree.name)
      val modl = ctx.effectiveScope.lookup(moduleTree.name)
      if (claz.isClass && modl.isClass) {
        ctx.synthesizeCompanionMethod(nme.COMPANION_CLASS_METHOD, claz, modl).entered
        ctx.synthesizeCompanionMethod(nme.COMPANION_MODULE_METHOD, modl, claz).entered
      }
    }

    def createCompanionLinks(implicit ctx: Context): Unit = {
      val classDef  = mutable.Map[TypeName, TypeDef]()
      val moduleDef = mutable.Map[TypeName, TypeDef]()

      def updateCache(cdef: TypeDef): Unit = {
        if (!cdef.isClassDef || cdef.mods.is(Package)) return

        if (cdef.mods.is(ModuleClass)) moduleDef(cdef.name) = cdef
        else classDef(cdef.name) = cdef
      }

      for (stat <- stats)
        expanded(stat) match {
          case cdef : TypeDef => updateCache(cdef)
          case Thicket(trees) =>
            trees.map {
              case cdef: TypeDef => updateCache(cdef)
              case _ =>
            }
          case _ =>
        }

      for (cdef @ TypeDef(name, _) <- classDef.values) {
        moduleDef.getOrElse(name.moduleClassName, EmptyTree) match {
          case t: TypeDef =>
            createLinks(cdef, t)
          case EmptyTree =>
        }
      }

      // If a top-level object or class has no companion in the current run, we
      // enter a dummy companion (`denot.isAbsent` returns true) in scope. This
      // ensures that we never use a companion from a previous run or from the
      // classpath. See tests/pos/false-companion for an example where this
      // matters.
      if (ctx.owner.is(PackageClass)) {
        for (cdef @ TypeDef(moduleName, _) <- moduleDef.values) {
          val moduleSym = ctx.effectiveScope.lookup(moduleName)
          if (moduleSym.isDefinedInCurrentRun) {
            val className = moduleName.stripModuleClassSuffix.toTypeName
            val classSym = ctx.effectiveScope.lookup(className)
            if (!classSym.isDefinedInCurrentRun) {
              val absentClassSymbol = ctx.newClassSymbol(ctx.owner, className, EmptyFlags, _ => NoType)
              enterSymbol(absentClassSymbol)
            }
          }
        }
        for (cdef @ TypeDef(className, _) <- classDef.values) {
          val classSym = ctx.effectiveScope.lookup(className.encode)
          if (classSym.isDefinedInCurrentRun) {
            val moduleName = className.toTermName
            val moduleSym = ctx.effectiveScope.lookup(moduleName.encode)
            if (!moduleSym.isDefinedInCurrentRun) {
              val absentModuleSymbol = ctx.newModuleSymbol(ctx.owner, moduleName, EmptyFlags, EmptyFlags, (_, _) => NoType)
              enterSymbol(absentModuleSymbol)
            }
          }
        }
      }
    }

    stats.foreach(expand)
    mergeCompanionDefs()
    val ctxWithStats = (ctx /: stats) ((ctx, stat) => indexExpanded(stat)(ctx))
    createCompanionLinks(ctxWithStats)
    ctxWithStats
  }

  /** Index symbols in `tree` while asserting the `lateCompile` flag.
   *  This will cause any old top-level symbol with the same fully qualified
   *  name as a newly created symbol to be replaced.
   */
  def lateEnter(tree: Tree)(implicit ctx: Context) = {
    val saved = lateCompile
    lateCompile = true
    try index(tree :: Nil) finally lateCompile = saved
  }

  def missingType(sym: Symbol, modifier: String)(implicit ctx: Context) = {
    ctx.error(s"${modifier}type of implicit definition needs to be given explicitly", sym.pos)
    sym.resetFlag(Implicit)
  }

  /** The completer of a symbol defined by a member def or import (except ClassSymbols) */
  class Completer(val original: Tree)(implicit ctx: Context) extends LazyType with SymbolLoaders.SecondCompleter {

    protected def localContext(owner: Symbol) = ctx.fresh.setOwner(owner).setTree(original)

    /** The context with which this completer was created */
    def creationContext = ctx
    ctx.typerState.markShared()

    protected def typeSig(sym: Symbol): Type = original match {
      case original: ValDef =>
        if (sym is Module) moduleValSig(sym)
        else valOrDefDefSig(original, sym, Nil, Nil, identity)(localContext(sym).setNewScope)
      case original: DefDef =>
        val typer1 = ctx.typer.newLikeThis
        nestedTyper(sym) = typer1
        typer1.defDefSig(original, sym)(localContext(sym).setTyper(typer1))
      case imp: Import =>
        try {
          val expr1 = typedAheadExpr(imp.expr, AnySelectionProto)
          ImportType(expr1)
        } catch {
          case ex: CyclicReference =>
            typr.println(s"error while completing ${imp.expr}")
            throw ex
        }
    }

    final override def complete(denot: SymDenotation)(implicit ctx: Context) = {
      if (Config.showCompletions && ctx.typerState != this.ctx.typerState) {
        def levels(c: Context): Int =
          if (c.typerState eq this.ctx.typerState) 0
          else if (c.typerState == null) -1
          else if (c.outer.typerState == c.typerState) levels(c.outer)
          else levels(c.outer) + 1
        println(s"!!!completing ${denot.symbol.showLocated} in buried typerState, gap = ${levels(ctx)}")
      }
      if (ctx.runId > creationContext.runId) {
        assert(ctx.mode.is(Mode.Interactive), s"completing $denot in wrong run ${ctx.runId}, was created in ${creationContext.runId}")
        denot.info = UnspecifiedErrorType
      }
      else completeInCreationContext(denot)
    }

    protected def addAnnotations(sym: Symbol): Unit = original match {
      case original: untpd.MemberDef =>
        lazy val annotCtx = annotContext(original, sym)
        for (annotTree <- untpd.modsDeco(original).mods.annotations) {
          val cls = typedAheadAnnotationClass(annotTree)(annotCtx)
          val ann = Annotation.deferred(cls, implicit ctx => typedAnnotation(annotTree))
          sym.addAnnotation(ann)
          if (cls == defn.ForceInlineAnnot && sym.is(Method, butNot = Accessor))
            sym.setFlag(Rewrite)
        }
      case _ =>
    }

    private def addInlineInfo(sym: Symbol) = original match {
      case original: untpd.DefDef if sym.isInlineable =>
        PrepareInlineable.registerInlineInfo(
            sym,
            original.rhs,
            implicit ctx => typedAheadExpr(original).asInstanceOf[tpd.DefDef].rhs
          )(localContext(sym))
      case _ =>
    }

    /** Invalidate `denot` by overwriting its info with `NoType` if
     *  `denot` is a compiler generated case class method that clashes
     *  with a user-defined method in the same scope with a matching type.
     */
    private def invalidateIfClashingSynthetic(denot: SymDenotation): Unit = {
      def isCaseClass(owner: Symbol) =
        owner.isClass && {
          if (owner.is(Module)) owner.linkedClass.is(CaseClass)
          else owner.is(CaseClass)
        }
      val isClashingSynthetic =
        denot.is(Synthetic) &&
        desugar.isRetractableCaseClassMethodName(denot.name) &&
        isCaseClass(denot.owner) &&
        denot.owner.info.decls.lookupAll(denot.name).exists(alt =>
          alt != denot.symbol && alt.info.matchesLoosely(denot.info))
      if (isClashingSynthetic) {
        typr.println(i"invalidating clashing $denot in ${denot.owner}")
        denot.info = NoType
      }
    }

    /** Intentionally left without `implicit ctx` parameter. We need
     *  to pick up the context at the point where the completer was created.
     */
    def completeInCreationContext(denot: SymDenotation): Unit = {
      val sym = denot.symbol
      addAnnotations(sym)
      addInlineInfo(sym)
      denot.info = typeSig(sym)
      invalidateIfClashingSynthetic(denot)
      Checking.checkWellFormed(sym)
      denot.info = avoidPrivateLeaks(sym, sym.pos)
    }
  }

  class TypeDefCompleter(original: TypeDef)(ictx: Context) extends Completer(original)(ictx) with TypeParamsCompleter {
    private[this] var myTypeParams: List[TypeSymbol] = null
    private[this] var nestedCtx: Context = null
    assert(!original.isClassDef)

    override def completerTypeParams(sym: Symbol)(implicit ctx: Context): List[TypeSymbol] = {
      if (myTypeParams == null) {
        //println(i"completing type params of $sym in ${sym.owner}")
        nestedCtx = localContext(sym).setNewScope
        myTypeParams = {
          implicit val ctx = nestedCtx
          def typeParamTrees(tdef: Tree): List[TypeDef] = tdef match {
            case TypeDef(_, original) =>
              original match {
                case LambdaTypeTree(tparams, _) => tparams
                case original: DerivedFromParamTree => typeParamTrees(original.watched)
                case _ => Nil
              }
            case _ => Nil
          }
          val tparams = typeParamTrees(original)
          completeParams(tparams)
          tparams.map(symbolOfTree(_).asType)
        }
      }
      myTypeParams
    }

    override protected def typeSig(sym: Symbol): Type =
      typeDefSig(original, sym, completerTypeParams(sym)(ictx))(nestedCtx)
  }

  class ClassCompleter(cls: ClassSymbol, original: TypeDef)(ictx: Context) extends Completer(original)(ictx) {
    withDecls(newScope)

    protected implicit val ctx: Context = localContext(cls).setMode(ictx.mode &~ Mode.InSuperCall)

    val TypeDef(name, impl @ Template(constr, parents, self, _)) = original

    val (params, rest) = impl.body span {
      case td: TypeDef => td.mods is Param
      case vd: ValDef => vd.mods is ParamAccessor
      case _ => false
    }

    def init() = index(params)

    /** The type signature of a ClassDef with given symbol */
    override def completeInCreationContext(denot: SymDenotation): Unit = {

      /* The type of a parent constructor. Types constructor arguments
       * only if parent type contains uninstantiated type parameters.
       */
      def parentType(parent: untpd.Tree)(implicit ctx: Context): Type =
        if (parent.isType)
          typedAheadType(parent, AnyTypeConstructorProto).tpe
        else {
          val (core, targs) = stripApply(parent) match {
            case TypeApply(core, targs) => (core, targs)
            case core => (core, Nil)
          }
          core match {
            case Select(New(tpt), nme.CONSTRUCTOR) =>
              val targs1 = targs map (typedAheadType(_))
              val ptype = typedAheadType(tpt).tpe appliedTo targs1.tpes
              if (ptype.typeParams.isEmpty) ptype
              else {
                if (denot.is(ModuleClass) && denot.sourceModule.is(Implicit))
                  missingType(denot.symbol, "parent ")(creationContext)
                fullyDefinedType(typedAheadExpr(parent).tpe, "class parent", parent.pos)
              }
            case _ =>
              UnspecifiedErrorType.assertingErrorsReported
          }
        }

      /* Check parent type tree `parent` for the following well-formedness conditions:
       * (1) It must be a class type with a stable prefix (@see checkClassTypeWithStablePrefix)
       * (2) If may not derive from itself
       * (3) The class is not final
       * (4) If the class is sealed, it is defined in the same compilation unit as the current class
       */
      def checkedParentType(parent: untpd.Tree): Type = {
        val ptype = parentType(parent)(ctx.superCallContext).dealiasKeepAnnots
        if (cls.isRefinementClass) ptype
        else {
          val pt = checkClassType(ptype, parent.pos,
              traitReq = parent ne parents.head, stablePrefixReq = true)
          if (pt.derivesFrom(cls)) {
            val addendum = parent match {
              case Select(qual: Super, _) if ctx.scala2Mode =>
                "\n(Note that inheriting a class of the same name is no longer allowed)"
              case _ => ""
            }
            ctx.error(CyclicInheritance(cls, addendum), parent.pos)
            defn.ObjectType
          }
          else {
            val pclazz = pt.typeSymbol
            if (pclazz.is(Final))
              ctx.error(ExtendFinalClass(cls, pclazz), cls.pos)
            if (pclazz.is(Sealed) && pclazz.associatedFile != cls.associatedFile)
              ctx.error(UnableToExtendSealedClass(pclazz), cls.pos)
            pt
          }
        }
      }

      addAnnotations(denot.symbol)

      val selfInfo =
        if (self.isEmpty) NoType
        else if (cls.is(Module)) {
          val moduleType = cls.owner.thisType select sourceModule
          if (self.name == nme.WILDCARD) moduleType
          else recordSym(
            ctx.newSymbol(cls, self.name, self.mods.flags, moduleType, coord = self.pos),
            self)
        }
        else createSymbol(self)

      // pre-set info, so that parent types can refer to type params
      val tempInfo = new TempClassInfo(cls.owner.thisType, cls, decls, selfInfo)
      denot.info = tempInfo

      // Ensure constructor is completed so that any parameter accessors
      // which have type trees deriving from its parameters can be
      // completed in turn. Note that parent types access such parameter
      // accessors, that's why the constructor needs to be completed before
      // the parent types are elaborated.
      index(constr)
      index(rest)(ctx.inClassContext(selfInfo))
      symbolOfTree(constr).ensureCompleted()

      val parentTypes = ensureFirstIsClass(parents.map(checkedParentType(_)), cls.pos)
      typr.println(i"completing $denot, parents = $parents%, %, parentTypes = $parentTypes%, %")

      tempInfo.finalize(denot, parentTypes)

      Checking.checkWellFormed(cls)
      if (isDerivedValueClass(cls)) cls.setFlag(Final)
      cls.info = avoidPrivateLeaks(cls, cls.pos)
      cls.baseClasses.foreach(_.invalidateBaseTypeCache()) // we might have looked before and found nothing
      cls.setNoInitsFlags(parentsKind(parents), bodyKind(rest))
      if (cls.isNoInitsClass) cls.primaryConstructor.setFlag(Stable)
    }
  }

  /** Typecheck `tree` during completion using `typed`, and remember result in TypedAhead map */
  def typedAheadImpl(tree: Tree, typed: untpd.Tree => tpd.Tree)(implicit ctx: Context): tpd.Tree = {
    val xtree = expanded(tree)
    xtree.getAttachment(TypedAhead) match {
      case Some(ttree) => ttree
      case none =>
        val ttree = typed(tree)
        xtree.putAttachment(TypedAhead, ttree)
        ttree
    }
  }

  def typedAheadType(tree: Tree, pt: Type = WildcardType)(implicit ctx: Context): tpd.Tree =
    typedAheadImpl(tree, typer.typed(_, pt)(ctx retractMode Mode.PatternOrTypeBits addMode Mode.Type))

  def typedAheadExpr(tree: Tree, pt: Type = WildcardType)(implicit ctx: Context): tpd.Tree =
    typedAheadImpl(tree, typer.typed(_, pt)(ctx retractMode Mode.PatternOrTypeBits))

  def typedAheadAnnotation(tree: Tree)(implicit ctx: Context): tpd.Tree =
    typedAheadExpr(tree, defn.AnnotationType)

  def typedAheadAnnotationClass(tree: Tree)(implicit ctx: Context): Symbol = tree match {
    case Apply(fn, _) => typedAheadAnnotationClass(fn)
    case TypeApply(fn, _) => typedAheadAnnotationClass(fn)
    case Select(qual, nme.CONSTRUCTOR) => typedAheadAnnotationClass(qual)
    case New(tpt) => typedAheadType(tpt).tpe.classSymbol
  }

  /** Enter and typecheck parameter list */
  def completeParams(params: List[MemberDef])(implicit ctx: Context) = {
    index(params)
    for (param <- params) typedAheadExpr(param)
  }

  /** The signature of a module valdef.
   *  This will compute the corresponding module class TypeRef immediately
   *  without going through the defined type of the ValDef. This is necessary
   *  to avoid cyclic references involving imports and module val defs.
   */
  def moduleValSig(sym: Symbol)(implicit ctx: Context): Type = {
    val clsName = sym.name.moduleClassName
    val cls = ctx.denotNamed(clsName).suchThat(_ is ModuleClass)
      .orElse(ctx.newStubSymbol(ctx.owner, clsName).assertingErrorsReported)
    ctx.owner.thisType.select(clsName, cls)
  }

  /** The type signature of a ValDef or DefDef
   *  @param mdef     The definition
   *  @param sym      Its symbol
   *  @param paramFn  A wrapping function that produces the type of the
   *                  defined symbol, given its final return type
   */
  def valOrDefDefSig(mdef: ValOrDefDef, sym: Symbol, typeParams: List[Symbol], paramss: List[List[Symbol]], paramFn: Type => Type)(implicit ctx: Context): Type = {

    def inferredType = {
      /** A type for this definition that might be inherited from elsewhere:
       *  If this is a setter parameter, the corresponding getter type.
       *  If this is a class member, the conjunction of all result types
       *  of overridden methods.
       *  NoType if neither case holds.
       */
      val inherited =
        if (sym.owner.isTerm) NoType
        else {
          // TODO: Look only at member of supertype instead?
          lazy val schema = paramFn(WildcardType)
          val site = sym.owner.thisType
          ((NoType: Type) /: sym.owner.info.baseClasses.tail) { (tp, cls) =>
            def instantiatedResType(info: Type, tparams: List[Symbol], paramss: List[List[Symbol]]): Type = info match {
              case info: PolyType =>
                if (info.paramNames.length == typeParams.length)
                  instantiatedResType(info.instantiate(tparams.map(_.typeRef)), Nil, paramss)
                else NoType
              case info: MethodType =>
                paramss match {
                  case params :: paramss1 if info.paramNames.length == params.length =>
                    instantiatedResType(info.instantiate(params.map(_.termRef)), tparams, paramss1)
                  case _ =>
                    NoType
                }
              case _ =>
                if (tparams.isEmpty && paramss.isEmpty) info.widenExpr
                else NoType
            }
            val iRawInfo =
              cls.info.nonPrivateDecl(sym.name).matchingDenotation(site, schema).info
            val iResType = instantiatedResType(iRawInfo, typeParams, paramss).asSeenFrom(site, cls)
            if (iResType.exists)
              typr.println(i"using inherited type for ${mdef.name}; raw: $iRawInfo, inherited: $iResType")
            tp & iResType
          }
        }

      /** The proto-type to be used when inferring the result type from
       *  the right hand side. This is `WildcardType` except if the definition
       *  is a default getter. In that case, the proto-type is the type of
       *  the corresponding parameter where bound parameters are replaced by
       *  Wildcards.
       */
      def rhsProto = sym.asTerm.name collect {
        case DefaultGetterName(original, idx) =>
          val meth: Denotation =
            if (original.isConstructorName && (sym.owner is ModuleClass))
              sym.owner.companionClass.info.decl(nme.CONSTRUCTOR)
            else
              ctx.defContext(sym).denotNamed(original)
          def paramProto(paramss: List[List[Type]], idx: Int): Type = paramss match {
            case params :: paramss1 =>
              if (idx < params.length) wildApprox(params(idx))
              else paramProto(paramss1, idx - params.length)
            case nil =>
              WildcardType
          }
          val defaultAlts = meth.altsWith(_.hasDefaultParams)
          if (defaultAlts.length == 1)
            paramProto(defaultAlts.head.info.widen.paramInfoss, idx)
          else
            WildcardType
      } getOrElse WildcardType

      // println(s"final inherited for $sym: ${inherited.toString}") !!!
      // println(s"owner = ${sym.owner}, decls = ${sym.owner.info.decls.show}")
      def isTransparentVal = sym.is(FinalOrTransparent, butNot = Method | Mutable)

      // Widen rhs type and eliminate `|' but keep ConstantTypes if
      // definition is inline (i.e. final in Scala2) and keep module singleton types
      // instead of widening to the underlying module class types.
      def widenRhs(tp: Type): Type = tp.widenTermRefExpr match {
        case ctp: ConstantType if isTransparentVal => ctp
        case ref: TypeRef if ref.symbol.is(ModuleClass) => tp
        case _ => tp.widen.widenUnion
      }

      // Replace aliases to Unit by Unit itself. If we leave the alias in
      // it would be erased to BoxedUnit.
      def dealiasIfUnit(tp: Type) = if (tp.isRef(defn.UnitClass)) defn.UnitType else tp

      var rhsCtx = ctx.addMode(Mode.InferringReturnType)
      if (sym.isInlineable) rhsCtx = rhsCtx.addMode(Mode.InlineableBody)
      def rhsType = typedAheadExpr(mdef.rhs, inherited orElse rhsProto)(rhsCtx).tpe

      // Approximate a type `tp` with a type that does not contain skolem types.
      val deskolemize = new ApproximatingTypeMap {
        def apply(tp: Type) = /*trace(i"deskolemize($tp) at $variance", show = true)*/ {
          tp match {
            case tp: SkolemType => range(defn.NothingType, atVariance(1)(apply(tp.info)))
            case _ => mapOver(tp)
          }
        }
      }

      def cookedRhsType = deskolemize(dealiasIfUnit(widenRhs(rhsType)))
      def lhsType = fullyDefinedType(cookedRhsType, "right-hand side", mdef.pos)
      //if (sym.name.toString == "y") println(i"rhs = $rhsType, cooked = $cookedRhsType")
      if (inherited.exists) {
        if (sym.is(Final, butNot = Method)) {
          val tp = lhsType
          if (tp.isInstanceOf[ConstantType])
            tp // keep constant types that fill in for a non-constant (to be revised when transparent has landed).
          else inherited
        }
        else inherited
      }
      else {
        if (sym is Implicit)
          mdef match {
            case _: DefDef => missingType(sym, "result ")
            case _: ValDef if sym.owner.isType => missingType(sym, "")
            case _ =>
          }
        lhsType orElse WildcardType
      }
    }

    val tptProto = mdef.tpt match {
      case _: untpd.DerivedTypeTree =>
        WildcardType
      case TypeTree() =>
        checkMembersOK(inferredType, mdef.pos)
      case DependentTypeTree(tpFun) =>
        tpFun(paramss.head)
      case TypedSplice(tpt: TypeTree) if !isFullyDefined(tpt.tpe, ForceDegree.none) =>
        val rhsType = typedAheadExpr(mdef.rhs, tpt.tpe).tpe
        mdef match {
          case mdef: DefDef if mdef.name == nme.ANON_FUN =>
            val hygienicType = avoid(rhsType, paramss.flatten)
            if (!hygienicType.isValueType || !(hygienicType <:< tpt.tpe))
              ctx.error(i"return type ${tpt.tpe} of lambda cannot be made hygienic;\n" +
                i"it is not a supertype of the hygienic type $hygienicType", mdef.pos)
            //println(i"lifting $rhsType over $paramss -> $hygienicType = ${tpt.tpe}")
            //println(TypeComparer.explained { implicit ctx => hygienicType <:< tpt.tpe })
          case _ =>
        }
        WildcardType
      case _ =>
        WildcardType
    }
    paramFn(checkSimpleKinded(typedAheadType(mdef.tpt, tptProto)).tpe)
  }

  /** The type signature of a DefDef with given symbol */
  def defDefSig(ddef: DefDef, sym: Symbol)(implicit ctx: Context) = {
    // Beware: ddef.name need not match sym.name if sym was freshened!
    val DefDef(_, tparams, vparamss, _, _) = ddef
    val isConstructor = sym.name == nme.CONSTRUCTOR

    // The following 3 lines replace what was previously just completeParams(tparams).
    // But that can cause bad bounds being computed, as witnessed by
    // tests/pos/paramcycle.scala. The problematic sequence is this:
    //   0. Class constructor gets completed.
    //   1. Type parameter CP of constructor gets completed
    //   2. As a first step CP's bounds are set to Nothing..Any.
    //   3. CP's real type bound demands the completion of corresponding type parameter DP
    //      of enclosing class.
    //   4. Type parameter DP has a rhs a DerivedFromParam tree, as installed by
    //      desugar.classDef
    //   5. The completion of DP then copies the current bounds of CP, which are still Nothing..Any.
    //   6. The completion of CP finishes installing the real type bounds.
    // Consequence: CP ends up with the wrong bounds!
    // To avoid this we always complete type parameters of a class before the type parameters
    // of the class constructor, but after having indexed the constructor parameters (because
    // indexing is needed to provide a symbol to copy for DP's completion.
    // With the patch, we get instead the following sequence:
    //   0. Class constructor gets completed.
    //   1. Class constructor parameter CP is indexed.
    //   2. Class parameter DP starts completion.
    //   3. Info of CP is computed (to be copied to DP).
    //   4. CP is completed.
    //   5. Info of CP is copied to DP and DP is completed.
    index(tparams)
    if (isConstructor) sym.owner.typeParams.foreach(_.ensureCompleted())
    for (tparam <- tparams) typedAheadExpr(tparam)

    vparamss foreach completeParams
    def typeParams = tparams map symbolOfTree
    val termParamss = ctx.normalizeIfConstructor(vparamss.nestedMap(symbolOfTree), isConstructor)
    def wrapMethType(restpe: Type): Type = {
      instantiateDependent(restpe, typeParams, termParamss)
      ctx.methodType(tparams map symbolOfTree, termParamss, restpe, isJava = ddef.mods is JavaDefined)
    }
    if (isConstructor) {
      // set result type tree to unit, but take the current class as result type of the symbol
      typedAheadType(ddef.tpt, defn.UnitType)
      wrapMethType(ctx.effectiveResultType(sym, typeParams, NoType))
    }
    else valOrDefDefSig(ddef, sym, typeParams, termParamss, wrapMethType)
  }

  def typeDefSig(tdef: TypeDef, sym: Symbol, tparamSyms: List[TypeSymbol])(implicit ctx: Context): Type = {
    def abstracted(tp: Type): Type = HKTypeLambda.fromParams(tparamSyms, tp)
    val dummyInfo = abstracted(TypeBounds.empty)
    sym.info = dummyInfo
    sym.setFlag(Provisional)
      // Temporarily set info of defined type T to ` >: Nothing <: Any.
      // This is done to avoid cyclic reference errors for F-bounds.
      // This is subtle: `sym` has now an empty TypeBounds, but is not automatically
      // made an abstract type. If it had been made an abstract type, it would count as an
      // abstract type of its enclosing class, which might make that class an invalid
      // prefix. I verified this would lead to an error when compiling io.ClassPath.
      // A distilled version is in pos/prefix.scala.
      //
      // The scheme critically relies on an implementation detail of isRef, which
      // inspects a TypeRef's info, instead of simply dealiasing alias types.

    val isDerived = tdef.rhs.isInstanceOf[untpd.DerivedTypeTree]
    val rhs = tdef.rhs match {
      case LambdaTypeTree(_, body) => body
      case rhs => rhs
    }
    val rhsBodyType = typedAheadType(rhs).tpe
    val rhsType = if (isDerived) rhsBodyType else abstracted(rhsBodyType)
    val unsafeInfo = rhsType.toBounds
    if (isDerived) sym.info = unsafeInfo
    else {
      sym.info = NoCompleter
      sym.info = checkNonCyclic(sym, unsafeInfo, reportErrors = true)
    }
    sym.resetFlag(Provisional)

    // Here we pay the price for the cavalier setting info to TypeBounds.empty above.
    // We need to compensate by reloading the denotation of references that might
    // still contain the TypeBounds.empty. If we do not do this, stdlib factories
    // fail with a bounds error in PostTyper.
    def ensureUpToDate(tp: Type, outdated: Type) = tp match {
      case tref: TypeRef if tref.info == outdated && sym.info != outdated =>
        tref.recomputeDenot()
      case _ =>
    }
    ensureUpToDate(sym.typeRef, dummyInfo)
    ensureUpToDate(sym.typeRef.appliedTo(tparamSyms.map(_.typeRef)), TypeBounds.empty)
    sym.info
  }
}
