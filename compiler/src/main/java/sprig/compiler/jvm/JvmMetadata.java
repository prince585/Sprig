package sprig.compiler.jvm;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sprig.compiler.sem.JavaTypes;

/**
 * Shared reflection view for {@code api} and compiler overload diagnostics.
 *
 * <p>One {@link Support} classification is the single source of truth for
 * "can Sprig bind this, how, and if not, why": the checker's candidate gate and
 * {@code sprig api} both read it, so metadata cannot drift from behavior.
 */
public final class JvmMetadata {
    private JvmMetadata() {}

    /** Stable interop classification shared by metadata and checking. */
    public record Support(String level, List<String> reasonCodes, String unusableReason,
                          boolean usable, String adaptationKind, String adaptationHelp) {
        public boolean adaptationAvailable() {
            return adaptationKind != null;
        }
    }

    private static final List<Class<?>> COLLECTION_KINDS = List.of(
            java.util.List.class, java.util.Map.class,
            sprig.runtime.SprigList.class, sprig.runtime.SprigMap.class,
            sprig.runtime.SprigMutableList.class, sprig.runtime.SprigMutableMap.class);

    public static Map<String, Object> inspect(Class<?> clazz) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", 1);
        out.put("className", clazz.getName());
        out.put("classKind", clazz.isInterface() ? "interface" : clazz.isEnum() ? "enum" : "class");
        List<Map<String, Object>> constructors = new ArrayList<>();
        Arrays.stream(clazz.getConstructors()).sorted(Comparator.comparing(Constructor::toGenericString))
                .forEach(ctor -> constructors.add(describe(ctor)));
        out.put("constructors", constructors);
        List<Map<String, Object>> staticMethods = new ArrayList<>();
        List<Map<String, Object>> instanceMethods = new ArrayList<>();
        Arrays.stream(clazz.getMethods()).sorted(Comparator.comparing(Method::toGenericString))
                .forEach(method -> (Modifier.isStatic(method.getModifiers()) ? staticMethods : instanceMethods)
                        .add(describe(method)));
        out.put("staticMethods", staticMethods);
        out.put("instanceMethods", instanceMethods);
        List<Map<String, Object>> fields = new ArrayList<>();
        Arrays.stream(clazz.getFields()).sorted(Comparator.comparing(Field::getName)
                        .thenComparing(Field::toGenericString))
                .forEach(field -> fields.add(describe(field)));
        out.put("fields", fields);
        out.put("nullabilityPolicy", "Java reference results are nullable unless a run-time visible annotation declares them non-null (NotNull/NonNull/Nonnull, or NullMarked/NonNullApi/MethodsReturnNonnullByDefault on the class or package) or the method is toString(); parameters require non-null values unless annotated Nullable or CheckForNull; annotations kept only in class files (org.jetbrains.annotations, Android) are read from the class file");
        out.put("interopLevels", List.of(
                Map.of("level", "direct", "meaning", "no generic or array shape is involved"),
                Map.of("level", "concrete-generic", "meaning", "every generic argument is concrete and preserved"),
                Map.of("level", "opaque-array", "meaning", "array values cross the boundary unchanged; no source array syntax exists"),
                Map.of("level", "adaptable", "meaning", "an explicit adapter is available (collections or byte arrays)"),
                Map.of("level", "sprig-callable", "meaning", "concrete Fn0..Fn3 slots are checked invariantly"),
                Map.of("level", "java-callable", "meaning", "a functional-interface parameter accepts a Sprig fn value through a generated adapter"),
                Map.of("level", "erased-generic", "meaning", "generic information exists but this raw context binds the erased boundary"),
                Map.of("level", "unsupported", "meaning", "the reflection shape is outside the supported profile")));
        out.put("classpath", JvmClasspath.entries().stream().map(java.nio.file.Path::toString).toList());
        return out;
    }

    public static Map<String, Object> describe(Executable executable) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", executable instanceof Method method ? method.getName() : "<init>");
        out.put("javaSignature", executable.toGenericString());
        out.put("sprigSignature", sprigSignature(executable));
        out.put("static", Modifier.isStatic(executable.getModifiers()));
        out.put("varargs", executable.isVarArgs());
        out.put("javaParameterTypes", Arrays.stream(executable.getParameterTypes()).map(Class::getTypeName).toList());
        List<String> parameterTypes = new ArrayList<>();
        for (int i = 0; i < executable.getParameterCount(); i++) {
            String formal = formalDisplay(executable.getGenericParameterTypes()[i], executable.getParameterTypes()[i]);
            if (JvmNullability.parameterNullable(executable, i) && !formal.endsWith("?")) {
                formal += "?"; // annotated nullable: a T? or null is accepted
            }
            parameterTypes.add(formal);
        }
        out.put("sprigParameterTypes", parameterTypes);
        out.put("genericParameterTypes", Arrays.stream(executable.getGenericParameterTypes())
                .map(Type::getTypeName).toList());
        out.put("parameterTypeShapes", Arrays.stream(executable.getGenericParameterTypes())
                .map(JavaTypes::shapeJson).toList());
        out.put("typeParameters", Arrays.stream(executable.getTypeParameters())
                .map(TypeVariable::getName).toList());
        if (executable instanceof Method method) {
            boolean textResult = isTextResult(method);
            String sprigReturn = sprigReturn(method);
            out.put("javaReturnType", method.getReturnType().getTypeName());
            out.put("sprigReturnType", sprigReturn);
            out.put("sprigBoundaryType", sprigReturn);
            out.put("genericReturnType", method.getGenericReturnType().getTypeName());
            out.put("returnTypeShape", JavaTypes.shapeJson(method.getGenericReturnType()));
            out.put("nullableResult", !textResult && !method.getReturnType().isPrimitive()
                    && !JvmNullability.returnsNonNull(method));
        } else {
            out.put("javaReturnType", executable.getDeclaringClass().getTypeName());
            out.put("sprigReturnType", executable.getDeclaringClass().getSimpleName());
            out.put("sprigBoundaryType", executable.getDeclaringClass().getName());
            out.put("nullableResult", false);
        }
        out.put("checkedExceptions", Arrays.stream(executable.getExceptionTypes())
                .filter(e -> !RuntimeException.class.isAssignableFrom(e) && !Error.class.isAssignableFrom(e))
                .map(Class::getTypeName).toList());
        Support support = support(executable);
        out.put("usableFromSprig", support.usable());
        out.put("signatureSupported", support.usable());
        out.put("interopLevel", support.level());
        out.put("interopReasonCodes", support.reasonCodes());
        out.put("unusableReason", support.unusableReason());
        out.put("adaptation", adaptation(support));
        out.put("genericBoundary", genericBoundary(executable));
        List<String> interopNotes = new ArrayList<>();
        boolean callableBoundary = callableBoundary(executable);
        out.put("sprigCallableBoundary", callableBoundary);
        if (callableBoundary) interopNotes.add("Concrete Fn0..Fn3 arguments preserve invariant source function types; Java callback parameters/results must be non-null, except Void denotes Unit.");
        if (support.reasonCodes().contains("raw-generic-boundary")) interopNotes.add(
                "Generic type arguments are erased at the Sprig boundary; no List[T] or Map[K,V] guarantee is inferred. Use explicit Type[Arg] application or an adapter for concrete typing.");
        if (support.reasonCodes().contains("array-source-syntax-unavailable")) interopNotes.add(
                "Array values cross the boundary unchanged with their exact JVM class; Sprig has no array literal, indexing or annotation syntax.");
        if (Arrays.stream(executable.getParameterTypes())
                .anyMatch(c -> c == char.class || c == Character.class)) interopNotes.add(
                "Java char/Character arguments accept only a one-UTF-16-unit Sprig String literal.");
        if (support.reasonCodes().contains("java-callable-adapter")) interopNotes.add(
                "A functional-interface parameter accepts a Sprig function value of the shown fn(...) -> R type without a throws clause; the compiler emits the Java adapter. void accepts any result.");
        if (support.reasonCodes().contains("varargs-expansion")) interopNotes.add(
                "Trailing arguments are packed into the final array parameter (zero of them is allowed); an opaque Java array of exactly that class is passed through. Fixed-arity overloads are preferred.");
        if (support.reasonCodes().contains("wildcard-bounds")) interopNotes.add(
                "A wildcard keeps its bound: a result of List<? extends T> reads elements as T, a parameter List<? extends T> accepts any List whose element type fits T, and an element cannot be added through ? extends.");
        out.put("interopNotes", interopNotes);
        return out;
    }

    /** A formal as Sprig sees it: the fn(...) -> R type of a functional interface, else the mapped type. */
    private static String formalDisplay(java.lang.reflect.Type generic, Class<?> raw) {
        sprig.compiler.types.FunctionType callable = JavaTypes.javaCallable(generic, raw, JavaTypes.NO_BINDINGS);
        return callable != null ? callable.display() : JavaTypes.mapFormal(generic, raw).display();
    }

    public static Map<String, Object> describe(Field field) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", field.getName());
        out.put("javaSignature", field.toGenericString());
        out.put("javaType", field.getType().getTypeName());
        sprig.compiler.types.Type fieldType = JavaTypes.mapValue(field.getGenericType(), field.getType());
        if (fieldType.isNullable() && JvmNullability.fieldNonNull(field)) {
            fieldType = fieldType.nonNull();
        }
        out.put("sprigType", fieldType.display());
        out.put("sprigBoundaryType", fieldType.display());
        out.put("genericType", field.getGenericType().getTypeName());
        out.put("typeShape", JavaTypes.shapeJson(field.getGenericType()));
        out.put("static", Modifier.isStatic(field.getModifiers()));
        out.put("nullableResult", fieldType.isNullable());
        Support support = support(field);
        out.put("usableFromSprig", support.usable());
        out.put("signatureSupported", support.usable());
        out.put("genericBoundary", genericBoundary(field.getGenericType()));
        out.put("interopLevel", support.level());
        out.put("interopReasonCodes", support.reasonCodes());
        out.put("unusableReason", support.unusableReason());
        out.put("adaptation", adaptation(support));
        if (JavaTypes.needsValueAdapter(field.getType())) out.put("writePolicy",
                "Direct assignment is unsupported; use an explicit Java setter or adapter.");
        return out;
    }

    private static Map<String, Object> adaptation(Support support) {
        if (!support.adaptationAvailable()) {
            return Map.of("available", false);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("kind", support.adaptationKind());
        out.put("help", support.adaptationHelp());
        return out;
    }

    private static String sprigSignature(Executable executable) {
        StringBuilder out = new StringBuilder(executable instanceof Method method ? method.getName() : executable.getDeclaringClass().getSimpleName());
        out.append('(');
        Class<?>[] params = executable.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) out.append(", ");
            out.append(formalDisplay(executable.getGenericParameterTypes()[i], params[i]));
        }
        out.append(')');
        if (executable instanceof Method method) out.append(" -> ").append(sprigReturn(method));
        return out.toString();
    }

    /** toString() is a non-null String in Sprig, as the checker types it. */
    private static boolean isTextResult(Method method) {
        return method.getName().equals("toString") && method.getParameterCount() == 0
                && method.getReturnType() == String.class;
    }

    /** The Sprig type of a method's result, the same in every field of the description. */
    private static String sprigReturn(Method method) {
        if (isTextResult(method)) {
            return "String";
        }
        sprig.compiler.types.Type result = JavaTypes.mapValue(method.getGenericReturnType(), method.getReturnType());
        if (result.isNullable() && JvmNullability.returnsNonNull(method)) {
            result = result.nonNull();
        }
        return result.display();
    }

    // ------------------------------------------------------------------
    // Support classification (shared with the checker)
    // ------------------------------------------------------------------

    /** Compatible one-line view of the shared classification. */
    public static String unsupportedReason(Executable executable) {
        Support support = support(executable);
        return support.usable() ? null : support.unusableReason();
    }

    public static Support support(Executable executable) {
        if (executable instanceof Method method && supersededBridge(method)) {
            return unsupported("bridge-superseded", "Java compiler bridge is superseded by its source method");
        }
        java.lang.reflect.Type[] generic = executable.getGenericParameterTypes();
        Class<?>[] raw = executable.getParameterTypes();
        // A varargs tail is bound by its element: trailing arguments are
        // packed into the array, or an opaque array of that class passes
        // through. A type-variable element (T...) has no class to pack into.
        boolean varargs = executable.isVarArgs();
        if (varargs && JavaTypes.varargsElement(executable) == null) {
            return unsupported("generic-array-unsupported",
                    "Java varargs of a type-variable element (T...) are not supported");
        }
        for (int i = 0; i < raw.length; i++) {
            if (JavaTypes.isCallableClass(raw[i]) && JavaTypes.callable(generic[i]) == null) {
                return unsupported("sprig-callable-boundary",
                        "Sprig callable boundary requires concrete invariant Fn0..Fn3 type arguments");
            }
        }
        if (executable instanceof Method method && JavaTypes.isCallableClass(method.getReturnType())
                && JavaTypes.callable(method.getGenericReturnType()) == null) {
            return unsupported("sprig-callable-boundary",
                    "Sprig callable result requires concrete invariant Fn0..Fn3 type arguments");
        }
        // The varargs element and functional-interface formals are judged by
        // their own rules above and below; the shape scan covers the rest.
        List<java.lang.reflect.Type> scanned = new ArrayList<>();
        boolean javaCallable = false;
        for (int i = 0; i < raw.length; i++) {
            if (varargs && i == raw.length - 1) continue;
            if (JavaTypes.functionalFormal(generic[i], raw[i])) {
                javaCallable = true;
                continue;
            }
            scanned.add(generic[i]);
        }
        if (executable instanceof Method method) scanned.add(method.getGenericReturnType());
        if (hasShape(scanned, JavaTypes.Shape.GENERIC_ARRAY)) {
            return unsupported("generic-array-unsupported", "Java generic array types (T[]) are not supported");
        }
        if (executable.getTypeParameters().length > 0 && recursiveBounds(executable)) {
            return unsupported("generic-bound-unsupported",
                    "Java method type parameters with recursive or intersection bounds are not supported");
        }
        // Wildcards keep their bounds: a value reads at the upper bound, an
        // argument must fit the bound, and a write through ? extends has no
        // type to offer, so that one call is rejected rather than the member.
        boolean wildcards = hasShape(scanned, JavaTypes.Shape.WILDCARD);
        if (genericWrapper(executable)) {
            return unsupported("generic-wrapper-unsupported",
                    "Short/Byte/Character generic arguments require an element adapter");
        }
        List<String> codes = new ArrayList<>();
        boolean array = hasArray(executable);
        boolean collections = hasCollection(executable);
        boolean typeVariable = hasShape(executable, JavaTypes.Shape.TYPE_VARIABLE);
        boolean parameterized = hasShape(executable, JavaTypes.Shape.PARAMETERIZED);
        boolean callable = callableBoundary(executable);
        if (array) codes.add("array-source-syntax-unavailable");
        if (varargs) codes.add("varargs-expansion");
        if (javaCallable) codes.add("java-callable-adapter");
        if (wildcards) codes.add("wildcard-bounds");
        if (typeVariable) codes.add("raw-generic-boundary");
        if (executable.getTypeParameters().length > 0) codes.add("explicit-type-arguments-required");
        String level;
        if (callable) {
            level = "sprig-callable";
        } else if (javaCallable) {
            level = "java-callable";
        } else if (array) {
            level = "opaque-array";
        } else if (collections) {
            level = "adaptable";
        } else if (typeVariable) {
            level = "erased-generic";
        } else if (parameterized) {
            level = "concrete-generic";
        } else {
            level = "direct";
        }
        String kind = null;
        String help = null;
        if (array && usesByteArray(executable)) {
            kind = "byte-array";
            help = "import sprig.runtime.jvm.HostBytes as HostBytes: utf8, utf8String (strict), length, hex";
        } else if (collections) {
            kind = "collection-adapter";
            help = "import \"@std/jvm.spr\" as jvm: list_snapshot/map_snapshot copy validated non-null contents; list_copy/map_copy build independent Java copies";
        }
        return new Support(level, codes, null, true, kind, help);
    }

    public static Support support(Field field) {
        java.lang.reflect.Type generic = field.getGenericType();
        boolean array = field.getType().isArray();
        // Shape inspection must recurse: List<?> and List<T[]> only look
        // parameterized at the top level.
        if (containsShape(generic, JavaTypes.Shape.GENERIC_ARRAY)) {
            return unsupported("generic-array-unsupported", "Java generic array types (T[]) are not supported");
        }
        if (JavaTypes.wrapperArgument(generic)) {
            return unsupported("generic-wrapper-unsupported",
                    "Short/Byte/Character generic arguments require an element adapter");
        }
        List<String> codes = new ArrayList<>();
        if (array) codes.add("array-source-syntax-unavailable");
        if (containsShape(generic, JavaTypes.Shape.WILDCARD)) codes.add("wildcard-bounds");
        boolean typeVariable = containsShape(generic, JavaTypes.Shape.TYPE_VARIABLE);
        boolean parameterized = containsShape(generic, JavaTypes.Shape.PARAMETERIZED);
        if (typeVariable) codes.add("raw-generic-boundary");
        String level;
        if (array) {
            level = "opaque-array";
        } else if (COLLECTION_KINDS.contains(field.getType())) {
            level = "adaptable";
        } else if (typeVariable) {
            level = "erased-generic";
        } else if (parameterized) {
            level = "concrete-generic";
        } else {
            level = "direct";
        }
        String kind = null;
        String help = null;
        if (array && field.getType() == byte[].class) {
            kind = "byte-array";
            help = "import sprig.runtime.jvm.HostBytes as HostBytes: utf8, utf8String (strict), length, hex";
        } else if (COLLECTION_KINDS.contains(field.getType())) {
            kind = "collection-adapter";
            help = "import \"@std/jvm.spr\" as jvm: list_snapshot/map_snapshot copy validated non-null contents; list_copy/map_copy build independent Java copies";
        }
        return new Support(level, codes, null, true, kind, help);
    }

    private static Support unsupported(String code, String reason) {
        return new Support("unsupported", List.of(code), reason, false, null, null);
    }

    private static boolean hasArray(Executable executable) {
        for (Class<?> param : executable.getParameterTypes()) {
            if (param.isArray()) return true;
        }
        return executable instanceof Method method && method.getReturnType().isArray();
    }

    private static boolean usesByteArray(Executable executable) {
        for (Class<?> param : executable.getParameterTypes()) {
            if (param == byte[].class) return true;
        }
        return executable instanceof Method method && method.getReturnType() == byte[].class;
    }

    private static boolean hasCollection(Executable executable) {
        for (Class<?> param : executable.getParameterTypes()) {
            if (COLLECTION_KINDS.contains(param)) return true;
        }
        return executable instanceof Method method && COLLECTION_KINDS.contains(method.getReturnType());
    }

    private static boolean genericWrapper(Executable executable) {
        for (java.lang.reflect.Type type : executable.getGenericParameterTypes()) {
            if (JavaTypes.wrapperArgument(type)) return true;
        }
        return executable instanceof Method method
                && JavaTypes.wrapperArgument(method.getGenericReturnType());
    }

    private static boolean hasShape(Executable executable, JavaTypes.Shape target) {
        for (java.lang.reflect.Type type : executable.getGenericParameterTypes()) {
            if (containsShape(type, target)) return true;
        }
        return executable instanceof Method method
                && containsShape(method.getGenericReturnType(), target);
    }

    private static boolean hasShape(List<java.lang.reflect.Type> types, JavaTypes.Shape target) {
        for (java.lang.reflect.Type type : types) {
            if (containsShape(type, target)) return true;
        }
        return false;
    }

    private static boolean containsShape(java.lang.reflect.Type type, JavaTypes.Shape target) {
        if (JavaTypes.shape(type) == target) return true;
        if (type instanceof ParameterizedType applied) {
            for (java.lang.reflect.Type argument : applied.getActualTypeArguments()) {
                if (containsShape(argument, target)) return true;
            }
        }
        if (type instanceof GenericArrayType array) {
            return containsShape(array.getGenericComponentType(), target);
        }
        return false;
    }

    private static boolean recursiveBounds(Executable executable) {
        for (TypeVariable<?> variable : executable.getTypeParameters()) {
            for (java.lang.reflect.Type bound : variable.getBounds()) {
                if (bound == Object.class) continue;
                if (!(bound instanceof Class<?>)) return true; // parameterized or variable bound
            }
        }
        return false;
    }

    private static boolean genericBoundary(java.lang.reflect.Type type) {
        return JavaTypes.shape(type) != JavaTypes.Shape.CLASS
                || (type instanceof Class<?> clazz && clazz.isArray());
    }

    private static boolean callableBoundary(Executable executable) {
        return Arrays.stream(executable.getParameterTypes()).anyMatch(JavaTypes::isCallableClass)
                || executable instanceof Method m && JavaTypes.isCallableClass(m.getReturnType());
    }

    private static boolean genericBoundary(Executable executable) {
        if (executable instanceof Method method && method.getGenericReturnType() != method.getReturnType()) return true;
        Type[] generic = executable.getGenericParameterTypes();
        Class<?>[] raw = executable.getParameterTypes();
        for (int i = 0; i < raw.length; i++) if (generic[i] != raw[i]) return true;
        return false;
    }

    private static boolean supersededBridge(Method bridge) {
        if (!bridge.isBridge()) return false;
        Class<?> owner = bridge.getDeclaringClass();
        for (Method target : owner.getDeclaredMethods()) {
            if (target.isBridge() || !Modifier.isPublic(target.getModifiers())
                    || !target.getName().equals(bridge.getName())
                    || !bridge.getReturnType().isAssignableFrom(target.getReturnType())) continue;
            if (Arrays.equals(bridge.getParameterTypes(), target.getParameterTypes())) return true;
            // Public access bridges can forward an inherited method from a
            // nonpublic base. A narrower overload alone does not supersede one:
            // the target must override the inherited, substituted signature.
            if (overridesBridgeSource(owner, Map.of(), bridge, target)) return true;
        }
        return false;
    }

    private static boolean overridesBridgeSource(Class<?> owner,
            Map<java.lang.reflect.TypeVariable<?>, Class<?>> bindings, Method bridge, Method target) {
        List<Type> parents = new ArrayList<>(List.of(owner.getGenericInterfaces()));
        if (owner.getGenericSuperclass() != null) parents.add(owner.getGenericSuperclass());
        for (Type parent : parents) {
            Class<?> raw = erasedType(parent, bindings);
            Map<java.lang.reflect.TypeVariable<?>, Class<?>> inherited = new LinkedHashMap<>();
            if (parent instanceof java.lang.reflect.ParameterizedType applied) {
                java.lang.reflect.TypeVariable<?>[] variables = raw.getTypeParameters();
                Type[] arguments = applied.getActualTypeArguments();
                for (int i = 0; i < variables.length; i++)
                    inherited.put(variables[i], erasedType(arguments[i], bindings));
            }
            for (Method original : raw.getDeclaredMethods()) {
                if (original.isBridge() || !original.getName().equals(bridge.getName())
                        || !Arrays.equals(original.getParameterTypes(), bridge.getParameterTypes())) continue;
                Class<?>[] parameters = Arrays.stream(original.getGenericParameterTypes())
                        .map(type -> erasedType(type, inherited)).toArray(Class<?>[]::new);
                if (Arrays.equals(parameters, target.getParameterTypes())) return true;
            }
            if (overridesBridgeSource(raw, inherited, bridge, target)) return true;
        }
        return false;
    }

    private static Class<?> erasedType(Type type,
            Map<java.lang.reflect.TypeVariable<?>, Class<?>> bindings) {
        if (type instanceof Class<?> clazz) return clazz;
        if (type instanceof java.lang.reflect.ParameterizedType applied)
            return (Class<?>) applied.getRawType();
        if (type instanceof java.lang.reflect.TypeVariable<?> variable) {
            Class<?> bound = bindings.get(variable);
            return bound != null ? bound : erasedType(variable.getBounds()[0], bindings);
        }
        if (type instanceof java.lang.reflect.GenericArrayType array)
            return java.lang.reflect.Array.newInstance(erasedType(array.getGenericComponentType(), bindings), 0)
                    .getClass();
        return Object.class;
    }
}
