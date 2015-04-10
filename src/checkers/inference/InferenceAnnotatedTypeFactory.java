package checkers.inference;

import checkers.inference.dataflow.InferenceAnalysis;
import checkers.inference.quals.VarAnnot;
import checkers.inference.util.CopyUtil;
import checkers.inference.util.InferenceUtil;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;

import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import org.checkerframework.common.basetype.BaseAnnotatedTypeFactory;
import org.checkerframework.framework.flow.CFAbstractAnalysis;
import org.checkerframework.framework.flow.CFAnalysis;
import org.checkerframework.framework.flow.CFStore;
import org.checkerframework.framework.flow.CFTransfer;
import org.checkerframework.framework.flow.CFValue;
import org.checkerframework.framework.qual.Unqualified;
import org.checkerframework.framework.type.*;
import org.checkerframework.framework.type.AnnotatedTypeMirror.*;
import org.checkerframework.framework.type.treeannotator.ImplicitsTreeAnnotator;
import org.checkerframework.framework.type.treeannotator.ListTreeAnnotator;
import org.checkerframework.framework.type.treeannotator.TreeAnnotator;
import org.checkerframework.framework.type.visitor.AnnotatedTypeScanner;
import org.checkerframework.framework.type.visitor.SimpleAnnotatedTypeScanner;
import org.checkerframework.framework.util.AnnotatedTypes;
import org.checkerframework.framework.util.MultiGraphQualifierHierarchy;
import org.checkerframework.javacutil.ErrorReporter;
import org.checkerframework.javacutil.Pair;
import org.checkerframework.javacutil.TreeUtils;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;

/**
 * InferenceAnnotatedTypeFactory is responsible for creating AnnotatedTypeMirrors that are annotated with
 * "variable" or "real" annotations.  Variable annotations, represented by checkers.inference.@VarAnnot, indicate
 * an annotation with a value to be inferred.  Real annotations, those found in the hierarchy of the type system
 * for which we are inferring values, indicate that the given type has a constant value in the "real" type system.
 *
 * Adding annotations is accomplished through three means:
 * 1.  If we have the source code for a particular type, the InferenceTreeAnnotator and the VariableAnnotator
 * will add the relevant annotation (either VarAnnot or constant real annotation) based on the type's corresponding
 * tree and the rules of the InferrableChecker.  If the InferrableChecker determines that a value is constant
 * then the realAnnotatedTypeFactory is consulted to get this value.
 * @see checkers.inference.InferenceAnnotatedTypeFactory#annotateImplicit(com.sun.source.tree.Tree, checkers.types.AnnotatedTypeMirror)
 *
 * 2.  If we do NOT have the source code then the realAnnotatedTypeFactory is used to determine a constant value
 * to place on the given "library", i.e. from bytecode, type.
 * @see checkers.inference.InferenceAnnotatedTypeFactory#annotateImplicit(javax.lang.model.element.Element, checkers.types.AnnotatedTypeMirror)
 *
 * 3.  Types representing declarations generated using methods 1 and 2 are stored via
 * VariableAnnotator#storeElementType.  If these elements are encountered again, the annotations from the stored
 * type are copied to the annotations of the type being annotated.
 * @see checkers.inference.InferenceAnnotatedTypeFactory#annotateImplicit(javax.lang.model.element.Element, checkers.types.AnnotatedTypeMirror)
 *
 * Note: a number of constraints are created by members of this class
 * @see checkers.inference.InferenceQualifierHierarchy
 * @see checkers.inference.InferenceTypeHierarchy
 *
 * Finally, Variables are created while flow is being performed (every time getAnnotatedType is called on a
 * class tree that hasn't yet been annotated).  Constraints are created after flow has been for a given class when
 * the visitor requests types.
 */
public class InferenceAnnotatedTypeFactory extends BaseAnnotatedTypeFactory {

    private final boolean withCombineConstraints;
    private final VariableAnnotator variableAnnotator;
    private final BaseAnnotatedTypeFactory realTypeFactory;

    private final InferrableChecker realChecker;
    private final InferenceChecker inferenceChecker;
    private final SlotManager slotManager;
    private final ConstraintManager constraintManager;

