/*
 * Copyright (C) 2015 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.internal.codegen;

import static com.google.common.base.CaseFormat.LOWER_CAMEL;
import static com.google.common.base.CaseFormat.UPPER_CAMEL;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.squareup.javapoet.MethodSpec.constructorBuilder;
import static com.squareup.javapoet.MethodSpec.methodBuilder;
import static com.squareup.javapoet.TypeSpec.anonymousClassBuilder;
import static com.squareup.javapoet.TypeSpec.classBuilder;
import static dagger.internal.codegen.AnnotationSpecs.Suppression.RAWTYPES;
import static dagger.internal.codegen.AnnotationSpecs.Suppression.UNCHECKED;
import static dagger.internal.codegen.BindingExpression.InitializationState.DELEGATED;
import static dagger.internal.codegen.BindingExpression.InitializationState.INITIALIZED;
import static dagger.internal.codegen.BindingExpression.InitializationState.UNINITIALIZED;
import static dagger.internal.codegen.BindingKey.contribution;
import static dagger.internal.codegen.CodeBlocks.makeParametersCodeBlock;
import static dagger.internal.codegen.ContributionBinding.FactoryCreationStrategy.SINGLETON_INSTANCE;
import static dagger.internal.codegen.ContributionBinding.Kind.INJECTION;
import static dagger.internal.codegen.ErrorMessages.CANNOT_RETURN_NULL_FROM_NON_NULLABLE_COMPONENT_METHOD;
import static dagger.internal.codegen.MapKeys.getMapKeyExpression;
import static dagger.internal.codegen.MemberSelect.localField;
import static dagger.internal.codegen.MoreAnnotationMirrors.getTypeValue;
import static dagger.internal.codegen.Scope.reusableScope;
import static dagger.internal.codegen.SourceFiles.frameworkMapFactoryClassName;
import static dagger.internal.codegen.SourceFiles.generatedClassNameForBinding;
import static dagger.internal.codegen.SourceFiles.mapFactoryClassName;
import static dagger.internal.codegen.SourceFiles.membersInjectorNameForType;
import static dagger.internal.codegen.SourceFiles.setFactoryClassName;
import static dagger.internal.codegen.SourceFiles.simpleVariableName;
import static dagger.internal.codegen.TypeNames.DELEGATE_FACTORY;
import static dagger.internal.codegen.TypeNames.DOUBLE_CHECK;
import static dagger.internal.codegen.TypeNames.INSTANCE_FACTORY;
import static dagger.internal.codegen.TypeNames.LISTENABLE_FUTURE;
import static dagger.internal.codegen.TypeNames.MEMBERS_INJECTORS;
import static dagger.internal.codegen.TypeNames.PRODUCER;
import static dagger.internal.codegen.TypeNames.REFERENCE_RELEASING_PROVIDER;
import static dagger.internal.codegen.TypeNames.REFERENCE_RELEASING_PROVIDER_MANAGER;
import static dagger.internal.codegen.TypeNames.SINGLE_CHECK;
import static dagger.internal.codegen.TypeNames.TYPED_RELEASABLE_REFERENCE_MANAGER_DECORATOR;
import static dagger.internal.codegen.TypeNames.providerOf;
import static dagger.internal.codegen.Util.toImmutableList;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;
import static javax.lang.model.type.TypeKind.VOID;

import com.google.auto.common.MoreElements;
import com.google.auto.common.MoreTypes;
import com.google.common.base.Joiner;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import dagger.internal.InstanceFactory;
import dagger.internal.Preconditions;
import dagger.internal.TypedReleasableReferenceManagerDecorator;
import dagger.internal.codegen.ComponentDescriptor.ComponentMethodDescriptor;
import dagger.producers.Produced;
import dagger.producers.Producer;
import dagger.releasablereferences.CanReleaseReferences;
import dagger.releasablereferences.ForReleasableReferences;
import dagger.releasablereferences.ReleasableReferenceManager;
import dagger.releasablereferences.TypedReleasableReferenceManager;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.inject.Provider;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

/** Creates the implementation class for a component or subcomponent. */
abstract class AbstractComponentWriter implements HasBindingExpressions {
  // TODO(dpb): Make all these fields private after refactoring is complete.
  protected final Elements elements;
  protected final Types types;
  protected final Key.Factory keyFactory;
  protected final CompilerOptions compilerOptions;
  protected final ClassName name;
  protected final BindingGraph graph;
  protected final ImmutableMap<ComponentDescriptor, String> subcomponentNames;
  protected final TypeSpec.Builder component;
  private final UniqueNameSet componentFieldNames = new UniqueNameSet();
  private final Map<BindingKey, BindingExpression> bindingExpressions = new LinkedHashMap<>();
  private final Map<BindingKey, MemberSelect> producerFromProviderMemberSelects = new HashMap<>();
  private final BindingExpression.Factory bindingExpressionFactory;
  protected final MethodSpec.Builder constructor = constructorBuilder().addModifiers(PRIVATE);
  private final OptionalFactories optionalFactories;
  private ComponentBuilder builder;
  private boolean done;

  /**
   * For each component requirement, the builder field. This map is empty for subcomponents that do
   * not use a builder.
   */
  private ImmutableMap<ComponentRequirement, FieldSpec> builderFields = ImmutableMap.of();

  /**
   * For each component requirement, the member select for the component field that holds it.
   *
   * <p>Fields are written for all requirements for subcomponents that do not use a builder, and for
   * any requirement that is reused from a subcomponent of this component.
   */
  protected final Map<ComponentRequirement, MemberSelect> componentContributionFields =
      Maps.newHashMap();

  /**
   * The member-selects for {@link dagger.internal.ReferenceReleasingProviderManager} fields,
   * indexed by their {@link CanReleaseReferences @CanReleaseReferences} scope.
   */
  private ImmutableMap<Scope, MemberSelect> referenceReleasingProviderManagerFields;

