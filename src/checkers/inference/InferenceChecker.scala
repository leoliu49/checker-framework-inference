package checkers.inference

import com.sun.source.tree.Tree
import checkers.source.SourceVisitor
import com.sun.source.tree.CompilationUnitTree
import checkers.basetype.BaseTypeVisitor
import javax.lang.model.element.TypeParameterElement
import checkers.quals.SubtypeOf
import checkers.util.MultiGraphQualifierHierarchy
import checkers.types.TypeHierarchy
import javax.lang.model.element.Name
import checkers.types.QualifierHierarchy
import java.util.HashMap
import javax.lang.model.element.VariableElement
import javax.lang.model.element.ExecutableElement
import javax.lang.model.`type`.TypeKind
import com.sun.source.tree.ClassTree
import com.sun.source.tree.Tree.Kind
import com.sun.source.util.TreePath
import javax.lang.model.element.TypeElement
import javax.lang.model.element.AnnotationMirror
import checkers.util.AnnotationUtils
import checkers.types.AnnotatedTypeMirror.AnnotatedNullType
import java.util.Collections
import java.util.HashSet
import javax.lang.model.element.AnnotationValue
import checkers.types.AnnotatedTypeMirror
import checkers.types.AnnotatedTypeMirror.AnnotatedArrayType
import checkers.types.AnnotatedTypeMirror.AnnotatedDeclaredType
import checkers.types.AnnotatedTypeMirror.AnnotatedExecutableType
import checkers.types.AnnotatedTypeMirror.AnnotatedPrimitiveType
import checkers.types.AnnotatedTypeMirror.AnnotatedWildcardType
import checkers.types.AnnotatedTypeMirror.AnnotatedTypeVariable
import checkers.quals.TypeQualifiers
import checkers.quals.Unqualified
import checkers.basetype.BaseTypeChecker
import javax.annotation.processing.ProcessingEnvironment
import checkers.inference.quals.VarAnnot
import checkers.inference.quals.CombVarAnnot
import checkers.inference.quals.LiteralAnnot
import checkers.types.AnnotatedTypeFactory

class InferenceChecker extends BaseTypeChecker {
  // TODO: can we make this a trait and mix it with the main checker?

  // map from fully qualified annotation name to the corresponding AnnotationMirror
  val REAL_QUALIFIERS: java.util.Map[String, AnnotationMirror] = new HashMap[String, AnnotationMirror]()
  var VAR_ANNOT: AnnotationMirror = null
  var COMBVAR_ANNOT: AnnotationMirror = null
  var LITERAL_ANNOT: AnnotationMirror = null

  /*
  override def typeProcess(e: TypeElement, p: TreePath) {
	  super.typeProcess(e, p)
	  println("typeprocessed: " + currentRoot)
  }
  */

  override def initChecker(env: ProcessingEnvironment): Unit = {
    InferenceMain.init(this)
    super.initChecker(env)

    val tquals = InferenceMain.getRealChecker.getSupportedTypeQualifiers()
    val annoFactory: AnnotationUtils = AnnotationUtils.getInstance(env)

    import scala.collection.JavaConversions._
    for (tq <- tquals) {
      REAL_QUALIFIERS.put(tq.getName, annoFactory.fromClass(tq))
    }

    VAR_ANNOT = annoFactory.fromClass(classOf[VarAnnot])
    COMBVAR_ANNOT = annoFactory.fromClass(classOf[CombVarAnnot])
    LITERAL_ANNOT = annoFactory.fromClass(classOf[LiteralAnnot])
  }

  def cleanUp() {
    varElemCache.clear
    exeElemCache.clear
    typeparamElemCache.clear
    typeElemCache.clear
  }

  override protected def createSourceVisitor(root: CompilationUnitTree): SourceVisitor[_, _] = {
    InferenceMain.createRealVisitor(root)
  }