    public InferenceAnnotatedTypeFactory(
            InferenceChecker inferenceChecker,
            boolean withCombineConstraints,
            BaseAnnotatedTypeFactory realTypeFactory,
            InferrableChecker realChecker,
            SlotManager slotManager,
            ConstraintManager constraintManager) {

        super(inferenceChecker, true);

        this.withCombineConstraints = withCombineConstraints;
        this.realTypeFactory = realTypeFactory;
        this.inferenceChecker = inferenceChecker;
        this.realChecker = realChecker;
        this.slotManager = slotManager;
        this.constraintManager = constraintManager;
        variableAnnotator = new VariableAnnotator(this, realTypeFactory, realChecker, slotManager, constraintManager);

        postInit();
    }

    @Override
    protected CFAnalysis createFlowAnalysis(List<Pair<VariableElement, CFValue>> fieldValues) {
        return realChecker.createInferenceAnalysis(inferenceChecker, this, fieldValues, slotManager, constraintManager, realChecker);
    }

    @Override
    public CFTransfer createFlowTransferFunction(CFAbstractAnalysis<CFValue, CFStore, CFTransfer> analysis) {
        return realChecker.createInferenceTransferFunction((InferenceAnalysis) analysis);
    }

    @Override
    public TreeAnnotator createTreeAnnotator() {
        return new ListTreeAnnotator(
                new ImplicitsTreeAnnotator(this),
                new InferenceTreeAnnotator(this, realChecker, realTypeFactory, variableAnnotator, slotManager)
        );
    }

    @Override
    protected TypeHierarchy createTypeHierarchy() {
        return new InferenceTypeHierarchy(checker, getQualifierHierarchy());
    }

    @Override
    protected MultiGraphQualifierHierarchy.MultiGraphFactory createQualifierHierarchyFactory() {
        return new MultiGraphQualifierHierarchy.MultiGraphFactory(this);
    }

    @Override
    public QualifierHierarchy createQualifierHierarchy( MultiGraphQualifierHierarchy.MultiGraphFactory factory ) {
        return new InferenceQualifierHierarchy(factory);
    }

    @Override
    @SuppressWarnings( "deprecation" ) //for getRealTypeFactory
    protected Set<Class<? extends Annotation>> createSupportedTypeQualifiers() {
        final Set<Class<? extends Annotation>> typeQualifiers = new HashSet<>();

        typeQualifiers.add(Unqualified.class);
        typeQualifiers.add(VarAnnot.class);

        typeQualifiers.addAll(InferenceMain.getInstance().getRealTypeFactory().getSupportedTypeQualifiers());
        return Collections.unmodifiableSet(typeQualifiers);
    }

    /**
     *  Copies the primary annotations on the use type "type" onto each "supertype".
     *  E.g. for a call:
     *      postDirectSuperTypes( @9 ArrayList< @7 String>, List( @4 List<@7 String>, @0 Object ) )
     *  we would like supertypes to become:
     *      List( @9 List<@7 String>, @9 Object )
     *
     * This does NOTHING to the type parameters of a declared type.  The superTypeFinder should appropriately
     * fix these up.
     */
    @Override
    protected void postDirectSuperTypes(AnnotatedTypeMirror type, List<? extends AnnotatedTypeMirror> supertypes) {

        //TODO: Move postdirectSupertypes to a "copyTypeToSuperType method" that can just be called by this method?
        //At the time of writing this is the same as AnnotatedTypeFactory.postDirectSuperTypes
        //we cannot call super.postDirectSuperTypes because GenericAnnotatedTypeFactory will cause
        //annotateImplicit(element,type) to be called on the supertype which will overwrite the annotations from type
        //with those for the declaration of the super type
        Set<AnnotationMirror> annotations = type.getEffectiveAnnotations();
        for (AnnotatedTypeMirror supertype : supertypes) {
            if (!annotations.equals(supertype.getEffectiveAnnotations())) {
                supertype.clearAnnotations();
                supertype.addAnnotations(annotations);
            }
        }
    }

    /**
     * We do not want annotations inherited from superclass, we would like to infer all positions.
     */
    @Override
    protected void annotateInheritedFromClass(AnnotatedTypeMirror type,
            Set<AnnotationMirror> fromClass) { }


    @Override
    public void postAsMemberOf(final AnnotatedTypeMirror type,
                               final AnnotatedTypeMirror owner, final Element element) {
        final TypeKind typeKind = type.getKind();
        if(typeKind != TypeKind.DECLARED && typeKind != TypeKind.ARRAY) {
            return;
        }

        final ElementKind elementKind = element.getKind();
        if(elementKind == ElementKind.LOCAL_VARIABLE || elementKind == ElementKind.PARAMETER) {
            return;
        }

        //TODO: Look at old implementation and add combine constraints
    }