  AbstractComponentWriter(
      Types types,
      Elements elements,
      Key.Factory keyFactory,
      CompilerOptions compilerOptions,
      ClassName name,
      BindingGraph graph,
      ImmutableMap<ComponentDescriptor, String> subcomponentNames,
      OptionalFactories optionalFactories) {
    this.types = types;
    this.elements = elements;
    this.keyFactory = keyFactory;
    this.compilerOptions = compilerOptions;
    this.component = classBuilder(name);
    this.name = name;
    this.graph = graph;
    this.subcomponentNames = subcomponentNames;
    this.optionalFactories = optionalFactories;
    this.bindingExpressionFactory =
        new BindingExpression.Factory(
            name, this, childComponentNames(keyFactory, subcomponentNames), graph, elements);
  }

  private static ImmutableMap<BindingKey, String> childComponentNames(
      Key.Factory keyFactory, ImmutableMap<ComponentDescriptor, String> subcomponentNames) {
    ImmutableMap.Builder<BindingKey, String> builder = ImmutableMap.builder();
    subcomponentNames.forEach(
        (component, name) -> {
          if (component.builderSpec().isPresent()) {
            TypeMirror builderType = component.builderSpec().get().builderDefinitionType().asType();
            builder.put(
                BindingKey.contribution(keyFactory.forSubcomponentBuilder(builderType)), name);
          }
        });
    return builder.build();
  }

  protected AbstractComponentWriter(
      AbstractComponentWriter parent, ClassName name, BindingGraph graph) {
    this(
        parent.types,
        parent.elements,
        parent.keyFactory,
        parent.compilerOptions,
        name,
        graph,
        parent.subcomponentNames,
        parent.optionalFactories);
  }

  protected final ClassName componentDefinitionTypeName() {
    return ClassName.get(graph.componentType());
  }

  /**
   * Returns an expression that evaluates to an instance of the requirement, looking for either a
   * builder field or a component field.
   */
  private CodeBlock getComponentContributionExpression(ComponentRequirement componentRequirement) {
    if (builderFields.containsKey(componentRequirement)) {
      return CodeBlock.of("builder.$N", builderFields.get(componentRequirement));
    } else {
      Optional<CodeBlock> codeBlock =
          getOrCreateComponentRequirementFieldExpression(componentRequirement);
      checkState(
          codeBlock.isPresent(), "no builder or component field for %s", componentRequirement);
      return codeBlock.get();
    }
  }

  /**
   * Returns an expression for a component requirement field. Adds a field the first time one is
   * requested for a requirement if this component's builder has a field for it.
   */
  protected Optional<CodeBlock> getOrCreateComponentRequirementFieldExpression(
      ComponentRequirement componentRequirement) {
    MemberSelect fieldSelect = componentContributionFields.get(componentRequirement);
    if (fieldSelect == null) {
      if (!builderFields.containsKey(componentRequirement)) {
        return Optional.empty();
      }
      FieldSpec componentField =
          componentField(
                  TypeName.get(componentRequirement.type()),
                  simpleVariableName(componentRequirement.typeElement()))
              .addModifiers(PRIVATE, FINAL)
              .build();
      component.addField(componentField);
      constructor.addCode(
          "this.$N = builder.$N;", componentField, builderFields.get(componentRequirement));
      fieldSelect = localField(name, componentField.name);
      componentContributionFields.put(componentRequirement, fieldSelect);
    }
    return Optional.of(fieldSelect.getExpressionFor(name));
  }

  /**
   * Creates a {@link FieldSpec.Builder} with a unique name based off of {@code name}.
   */
  protected final FieldSpec.Builder componentField(TypeName type, String name) {
    return FieldSpec.builder(type, componentFieldNames.getUniqueName(name));
  }

  @Override
  public BindingExpression getBindingExpression(BindingKey key) {
    return bindingExpressions.get(key);
  }

  /**
   * The member-select expression for the {@link dagger.internal.ReferenceReleasingProviderManager}
   * object for a scope.
   */
  protected CodeBlock getReferenceReleasingProviderManagerExpression(Scope scope) {
    return referenceReleasingProviderManagerFields.get(scope).getExpressionFor(name);
  }

  /**
   * Constructs a {@link TypeSpec.Builder} that models the {@link BindingGraph} for this component.
   * This is only intended to be called once (and will throw on successive invocations). If the
   * component must be regenerated, use a new instance.
   */
  final TypeSpec.Builder write() {
    checkState(!done, "ComponentWriter has already been generated.");
    decorateComponent();
    if (hasBuilder()) {
      addBuilder();
    }
    addFactoryMethods();
    addReferenceReleasingProviderManagerFields();
    createBindingExpressions();
    initializeFrameworkFields();
    writeFieldsAndInitializeMethods();
    implementInterfaceMethods();
    addSubcomponents();
    component.addMethod(constructor.build());
    if (graph.componentDescriptor().kind().isTopLevel()) {
      optionalFactories.addMembers(component);
    }
    done = true;
    return component;
  }

  /**
   * Adds Javadoc, modifiers, supertypes, and annotations to the component implementation class
   * declaration.
   */
  protected abstract void decorateComponent();

  private boolean hasBuilder() {
    ComponentDescriptor component = graph.componentDescriptor();
    return component.kind().isTopLevel() || component.builderSpec().isPresent();
  }

  /**
   * Adds a builder type.
   */
  private void addBuilder() {
    builder = ComponentBuilder.create(name, graph, subcomponentNames, elements, types);
    builderFields = builder.builderFields();

    addBuilderClass(builder.typeSpec());

    constructor.addParameter(builderName(), "builder");
    constructor.addStatement("assert builder != null");
  }

  /**
   * Adds {@code builder} as a nested builder class. Root components and subcomponents will nest
   * this in different classes.
   */
  protected abstract void addBuilderClass(TypeSpec builder);

  protected final ClassName builderName() {
    return builder.name();
  }

  /**
   * Adds component factory methods.
   */
  protected abstract void addFactoryMethods();