  /* Maybe we want to (also) use this to add the qualifiers from the "main" checker. */
  override protected def createSupportedTypeQualifiers(): java.util.Set[Class[_ <: java.lang.annotation.Annotation]] = {
    val typeQualifiers = new HashSet[Class[_ <: java.lang.annotation.Annotation]]();

    typeQualifiers.add(classOf[Unqualified])
    typeQualifiers.add(classOf[VarAnnot])
    typeQualifiers.add(classOf[CombVarAnnot])
    typeQualifiers.add(classOf[LiteralAnnot])

    typeQualifiers.addAll(InferenceMain.getRealChecker.getSupportedTypeQualifiers())

    // println("modifiers: " + Collections.unmodifiableSet(typeQualifiers))
    Collections.unmodifiableSet(typeQualifiers)
  }

  override def createFactory(root: CompilationUnitTree): AnnotatedTypeFactory = {
    new InferenceAnnotatedTypeFactory(this, root, InferenceMain.getRealChecker.withCombineConstraints)
  }

  override def isValidUse(declarationType: AnnotatedDeclaredType,
    useType: AnnotatedDeclaredType): Boolean = {
    // TODO at least for the UTS we don't check annotations on the class declaration
    //   println("InferenceChecker::isValidUse: decl: " + declarationType)
    //   println("InferenceChecker::isValidUse: use: " + useType)
    true
  }

  // Make the processing environment available to other parts
  def getProcessingEnv = processingEnv

  override protected def createTypeHierarchy(): TypeHierarchy = {
    new InferenceTypeHierarchy(this, getQualifierHierarchy());
  }

  private class InferenceTypeHierarchy(checker: BaseTypeChecker, qh: QualifierHierarchy)
      extends TypeHierarchy(checker, qh) {
    // copied from super, also allow type arguments with different qualifiers and create equality constraints
    override protected def isSubtypeAsTypeArgument(rhs: AnnotatedTypeMirror, lhs: AnnotatedTypeMirror): Boolean = {
      if (lhs.getKind() == TypeKind.WILDCARD && rhs.getKind() != TypeKind.WILDCARD) {
        if (visited.contains(lhs.getElement()))
          return true

        visited.add(lhs.getElement())
        val wclhs = lhs.asInstanceOf[AnnotatedWildcardType].getExtendsBound()
        if (wclhs == null)
          return true
        return isSubtypeImpl(rhs, wclhs)
      }

      if (lhs.getKind() == TypeKind.WILDCARD && rhs.getKind() == TypeKind.WILDCARD) {
        return isSubtype(rhs.asInstanceOf[AnnotatedWildcardType].getExtendsBound(),
          lhs.asInstanceOf[AnnotatedWildcardType].getExtendsBound())
      }

      if (lhs.getKind() == TypeKind.TYPEVAR && rhs.getKind() != TypeKind.TYPEVAR) {
        if (visited.contains(lhs.getElement()))
          return true
        visited.add(lhs.getElement())
        return isSubtype(rhs, lhs.asInstanceOf[AnnotatedTypeVariable].getUpperBound())
      }

      val lannoset = lhs.getAnnotations()
      val rannoset = rhs.getAnnotations()

      // TODO: improve handling of raw types
      // println("lhs: " + lhs)
      // println("rhs: " + rhs)

      // if (!AnnotationUtils.areSame(lannoset, rannoset))
      //  return false
      if (lannoset.size == rannoset.size) {

        if (lannoset.size != 1) {
          // TODO: maybe different for other type systems...
          return true
        }

        if (InferenceMain.DEBUG(this)) {
          println("InferenceTypeHierarchy::isSubtypeAsTypeArgument: Equality constraint for type argument.")
        }
        InferenceMain.constraintMgr.addEqualityConstraint(lannoset.iterator().next(), rannoset.iterator().next())

        if (lhs.getKind() == TypeKind.DECLARED && rhs.getKind() == TypeKind.DECLARED)
          return isSubtypeTypeArguments(rhs.asInstanceOf[AnnotatedDeclaredType], lhs.asInstanceOf[AnnotatedDeclaredType])
        else if (lhs.getKind() == TypeKind.ARRAY && rhs.getKind() == TypeKind.ARRAY) {
          // arrays components within type arguments are invariants too
          // List<String[]> is not a subtype of List<Object[]>
          val rhsComponent = rhs.asInstanceOf[AnnotatedArrayType].getComponentType()
          val lhsComponent = lhs.asInstanceOf[AnnotatedArrayType].getComponentType()
          return isSubtypeAsTypeArgument(rhsComponent, lhsComponent)
        }
      } else {
        // TODO: this case happens with raw types, were no annotations were added to the non-existent arguments.
        // Think about this.
      }
      return true
    }
  }