    /**
     * TODO: The implementation in AnnotatedTypeFactory essentially replaces the parameterized bounds with concrete bounds
     * TODO: (e.g. <T extends @Nullable Object, E extends T> => <T extends @Nullable Object, E extends @Nullable Object>
     * TODO: TO CORRECTLY MODEL THE RESULTING CONSTRAINTS WOULD WE NOT DO THE SAME THING?  OR CREATE A COMBVAR
     * TODO: FOR THAT LOCATION?
     */
    @Override
    public List<AnnotatedTypeParameterBounds> typeVariablesFromUse(final AnnotatedDeclaredType useType, final TypeElement element ) {
        //The type of the class in which the type params were declared
        final AnnotatedDeclaredType ownerOfTypeParams = getAnnotatedType(element);
        final List<AnnotatedTypeMirror> declaredTypeParameters = ownerOfTypeParams.getTypeArguments();

        final List<AnnotatedTypeParameterBounds> result = new ArrayList<>();

        for( int i = 0; i < declaredTypeParameters.size(); i++ ) {
            final AnnotatedTypeVariable declaredTypeParam = (AnnotatedTypeVariable) declaredTypeParameters.get(i);
            result.add(new AnnotatedTypeParameterBounds(declaredTypeParam.getUpperBound(), declaredTypeParam.getLowerBound()));

            //TODO: Original InferenceAnnotatedTypeFactory#typeVariablesFromUse would create a combine constraint
            //TODO: between the useType and the effectiveUpperBound of the declaredTypeParameter
            //TODO: and then copy the annotations from the type with the CombVars to the declared type
        }

        return result;
    }

    /**
     * @see org.checkerframework.checker.type.AnnotatedTypeFactory#methodFromUse(com.sun.source.tree.MethodInvocationTree)
     * TODO: This is essentially the default implementation of AnnotatedTypeFactory.methodFromUse with a space to later
     * TODO: add comb constraints.  One difference is how the receiver is gotten.  Perhaps we should just
     * TODO: change getSelfType?  But I am not sure where getSelfType is used yet
     * @param methodInvocationTree
     * @return
     */
    @Override
    public Pair<AnnotatedExecutableType, List<AnnotatedTypeMirror>> methodFromUse(final MethodInvocationTree methodInvocationTree) {
        assert methodInvocationTree != null : "MethodInvocationTree in methodFromUse was null.  " +
                                              "Current path:\n" + this.visitorState.getPath();
        final ExecutableElement methodElem = TreeUtils.elementFromUse(methodInvocationTree);

        //TODO: Used in comb constraints, going to leave it in to ensure the element has been visited
        final AnnotatedExecutableType methodType = getAnnotatedType(methodElem);

        final ExpressionTree methodSelectExpression = methodInvocationTree.getMethodSelect();
        final AnnotatedTypeMirror receiverType;
        if (methodSelectExpression.getKind() == Tree.Kind.MEMBER_SELECT) {
            receiverType = getAnnotatedType(((MemberSelectTree) methodSelectExpression).getExpression());
        } else {
            receiverType = getSelfType(methodInvocationTree);
        }

        assert receiverType != null : "Null receiver type when getting method from use for tree ( " + methodInvocationTree + " )";

        //TODO: Add CombConstraints for method parameter types as well as return types

        //TODO: Is the asMemberOf correct, was not in Werner's original implementation but I had added it
        //TODO: It is also what the AnnotatedTypeFactory default implementation does
        final AnnotatedExecutableType methodOfReceiver = AnnotatedTypes.asMemberOf(types, this, receiverType, methodElem);
        Pair<AnnotatedExecutableType, List<AnnotatedTypeMirror>> mfuPair = substituteTypeArgs(methodInvocationTree, methodElem, methodOfReceiver);

        //TODO: poly seems to discover all of the types that we have missed annotating
        if (InferenceMain.isHackMode()) {
            if (checkForUnannotatedTypes(methodInvocationTree, methodType)) {
                return mfuPair;
            }
        }
        AnnotatedExecutableType method = mfuPair.first;
        poly.annotate(methodInvocationTree, method);

        return mfuPair;
    }