  /**
   * Adds a {@link dagger.internal.ReferenceReleasingProviderManager} field for every {@link
   * CanReleaseReferences @ReleasableReferences} scope for which {@linkplain
   * #requiresReleasableReferences(Scope) one is required}.
   */
  private void addReferenceReleasingProviderManagerFields() {
    ImmutableMap.Builder<Scope, MemberSelect> fields = ImmutableMap.builder();
    for (Scope scope : graph.componentDescriptor().releasableReferencesScopes()) {
      if (requiresReleasableReferences(scope)) {
        FieldSpec field = referenceReleasingProxyManagerField(scope);
        component.addField(field);
        fields.put(scope, localField(name, field.name));
      }
    }
    referenceReleasingProviderManagerFields = fields.build();
  }

  /**
   * Returns {@code true} if {@code scope} {@linkplain CanReleaseReferences can release its
   * references} and there is a dependency request in the component for any of
   *
   * <ul>
   * <li>{@code @ForReleasableReferences(scope)} {@link ReleasableReferenceManager}
   * <li>{@code @ForReleasableReferences(scope)} {@code TypedReleasableReferenceManager<M>}, where
   *     {@code M} is the releasable-references metatadata type for {@code scope}
   * <li>{@code Set<ReleasableReferenceManager>}
   * <li>{@code Set<TypedReleasableReferenceManager<M>>}, where {@code M} is the metadata type for
   *     the scope
   * </ul>
   */
  private boolean requiresReleasableReferences(Scope scope) {
    if (!scope.canReleaseReferences()) {
      return false;
    }

    if (graphHasContributionBinding(keyFactory.forReleasableReferenceManager(scope))
        || graphHasContributionBinding(keyFactory.forSetOfReleasableReferenceManagers())) {
      return true;
    }

    for (AnnotationMirror metadata : scope.releasableReferencesMetadata()) {
      if (graphHasContributionBinding(
              keyFactory.forTypedReleasableReferenceManager(scope, metadata.getAnnotationType()))
          || graphHasContributionBinding(
              keyFactory.forSetOfTypedReleasableReferenceManagers(metadata.getAnnotationType()))) {
        return true;
      }
    }

    return false;
  }

  private boolean graphHasContributionBinding(Key key) {
    return graph.resolvedBindings().containsKey(contribution(key));
  }

  private FieldSpec referenceReleasingProxyManagerField(Scope scope) {
    return componentField(
            REFERENCE_RELEASING_PROVIDER_MANAGER,
            UPPER_CAMEL.to(
                LOWER_CAMEL, scope.scopeAnnotationElement().getSimpleName() + "References"))
        .addModifiers(PRIVATE, FINAL)
        .initializer(
            "new $T($T.class)",
            REFERENCE_RELEASING_PROVIDER_MANAGER,
            scope.scopeAnnotationElement())
        .addJavadoc(
            "The manager that releases references for the {@link $T} scope.\n",
            scope.scopeAnnotationElement())
        .build();
  }

  private void createBindingExpressions() {
    graph.resolvedBindings().values().forEach(this::createBindingExpression);
  }

  private void createBindingExpression(ResolvedBindings resolvedBindings) {
    // If the binding can be satisfied with a static method call without dependencies or state,
    // no field is necessary.
    // TODO(ronshapiro): can these be merged into bindingExpressionFactory.forResolvedBindings()?
    Optional<BindingExpression> staticBindingExpression =
        bindingExpressionFactory.forStaticMethod(resolvedBindings);
    if (staticBindingExpression.isPresent()) {
      bindingExpressions.put(resolvedBindings.bindingKey(), staticBindingExpression.get());
      return;
    }

    // No field needed if there are no owned bindings.
    if (resolvedBindings.ownedBindings().isEmpty()) {
      return;
    }

    // TODO(gak): get rid of the field for unscoped delegated bindings
    bindingExpressions.put(
        resolvedBindings.bindingKey(),
        bindingExpressionFactory.forField(
            resolvedBindings, generateFrameworkField(resolvedBindings, Optional.empty())));
  }

  /**
   * Adds a field representing the resolved bindings, optionally forcing it to use a particular
   * framework class (instead of the class the resolved bindings would typically use).
   */
  private FieldSpec generateFrameworkField(
      ResolvedBindings resolvedBindings, Optional<ClassName> frameworkClass) {
    boolean useRawType = useRawType(resolvedBindings);

    FrameworkField contributionBindingField =
        FrameworkField.forResolvedBindings(resolvedBindings, frameworkClass);
    FieldSpec.Builder contributionField =
        componentField(
            useRawType
                ? contributionBindingField.type().rawType
                : contributionBindingField.type(),
            contributionBindingField.name());
    contributionField.addModifiers(PRIVATE);
    if (useRawType) {
      contributionField.addAnnotation(AnnotationSpecs.suppressWarnings(RAWTYPES));
    }

    return contributionField.build();
  }

  private boolean useRawType(ResolvedBindings resolvedBindings) {
    return useRawType(resolvedBindings.bindingPackage());
  }

  private boolean useRawType(Binding binding) {
    return useRawType(binding.bindingPackage());
  }

  private boolean useRawType(Optional<String> bindingPackage) {
    return bindingPackage.isPresent() && !bindingPackage.get().equals(name.packageName());
  }

  private void implementInterfaceMethods() {
    Set<MethodSignature> interfaceMethods = Sets.newHashSet();
    for (ComponentMethodDescriptor componentMethod :
        graph.componentDescriptor().componentMethods()) {
      if (componentMethod.dependencyRequest().isPresent()) {
        DependencyRequest interfaceRequest = componentMethod.dependencyRequest().get();
        ExecutableElement methodElement =
            MoreElements.asExecutable(componentMethod.methodElement());
        ExecutableType requestType =
            MoreTypes.asExecutable(
                types.asMemberOf(
                    MoreTypes.asDeclared(graph.componentType().asType()), methodElement));
        MethodSignature signature =
            MethodSignature.fromExecutableType(
                methodElement.getSimpleName().toString(), requestType);
        if (!interfaceMethods.contains(signature)) {
          interfaceMethods.add(signature);
          MethodSpec.Builder interfaceMethod =
              methodSpecForComponentMethod(methodElement, requestType);
          CodeBlock codeBlock = getRequestFulfillment(interfaceRequest);
          List<? extends VariableElement> parameters = methodElement.getParameters();
          if (interfaceRequest.kind().equals(DependencyRequest.Kind.MEMBERS_INJECTOR)
              && !parameters.isEmpty() /* i.e. it's not a request for a MembersInjector<T> */) {
            Name parameterName = getOnlyElement(parameters).getSimpleName();
            interfaceMethod.addStatement("$L.injectMembers($L)", codeBlock, parameterName);
            if (!requestType.getReturnType().getKind().equals(VOID)) {
              interfaceMethod.addStatement("return $L", parameterName);
            }
          } else {
            interfaceMethod.addStatement("return $L", codeBlock);
          }
          component.addMethod(interfaceMethod.build());
        }
      }
    }
  }