  override protected def createQualifierHierarchyFactory(): MultiGraphQualifierHierarchy.MultiGraphFactory = {
          new MultiGraphQualifierHierarchy.MultiGraphFactory(this);
  }

  override protected def createQualifierHierarchy(): QualifierHierarchy = {
    new InferenceQualifierHierarchy(super.createQualifierHierarchy().asInstanceOf[MultiGraphQualifierHierarchy])
  }

  private class InferenceQualifierHierarchy(h: MultiGraphQualifierHierarchy) extends MultiGraphQualifierHierarchy(h) {
    override def isSubtype(rhs: java.util.Collection[AnnotationMirror], lhs: java.util.Collection[AnnotationMirror]): Boolean = {
      if (rhs.isEmpty || lhs.isEmpty || (lhs.size()!=rhs.size())) {
        // TODO: make behavior in superclass easier to adapt.
        true
      } else {
        super.isSubtype(rhs, lhs)
      }
    }

    override def isSubtype(sub: AnnotationMirror, sup: AnnotationMirror): Boolean = {

      if (InferenceMain.DEBUG(this)) {
        println("InferenceQualifierHierarchy::isSubtype: Subtype constraint for qualifiers sub: " + sub + " sup: " + sup)
      }
      InferenceMain.constraintMgr.addSubtypeConstraint(sub, sup)

      true
    }

    override def leastUpperBound(a1: AnnotationMirror, a2: AnnotationMirror): AnnotationMirror = {
      if (InferenceMain.DEBUG(this)) {
        println("InferenceQualifierHierarchy::leastUpperBound(" + a1 + ", " + a2 + ")")
      }

      if (a1 == null) { return a2 }
      if (a2 == null) { return a1 }

      val res = InferenceMain.slotMgr.createCombVariable

      if (InferenceMain.DEBUG(this)) {
        println("InferenceQualifierHierarchy::leastUpperBound: Two subtype constraints for qualifiers.")
      }

      val c1 = InferenceMain.constraintMgr.addSubtypeConstraint(a1, res.getAnnotation)
      val c2 = InferenceMain.constraintMgr.addSubtypeConstraint(a2, res.getAnnotation)

      res.getAnnotation
    }
  }

  /*
  // just for debugging
  override def isSubtype(sub: AnnotatedTypeMirror,
    sup: AnnotatedTypeMirror): Boolean = {
    println("InferenceChecker::isSubtype: sub: " + sub + " and " + sup)
    super.isSubtype(sub, sup)
  }
  */

  val varElemCache = new scala.collection.mutable.HashMap[VariableElement, AnnotatedTypeMirror]()
  val exeElemCache = new scala.collection.mutable.HashMap[ExecutableElement, AnnotatedExecutableType]()
  val typeparamElemCache = new scala.collection.mutable.HashMap[TypeParameterElement, AnnotatedTypeVariable]()
  val typeElemCache = new scala.collection.mutable.HashMap[TypeElement, AnnotatedTypeMirror]()

  val extImplsTreeCache = new scala.collection.mutable.HashMap[Tree, AnnotatedTypeMirror]()
}