    private boolean checkForUnannotatedTypes(MethodInvocationTree methodInvocationTree,
            AnnotatedExecutableType methodType) {
        List<AnnotatedTypeMirror> requiredArgs = AnnotatedTypes.expandVarArgs(this, methodType, methodInvocationTree.getArguments());
        List<AnnotatedTypeMirror> arguments = AnnotatedTypes.getAnnotatedTypes(this, requiredArgs, methodInvocationTree.getArguments());
        for (AnnotatedTypeMirror arg : arguments) {
            if (Boolean.FALSE == fullyQualifiedVisitor.visit(arg)) {
                return true;
            }
        }
        if (Boolean.FALSE == fullyQualifiedVisitor.visit(getReceiverType(methodInvocationTree))) {
            return true;
        }
        return false;
    }

    private AnnotatedTypeScanner fullyQualifiedVisitor = new AnnotatedTypeScanner<Boolean, Void>() {
        @Override
        public Boolean visitDeclared(AnnotatedDeclaredType type, Void p) {
            if (type.getAnnotations().size() == 0) {
                return false;
            }
            return super.visitDeclared(type, p);
        }
    };

    /**
     * TODO: Similar but not the same as AnnotatedTypeFactory.constructorFromUse with space set aside from
     * TODO: comb constraints, track down the differences with constructorFromUse
     * Note: super() and this() calls
     * @see org.checkerframework.checker.type.AnnotatedTypeFactory#constructorFromUse(com.sun.source.tree.NewClassTree)
     *
     * @param newClassTree
     * @return
     */
    @Override
    public Pair<AnnotatedExecutableType, List<AnnotatedTypeMirror>> constructorFromUse(final NewClassTree newClassTree) {
        assert newClassTree != null : "NewClassTree was null when attempting to get constructorFromUse. " +
                                      "Current path:\n" + this.visitorState.getPath();

        final ExecutableElement constructorElem = TreeUtils.elementFromUse(newClassTree);
        final AnnotatedTypeMirror constructorReturnType = fromNewClass(newClassTree);
        final AnnotatedExecutableType constructorType = AnnotatedTypes.asMemberOf(types, this, constructorReturnType, constructorElem);

        Pair<AnnotatedExecutableType, List<AnnotatedTypeMirror>> substitutedPair = substituteTypeArgs(newClassTree, constructorElem, constructorType);
        poly.annotate(newClassTree, substitutedPair.first);

        //TODO: ADD CombConstraints
        //TODO: Should we be doing asMemberOf like super?
        return substitutedPair;
    }

    /**
     * Get a map of the type arguments for any type variables in tree.  Create a list of type arguments by
     * replacing each type parameter of exeEle by it's corresponding argument in the map.  Substitute the
     * type parameters in exeType with those in the type arg map.
     *
     * @param expressionTree Tree representing the method or constructor we are analyzing
     * @param methodElement The element corresponding with tree
     * @param methodType The type as determined by this class of exeEle
     * @return A list of the actual type arguments for the type parameters of exeEle and exeType with it's type
     *         parameters replaced by the actual type arguments
     */
    private <EXP_TREE extends ExpressionTree> Pair<AnnotatedExecutableType, List<AnnotatedTypeMirror>> substituteTypeArgs(
            EXP_TREE expressionTree, final ExecutableElement methodElement, final AnnotatedExecutableType methodType) {

        // determine substitution for method type variables
        final Map<TypeVariable, AnnotatedTypeMirror> typeVarMapping =
                AnnotatedTypes.findTypeArguments(processingEnv, this, expressionTree, methodElement, methodType);

        if( typeVarMapping.isEmpty() ) {
            return Pair.<AnnotatedExecutableType, List<AnnotatedTypeMirror>>of(methodType, new LinkedList<AnnotatedTypeMirror>());
        } //else

        // We take the type variables from the method element, not from the annotated method.
        // For some reason, this way works, the other one doesn't.  //TODO: IS THAT TRUE?
        final List<TypeVariable> foundTypeVars   = new LinkedList<>();
        final List<TypeVariable> missingTypeVars = new LinkedList<>();

        for ( final TypeParameterElement typeParamElem : methodElement.getTypeParameters() ) {
            final TypeVariable typeParam = (TypeVariable)typeParamElem.asType();
            if (typeVarMapping.containsKey(typeParam)) {
                foundTypeVars.add(typeParam);
            } else {
                missingTypeVars.add(typeParam);
            }
        }

        if (!InferenceMain.isHackMode()) {
            //This used to be a println
            assert missingTypeVars.isEmpty() : "InferenceAnnotatedTypeFactory.methodFromUse did not find a mapping for " +
                    "the following type params:\n" + InferenceUtil.join(missingTypeVars, "\n") +
                    "in the inferred type arguments: " + InferenceUtil.join(typeVarMapping);
        } else {
            if (! missingTypeVars.isEmpty()) {
                InferenceMain.getInstance().logger.warning("Hack:InferenceAnnotatedTypeFactory:348");
            }
        }

        final List<AnnotatedTypeMirror> actualTypeArgs = new ArrayList<>(foundTypeVars.size());
        for (final TypeVariable found : foundTypeVars) {
            actualTypeArgs.add(typeVarMapping.get(found));
        }

        final AnnotatedExecutableType actualExeType = (AnnotatedExecutableType)typeVarSubstitutor.substitute(typeVarMapping, methodType);

        return Pair.of(actualExeType, actualTypeArgs);
    }