  private MethodSpec.Builder methodSpecForComponentMethod(
      ExecutableElement method, ExecutableType methodType) {
    String methodName = method.getSimpleName().toString();
    MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(methodName);

    methodBuilder.addAnnotation(Override.class);

    Set<Modifier> modifiers = EnumSet.copyOf(method.getModifiers());
    modifiers.remove(Modifier.ABSTRACT);
    methodBuilder.addModifiers(modifiers);

    methodBuilder.returns(TypeName.get(methodType.getReturnType()));

    List<? extends VariableElement> parameters = method.getParameters();
    List<? extends TypeMirror> resolvedParameterTypes = methodType.getParameterTypes();
    verify(parameters.size() == resolvedParameterTypes.size());
    for (int i = 0; i < parameters.size(); i++) {
      VariableElement parameter = parameters.get(i);
      TypeName type = TypeName.get(resolvedParameterTypes.get(i));
      String name = parameter.getSimpleName().toString();
      Set<Modifier> parameterModifiers = parameter.getModifiers();
      ParameterSpec.Builder parameterBuilder =
          ParameterSpec.builder(type, name)
              .addModifiers(parameterModifiers.toArray(new Modifier[0]));
      methodBuilder.addParameter(parameterBuilder.build());
    }
    for (TypeMirror thrownType : method.getThrownTypes()) {
      methodBuilder.addException(TypeName.get(thrownType));
    }
    return methodBuilder;
  }

  private void addSubcomponents() {
    for (BindingGraph subgraph : graph.subgraphs()) {
      ComponentMethodDescriptor componentMethodDescriptor =
          graph.componentDescriptor()
              .subcomponentsByFactoryMethod()
              .inverse()
              .get(subgraph.componentDescriptor());
      SubcomponentWriter subcomponent =
          new SubcomponentWriter(this, Optional.ofNullable(componentMethodDescriptor), subgraph);
      component.addType(subcomponent.write().build());
    }
  }

  private static final int INITIALIZATIONS_PER_INITIALIZE_METHOD = 100;

  private void initializeFrameworkFields() {
    bindingExpressions.values().forEach(this::initializeFrameworkType);
  }

  private void writeFieldsAndInitializeMethods() {
    ImmutableList.Builder<CodeBlock> codeBlocks = ImmutableList.builder();
    for (BindingExpression bindingExpression : bindingExpressions.values()) {
      bindingExpression.initializeField(
          (field, initialization) -> {
            component.addField(field);
            codeBlocks.add(initialization);
          });
    }

    List<List<CodeBlock>> partitions =
        Lists.partition(codeBlocks.build(), INITIALIZATIONS_PER_INITIALIZE_METHOD);

    UniqueNameSet methodNames = new UniqueNameSet();
    for (List<CodeBlock> partition : partitions) {
      String methodName = methodNames.getUniqueName("initialize");
      MethodSpec.Builder initializeMethod =
          methodBuilder(methodName)
              .addModifiers(PRIVATE)
              /* TODO(gak): Strictly speaking, we only need the suppression here if we are also
               * initializing a raw field in this method, but the structure of this code makes it
               * awkward to pass that bit through.  This will be cleaned up when we no longer
               * separate fields and initilization as we do now. */
              .addAnnotation(AnnotationSpecs.suppressWarnings(UNCHECKED))
              .addCode(CodeBlocks.concat(partition));
      if (hasBuilder()) {
        initializeMethod.addParameter(builderName(), "builder", FINAL);
        constructor.addStatement("$L(builder)", methodName);
      } else {
        constructor.addStatement("$L()", methodName);
      }
      component.addMethod(initializeMethod.build());
    }
  }

  /** Adds code to the given binding expression to initialize it, if necessary. */
  private void initializeFrameworkType(BindingExpression bindingExpression) {
    // If there is no field, don't initialize it.
    if (!bindingExpression.hasFieldSpec()) {
      return;
    }

    // We don't have to check whether we own the field because this method is called only for
    // the bindingExpressions map values). That map is only populated for bindings we own, while
    // getBindingExpression(BindingKey) may return those owned by parents.

    switch (bindingExpression.bindingKey().kind()) {
      case CONTRIBUTION:
        initializeContributionBinding(bindingExpression);
        break;

      case MEMBERS_INJECTION:
        initializeMembersInjectionBinding(bindingExpression);
        break;

      default:
        throw new AssertionError();
    }
  }

  private void initializeContributionBinding(BindingExpression bindingExpression) {
    ContributionBinding binding =
        graph.resolvedBindings().get(bindingExpression.bindingKey()).contributionBinding();
    /* We have some duplication in the branches below b/c initializeDeferredDependencies must be
     * called before we get the code block that initializes the member. */
    switch (binding.factoryCreationStrategy()) {
      case DELEGATE:
        CodeBlock delegatingCodeBlock =
            CodeBlock.of(
                "($T) $L",
                binding.bindingType().frameworkClass(),
                getRequestFulfillment(getOnlyElement(binding.frameworkDependencies())));
        bindingExpression.setInitializationCode(
            initializeDeferredDependencies(binding),
            initializeMember(
                bindingExpression, decorateForScope(delegatingCodeBlock, binding.scope())));
        break;
      case SINGLETON_INSTANCE:
        if (!binding.scope().isPresent()) {
          break;
        }
        // fall through
      case CLASS_CONSTRUCTOR:
        bindingExpression.setInitializationCode(
            initializeDeferredDependencies(binding),
            initializeMember(bindingExpression, initializeFactoryForContributionBinding(binding)));
        break;
      default:
        throw new AssertionError();
    }
  }