    @Override
    protected void performFlowAnalysis(final ClassTree classTree) {
        final InferenceMain inferenceMain = InferenceMain.getInstance();
        inferenceMain.setPerformingFlow(true);
        super.performFlowAnalysis(classTree);
        inferenceMain.setPerformingFlow(false);
    }

    /**
     * We override this to remove the extra isSubtype check when applying inferred annotations.
     * (so we don't get those extra isSubtype constraints).
     */
    @Override
    protected void applyInferredAnnotations(org.checkerframework.framework.type.AnnotatedTypeMirror type, CFValue as) {
        //TODO JB: Is this behavior different from what occured in inference?
        new DefaultInferredTypesApplier(true).applyInferredType(getQualifierHierarchy(), type, as.getType());
    }

    /**
     * This is the same as the super method, but we do not want to use defaults or typeAnnotator.
     */
    @Override
    protected void annotateImplicit(Tree tree, AnnotatedTypeMirror type, boolean iUseFlow) {
        assert root != null : "GenericAnnotatedTypeFactory.annotateImplicit: " +
                " root needs to be set when used on trees; factory: " + this.getClass();

        if (iUseFlow) {
            /**
             * We perform flow analysis on each {@link ClassTree} that is
             * passed to annotateImplicitWithFlow.  This works correctly when
             * a {@link ClassTree} is passed to this method before any of its
             * sub-trees.  It also helps to satisfy the requirement that a
             * {@link ClassTree} has been advanced to annotation before we
             * analyze it.
             */
            checkAndPerformFlowAnalysis(tree);
        }

        treeAnnotator.visit(tree, type);
        //typeAnnotator.visit(type, null);
        //defaults.annotate(tree, type);

        if (iUseFlow) {
            CFValue as = getInferredValueFor(tree);
            if (as != null) {
                applyInferredAnnotations(type, as);
            }
        }
    }

    //TODO: I don't think this method is needed, but we should verify this.
//    @Override
//    public AnnotatedTypeMirror getAnnotatedTypeFromTypeTree(final Tree tree) {
//
//        if (inferenceChecker.extImplsTreeCache.contains(tree)) {
//            inferenceChecker.extImplsTreeCache(tree)
//
//        } else {
//            super.getAnnotatedTypeFromTypeTree(tree)
//
//        }
//    }

    /**
     * TODO: Expand
     * If we have a cached AnnotatedTypeMirror for the element then copy its annotations to type
     * else if we can get the source tree for the declaration of that element visit it with the tree annotator
     * else get the AnnotatedTypeMirror from the real AnnotatedTypeFactory and copy its annotations to type
     * @param element The element to annotate
     * @param type The AnnotatedTypeMirror corresponding to element
     * */
    @Override
    public void annotateImplicit(final Element element, final AnnotatedTypeMirror type) {
        if (!variableAnnotator.annotateElementFromStore(element, type)) {

            Tree declaration = declarationFromElement(element);
            if (declaration == null) {
                // TODO: Why is the tree in the cache null
                boolean prev = this.shouldReadCache;
                this.shouldReadCache = false;
                declaration = declarationFromElement(element);
                this.shouldReadCache = prev;
            }

            if (declaration != null) {
                treeAnnotator.visit(declaration, type);
            } else {
                final AnnotatedTypeMirror realType = realTypeFactory.getAnnotatedType(element);
                CopyUtil.copyAnnotations(realType, type);
            }
        }
    }

    @Override
    public void setRoot(final CompilationUnitTree root) {
        //TODO: THERE MAY BE STORES WE WANT TO CLEAR, PERHAPS ELEMENTS FOR LOCAL VARIABLES
        //TODO: IN THE PREVIOUS COMPILATION UNIT IN VARIABLE ANNOTATOR
        this.realTypeFactory.setRoot( root );
        super.setRoot( root );
    }
}