  private void initializeMembersInjectionBinding(BindingExpression bindingExpression) {
    BindingKey bindingKey = bindingExpression.bindingKey();
    MembersInjectionBinding binding =
        graph.resolvedBindings().get(bindingKey).membersInjectionBinding().get();

    if (binding.injectionSites().isEmpty()) {
      return;
    }

    bindingExpression.setInitializationCode(
        initializeDeferredDependencies(binding),
        initializeMember(bindingExpression, initializeMembersInjectorForBinding(binding)));
  }

  /**
   * Initializes any dependencies of the given binding that need to be instantiated, i.e., as we get
   * to them during normal initialization.
   */
  private CodeBlock initializeDeferredDependencies(Binding binding) {
    return CodeBlocks.concat(
        ImmutableList.of(
            initializeDelegateFactoriesForUninitializedDependencies(binding),
            initializeProducersFromProviderDependencies(binding)));
  }

  /**
   * Initializes delegate factories for any dependencies of {@code binding} that are uninitialized
   * because of a dependency cycle.
   */
  private CodeBlock initializeDelegateFactoriesForUninitializedDependencies(Binding binding) {
    ImmutableList.Builder<CodeBlock> initializations = ImmutableList.builder();

    for (BindingKey dependencyKey :
        FluentIterable.from(binding.dependencies())
            .transform(DependencyRequest::bindingKey)
            .toSet()) {
      BindingExpression dependencyExpression = getBindingExpression(dependencyKey);
      if (dependencyExpression.hasFieldSpec()
          && dependencyExpression.fieldInitializationState().equals(UNINITIALIZED)) {
        initializations.add(
            CodeBlock.of(
                "this.$L = new $T();", dependencyExpression.fieldName(), DELEGATE_FACTORY));
        dependencyExpression.setFieldInitializationState(DELEGATED);
      }
    }

    return CodeBlocks.concat(initializations.build());
  }

  private CodeBlock initializeProducersFromProviderDependencies(Binding binding) {
    ImmutableList.Builder<CodeBlock> initializations = ImmutableList.builder();
    for (FrameworkDependency frameworkDependency : binding.frameworkDependencies()) {
      if (isProducerFromProvider(frameworkDependency)) {
        BindingKey dependencyKey = frameworkDependency.bindingKey();
        if (producerFromProviderMemberSelects.containsKey(dependencyKey)) {
          continue;
        }
        ResolvedBindings resolvedBindings = graph.resolvedBindings().get(dependencyKey);
        FieldSpec frameworkField = generateFrameworkField(resolvedBindings, Optional.of(PRODUCER));
        component.addField(frameworkField);
        MemberSelect memberSelect = localField(name, frameworkField.name);
        producerFromProviderMemberSelects.put(dependencyKey, memberSelect);
        initializations.add(
            CodeBlock.of(
                "this.$L = $L;",
                memberSelect.getExpressionFor(name),
                getRequestFulfillment(frameworkDependency)));
      }
    }
    return CodeBlocks.concat(initializations.build());
  }

  private boolean isProducerFromProvider(FrameworkDependency frameworkDependency) {
    ResolvedBindings resolvedBindings =
        graph.resolvedBindings().get(frameworkDependency.bindingKey());
    return resolvedBindings.frameworkClass().equals(Provider.class)
        && frameworkDependency.frameworkClass().equals(Producer.class);
  }

  private CodeBlock initializeMember(
      BindingExpression bindingExpression, CodeBlock initializationCodeBlock) {
    ImmutableList.Builder<CodeBlock> initializations = ImmutableList.builder();
    String fieldName = bindingExpression.fieldName();
    CodeBlock delegateFactoryVariable = delegateFactoryVariableName(bindingExpression);

    if (bindingExpression.fieldInitializationState().equals(DELEGATED)) {
      initializations.add(
          CodeBlock.of(
              "$1T $2L = ($1T) $3L;", DELEGATE_FACTORY, delegateFactoryVariable, fieldName));
    }
    initializations.add(CodeBlock.of("this.$L = $L;", fieldName, initializationCodeBlock));
    if (bindingExpression.fieldInitializationState().equals(DELEGATED)) {
      initializations.add(
          CodeBlock.of("$L.setDelegatedProvider($L);", delegateFactoryVariable, fieldName));
    }
    bindingExpression.setFieldInitializationState(INITIALIZED);

    return CodeBlocks.concat(initializations.build());
  }

  private CodeBlock delegateFactoryVariableName(BindingExpression bindingExpression) {
    return CodeBlock.of("$LDelegate", bindingExpression.fieldName().replace('.', '_'));
  }

  private CodeBlock initializeFactoryForContributionBinding(ContributionBinding binding) {
    TypeName bindingKeyTypeName = TypeName.get(binding.key().type());
    switch (binding.bindingKind()) {
      case COMPONENT:
        return CodeBlock.of(
            "$T.<$T>create($L)",
            INSTANCE_FACTORY,
            bindingKeyTypeName,
            bindingKeyTypeName.equals(componentDefinitionTypeName())
                ? "this"
                : getComponentContributionExpression(
                    ComponentRequirement.forDependency(binding.key().type())));

      case COMPONENT_PROVISION:
        {
          TypeElement dependencyType = dependencyTypeForBinding(binding);
          String dependencyVariable = simpleVariableName(dependencyType);
          String componentMethod = binding.bindingElement().get().getSimpleName().toString();
          CodeBlock callFactoryMethod =
              CodeBlock.of("$L.$L()", dependencyVariable, componentMethod);
          // TODO(sameb): This throws a very vague NPE right now.  The stack trace doesn't
          // help to figure out what the method or return type is.  If we include a string
          // of the return type or method name in the error message, that can defeat obfuscation.
          // We can easily include the raw type (no generics) + annotation type (no values),
          // using .class & String.format -- but that wouldn't be the whole story.
          // What should we do?
          CodeBlock getMethodBody =
              binding.nullableType().isPresent()
                      || compilerOptions.nullableValidationKind().equals(Diagnostic.Kind.WARNING)
                  ? CodeBlock.of("return $L;", callFactoryMethod)
                  : CodeBlock.of(
                      "return $T.checkNotNull($L, $S);",
                      Preconditions.class,
                      callFactoryMethod,
                      CANNOT_RETURN_NULL_FROM_NON_NULLABLE_COMPONENT_METHOD);
          ClassName dependencyClassName = ClassName.get(dependencyType);
          String factoryName =
              dependencyClassName.toString().replace('.', '_') + "_" + componentMethod;
          MethodSpec.Builder getMethod =
              methodBuilder("get")
                  .addAnnotation(Override.class)
                  .addModifiers(PUBLIC)
                  .returns(bindingKeyTypeName)
                  .addCode(getMethodBody);
          if (binding.nullableType().isPresent()) {
            getMethod.addAnnotation(
                ClassName.get(MoreTypes.asTypeElement(binding.nullableType().get())));
          }
          component.addType(
              TypeSpec.classBuilder(factoryName)
                  .addSuperinterface(providerOf(bindingKeyTypeName))
                  .addModifiers(PRIVATE, STATIC)
                  .addField(dependencyClassName, dependencyVariable, PRIVATE, FINAL)
                  .addMethod(
                      constructorBuilder()
                          .addParameter(dependencyClassName, dependencyVariable)
                          .addStatement("this.$1L = $1L", dependencyVariable)
                          .build())
                  .addMethod(getMethod.build())
                  .build());
          return CodeBlock.of(
              "new $L($L)",
              factoryName,
              getComponentContributionExpression(
                  ComponentRequirement.forDependency(dependencyType.asType())));
        }

      case SUBCOMPONENT_BUILDER:
        String subcomponentName =
            subcomponentNames.get(
                graph.componentDescriptor()
                    .subcomponentsByBuilderType()
                    .get(MoreTypes.asTypeElement(binding.key().type())));
        return CodeBlock.of(
            Joiner.on('\n')
                .join(
                    "new $1L<$2T>() {",
                    "  @Override public $2T get() {",
                    "    return new $3LBuilder();",
                    "  }",
                    "}"),
            // TODO(ronshapiro): Until we remove Factory, fully qualify the import so it doesn't
            // conflict with dagger.android.ActivityInjector.Factory
            /* 1 */ "dagger.internal.Factory",
            /* 2 */ bindingKeyTypeName,
            /* 3 */ subcomponentName);

      case BUILDER_BINDING:
        return CodeBlock.of(
            "$T.$L($L)",
            InstanceFactory.class,
            binding.nullableType().isPresent() ? "createNullable" : "create",
            getComponentContributionExpression(ComponentRequirement.forBinding(binding)));

      case INJECTION:
      case PROVISION:
        {
          List<CodeBlock> arguments =
              Lists.newArrayListWithCapacity(binding.explicitDependencies().size() + 1);
          if (binding.requiresModuleInstance()) {
            arguments.add(
                getComponentContributionExpression(
                    ComponentRequirement.forModule(binding.contributingModule().get().asType())));
          }
          arguments.addAll(getDependencyArguments(binding));

          CodeBlock factoryCreate =
              CodeBlock.of(
                  "$T.create($L)",
                  generatedClassNameForBinding(binding),
                  makeParametersCodeBlock(arguments));

          // If scoping a parameterized factory for an @Inject class, Java 7 cannot always infer the
          // type properly, so cast to a raw framework type before scoping.
          if (binding.bindingKind().equals(INJECTION)
              && binding.unresolved().isPresent()
              && binding.scope().isPresent()) {
            factoryCreate =
                CodeBlock.of("($T) $L", binding.bindingType().frameworkClass(), factoryCreate);
          }
          return decorateForScope(factoryCreate, binding.scope());
        }

      case COMPONENT_PRODUCTION:
        {
          TypeElement dependencyType = dependencyTypeForBinding(binding);
          return CodeBlock.of(
              Joiner.on('\n')
                  .join(
                      "new $1T<$2T>() {",
                      "  private final $6T $7L = $4L;",
                      "  @Override public $3T<$2T> get() {",
                      "    return $7L.$5L();",
                      "  }",
                      "}"),
              /* 1 */ PRODUCER,
              /* 2 */ TypeName.get(binding.key().type()),
              /* 3 */ LISTENABLE_FUTURE,
              /* 4 */ getComponentContributionExpression(
                  ComponentRequirement.forDependency(dependencyType.asType())),
              /* 5 */ binding.bindingElement().get().getSimpleName(),
              /* 6 */ TypeName.get(dependencyType.asType()),
              /* 7 */ simpleVariableName(dependencyType));
        }

      case PRODUCTION:
        {
          List<CodeBlock> arguments =
              Lists.newArrayListWithCapacity(binding.dependencies().size() + 2);
          if (binding.requiresModuleInstance()) {
            arguments.add(
                getComponentContributionExpression(
                    ComponentRequirement.forModule(binding.contributingModule().get().asType())));
          }
          arguments.addAll(getDependencyArguments(binding));

          return CodeBlock.of(
              "new $T($L)",
              generatedClassNameForBinding(binding),
              makeParametersCodeBlock(arguments));
        }

      case SYNTHETIC_MAP:
        FrameworkDependency frameworkDependency = getOnlyElement(binding.frameworkDependencies());
        return CodeBlock.of(
            "$T.create($L)",
            mapFactoryClassName(binding),
            getRequestFulfillment(frameworkDependency));

      case SYNTHETIC_MULTIBOUND_SET:
        return initializeFactoryForSetMultibinding(binding);

      case SYNTHETIC_MULTIBOUND_MAP:
        return initializeFactoryForMapMultibinding(binding);

      case SYNTHETIC_RELEASABLE_REFERENCE_MANAGER:
        return initializeFactoryForSyntheticReleasableReferenceManagerBinding(binding);

      case SYNTHETIC_RELEASABLE_REFERENCE_MANAGERS:
        return initializeFactoryForSyntheticSetOfReleasableReferenceManagers(binding);

      case SYNTHETIC_OPTIONAL_BINDING:
        return initializeFactoryForSyntheticOptionalBinding(binding);

      default:
        throw new AssertionError(binding);
    }
  }

  private TypeElement dependencyTypeForBinding(ContributionBinding binding) {
    return graph.componentDescriptor().dependencyMethodIndex().get(binding.bindingElement().get());
  }

  private CodeBlock decorateForScope(CodeBlock factoryCreate, Optional<Scope> maybeScope) {
    if (!maybeScope.isPresent()) {
      return factoryCreate;
    }
    Scope scope = maybeScope.get();
    if (requiresReleasableReferences(scope)) {
      return CodeBlock.of(
          "$T.create($L, $L)",
          REFERENCE_RELEASING_PROVIDER,
          factoryCreate,
          getReferenceReleasingProviderManagerExpression(scope));
    } else {
      return CodeBlock.of(
          "$T.provider($L)",
          scope.equals(reusableScope(elements)) ? SINGLE_CHECK : DOUBLE_CHECK,
          factoryCreate);
    }
  }

  private CodeBlock initializeMembersInjectorForBinding(MembersInjectionBinding binding) {
    return binding.injectionSites().isEmpty()
        ? CodeBlock.of("$T.noOp()", MEMBERS_INJECTORS)
        : CodeBlock.of(
            "$T.create($L)",
            membersInjectorNameForType(binding.membersInjectedType()),
            makeParametersCodeBlock(getDependencyArguments(binding)));
  }

  /**
   * The expressions that represent factory arguments for the dependencies of a binding.
   */
  private ImmutableList<CodeBlock> getDependencyArguments(Binding binding) {
    ImmutableList<FrameworkDependency> dependencies = binding.frameworkDependencies();
    return dependencies.stream().map(this::getDependencyArgument).collect(toImmutableList());
  }

  /** Returns the expression to use as an argument for a dependency. */
  private CodeBlock getDependencyArgument(FrameworkDependency frameworkDependency) {
    BindingKey requestedKey = frameworkDependency.bindingKey();
    return isProducerFromProvider(frameworkDependency)
        ? producerFromProviderMemberSelects.get(requestedKey).getExpressionFor(name)
        : getRequestFulfillment(frameworkDependency);
  }

  private CodeBlock initializeFactoryForSetMultibinding(ContributionBinding binding) {
    CodeBlock.Builder builder = CodeBlock.builder().add("$T.", setFactoryClassName(binding));
    boolean useRawTypes = useRawType(binding);
    if (!useRawTypes) {
      SetType setType = SetType.from(binding.key());
      builder.add(
          "<$T>",
          setType.elementsAreTypeOf(Produced.class)
              ? setType.unwrappedElementType(Produced.class)
              : setType.elementType());
    }
    int individualProviders = 0;
    int setProviders = 0;
    CodeBlock.Builder builderMethodCalls = CodeBlock.builder();
    for (FrameworkDependency frameworkDependency : binding.frameworkDependencies()) {
      ContributionType contributionType =
          graph.resolvedBindings().get(frameworkDependency.bindingKey()).contributionType();
      String methodName;
      String methodNameSuffix = frameworkDependency.frameworkClass().getSimpleName();
      switch (contributionType) {
        case SET:
          individualProviders++;
          methodName = "add" + methodNameSuffix;
          break;
        case SET_VALUES:
          setProviders++;
          methodName = "addCollection" + methodNameSuffix;
          break;
        default:
          throw new AssertionError(frameworkDependency + " is not a set multibinding");
      }

      builderMethodCalls.add(
          ".$L($L)",
          methodName,
          potentiallyCast(
              useRawTypes,
              frameworkDependency.frameworkClass(),
              getDependencyArgument(frameworkDependency)));
    }
    builder.add("builder($L, $L)", individualProviders, setProviders);
    builder.add(builderMethodCalls.build());
    return builder.add(".build()").build();
  }

  private CodeBlock initializeFactoryForMapMultibinding(ContributionBinding binding) {
    ImmutableList<FrameworkDependency> frameworkDependencies = binding.frameworkDependencies();

    ImmutableList.Builder<CodeBlock> codeBlocks = ImmutableList.builder();
    MapType mapType = MapType.from(binding.key().type());
    CodeBlock.Builder builderCall =
        CodeBlock.builder().add("$T.", frameworkMapFactoryClassName(binding.bindingType()));
    boolean useRawTypes = useRawType(binding);
    if (!useRawTypes) {
      builderCall.add("<$T, $T>", TypeName.get(mapType.keyType()),
          TypeName.get(mapType.unwrappedValueType(binding.bindingType().frameworkClass())));
    }
    builderCall.add("builder($L)", frameworkDependencies.size());
    codeBlocks.add(builderCall.build());

    for (FrameworkDependency frameworkDependency : frameworkDependencies) {
      BindingKey bindingKey = frameworkDependency.bindingKey();
      ContributionBinding contributionBinding =
          graph.resolvedBindings().get(bindingKey).contributionBinding();
      CodeBlock value =
          potentiallyCast(
              useRawTypes,
              frameworkDependency.frameworkClass(),
              getDependencyArgument(frameworkDependency));
      codeBlocks.add(
          CodeBlock.of(
              ".put($L, $L)", getMapKeyExpression(contributionBinding.mapKey().get()), value));
    }
    codeBlocks.add(CodeBlock.of(".build()"));

    return CodeBlocks.concat(codeBlocks.build());
  }

  private CodeBlock potentiallyCast(boolean shouldCast, Class<?> classToCast, CodeBlock notCasted) {
    if (!shouldCast) {
      return notCasted;
    }
    return CodeBlock.of("($T) $L", classToCast, notCasted);
  }

  /**
   * Initializes the factory for a {@link
   * ContributionBinding.Kind#SYNTHETIC_RELEASABLE_REFERENCE_MANAGER} binding.
   *
   * <p>The {@code get()} method just returns the component field with the {@link
   * dagger.internal.ReferenceReleasingProviderManager} object.
   */
  private CodeBlock initializeFactoryForSyntheticReleasableReferenceManagerBinding(
      ContributionBinding binding) {
    // The scope is the value of the @ForReleasableReferences annotation.
    Scope scope = forReleasableReferencesAnnotationValue(binding.key().qualifier().get());

    CodeBlock managerExpression;
    if (MoreTypes.isTypeOf(TypedReleasableReferenceManager.class, binding.key().type())) {
      /* The key's type is TypedReleasableReferenceManager<M>, so return
       * new TypedReleasableReferenceManager(field, metadata). */
      TypeMirror metadataType =
          MoreTypes.asDeclared(binding.key().type()).getTypeArguments().get(0);
      managerExpression =
          typedReleasableReferenceManagerDecoratorExpression(
              getReferenceReleasingProviderManagerExpression(scope),
              scope.releasableReferencesMetadata(metadataType).get());
    } else {
      // The key's type is ReleasableReferenceManager, so return the field as is.
      managerExpression = getReferenceReleasingProviderManagerExpression(scope);
    }

    TypeName keyType = TypeName.get(binding.key().type());
    return CodeBlock.of(
        "$L",
        anonymousClassBuilder("")
            .addSuperinterface(providerOf(keyType))
            .addMethod(
                methodBuilder("get")
                    .addAnnotation(Override.class)
                    .addModifiers(PUBLIC)
                    .returns(keyType)
                    .addCode("return $L;", managerExpression)
                    .build())
            .build());
  }

  /**
   * Initializes the factory for a {@link
   * ContributionBinding.Kind#SYNTHETIC_RELEASABLE_REFERENCE_MANAGERS} binding.
   *
   * <p>A binding for {@code Set<ReleasableReferenceManager>} will include managers for all
   * reference-releasing scopes. A binding for {@code Set<TypedReleasableReferenceManager<M>>} will
   * include managers for all reference-releasing scopes whose metadata type is {@code M}.
   */
  private CodeBlock initializeFactoryForSyntheticSetOfReleasableReferenceManagers(
      ContributionBinding binding) {
    Key key = binding.key();
    SetType keyType = SetType.from(key);
    ImmutableList.Builder<CodeBlock> managerExpressions = ImmutableList.builder();
    for (Map.Entry<Scope, MemberSelect> entry :
        referenceReleasingProviderManagerFields.entrySet()) {
      Scope scope = entry.getKey();
      CodeBlock releasableReferenceManagerExpression = entry.getValue().getExpressionFor(name);

      if (keyType.elementsAreTypeOf(ReleasableReferenceManager.class)) {
        managerExpressions.add(releasableReferenceManagerExpression);
      } else if (keyType.elementsAreTypeOf(TypedReleasableReferenceManager.class)) {
        TypeMirror metadataType =
            keyType.unwrappedElementType(TypedReleasableReferenceManager.class);
        Optional<AnnotationMirror> metadata = scope.releasableReferencesMetadata(metadataType);
        if (metadata.isPresent()) {
          managerExpressions.add(
              typedReleasableReferenceManagerDecoratorExpression(
                  releasableReferenceManagerExpression, metadata.get()));
        }
      } else {
        throw new IllegalArgumentException("inappropriate key: " + binding);
      }
    }
    TypeName keyTypeName = TypeName.get(key.type());
    return CodeBlock.of(
        "$L",
        anonymousClassBuilder("")
            .addSuperinterface(providerOf(keyTypeName))
            .addMethod(
                methodBuilder("get")
                    .addAnnotation(Override.class)
                    .addModifiers(PUBLIC)
                    .returns(keyTypeName)
                    .addCode(
                        "return new $T($T.asList($L));",
                        HashSet.class,
                        Arrays.class,
                        makeParametersCodeBlock(managerExpressions.build()))
                    .build())
            .build());
  }

  /**
   * Returns an expression that evaluates to a {@link TypedReleasableReferenceManagerDecorator} that
   * decorates the {@code managerExpression} to supply {@code metadata}.
   */
  private CodeBlock typedReleasableReferenceManagerDecoratorExpression(
      CodeBlock managerExpression, AnnotationMirror metadata) {
    return CodeBlock.of(
        "new $T($L, $L)",
        ParameterizedTypeName.get(
            TYPED_RELEASABLE_REFERENCE_MANAGER_DECORATOR,
            TypeName.get(metadata.getAnnotationType())),
        managerExpression,
        new AnnotationExpression(metadata).getAnnotationInstanceExpression());
  }

  private Scope forReleasableReferencesAnnotationValue(AnnotationMirror annotation) {
    checkArgument(
        MoreTypes.isTypeOf(ForReleasableReferences.class, annotation.getAnnotationType()));
    return Scope.scope(
        MoreElements.asType(MoreTypes.asDeclared(getTypeValue(annotation, "value")).asElement()));
  }

  /**
   * Returns an expression that initializes a {@link Provider} or {@link Producer} for an optional
   * binding.
   */
  private CodeBlock initializeFactoryForSyntheticOptionalBinding(ContributionBinding binding) {
    if (binding.explicitDependencies().isEmpty()) {
      verify(
          binding.bindingType().equals(BindingType.PROVISION),
          "Absent optional bindings should be provisions: %s",
          binding);
      return optionalFactories.absentOptionalProvider(binding);
    } else {
      return optionalFactories.presentOptionalFactory(
          binding, getOnlyElement(getDependencyArguments(binding)));
    }
  }

  private CodeBlock getRequestFulfillment(FrameworkDependency frameworkDependency) {
    return getBindingExpression(frameworkDependency.bindingKey())
        .getSnippetForFrameworkDependency(frameworkDependency, name);
  }

  private CodeBlock getRequestFulfillment(DependencyRequest dependencyRequest) {
    return getBindingExpression(dependencyRequest.bindingKey())
        .getSnippetForDependencyRequest(dependencyRequest, name);
  }
}
