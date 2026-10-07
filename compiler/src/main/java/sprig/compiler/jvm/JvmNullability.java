package sprig.compiler.jvm;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

/**
 * Nullability declared by annotations that are visible at run time, matched
 * by simple name so every library's spelling counts: NotNull, NonNull and
 * Nonnull or Nullable and CheckForNull on a method result, a parameter or a
 * field (as a declaration or a type-use annotation), and the class, package
 * or module defaults NullMarked, NonNullApi, MethodsReturnNonnullByDefault
 * and FieldsAreNonnullByDefault, which NullUnmarked cancels. An annotation
 * kept only in class files (CLASS retention, as org.jetbrains.annotations
 * and the Android ones are) is invisible to reflection, so the declaring
 * class's file is read as well ({@link ClassFileAnnotations}). Parameters
 * stay non-null unless annotated nullable, whatever the defaults say,
 * because Sprig never hands Java a null it did not ask for.
 */
public final class JvmNullability {
    private static final Set<String> NON_NULL = Set.of("NotNull", "NonNull", "Nonnull");
    private static final Set<String> NULLABLE = Set.of("Nullable", "CheckForNull");
    private static final Set<String> RESULTS_NON_NULL =
            Set.of("NullMarked", "NonNullApi", "MethodsReturnNonnullByDefault");
    private static final Set<String> FIELDS_NON_NULL =
            Set.of("NullMarked", "NonNullApi", "FieldsAreNonnullByDefault");

    private JvmNullability() {}

    /** A result declared non-null, by annotation or by a default on its class or package. */
    public static boolean returnsNonNull(Method method) {
        if (method.getReturnType().isPrimitive()) {
            return true;
        }
        Boolean explicit = explicit(method, method.getAnnotatedReturnType());
        if (explicit == null) {
            explicit = ClassFileAnnotations.of(method.getDeclaringClass()).result(method);
        }
        return explicit != null ? explicit : defaults(method.getDeclaringClass(), RESULTS_NON_NULL);
    }

    /** A field declared non-null, by annotation or by a default on its class or package. */
    public static boolean fieldNonNull(Field field) {
        if (field.getType().isPrimitive()) {
            return true;
        }
        Boolean explicit = explicit(field, field.getAnnotatedType());
        if (explicit == null) {
            explicit = ClassFileAnnotations.of(field.getDeclaringClass()).field(field);
        }
        return explicit != null ? explicit : defaults(field.getDeclaringClass(), FIELDS_NON_NULL);
    }

    /** A reference parameter annotated nullable: a {@code T?} or {@code null} may be passed. */
    public static boolean parameterNullable(Executable executable, int index) {
        if (index < 0 || index >= executable.getParameterCount()
                || executable.getParameterTypes()[index].isPrimitive()) {
            return false;
        }
        Annotation[][] declared = executable.getParameterAnnotations();
        AnnotatedType[] typeUse = executable.getAnnotatedParameterTypes();
        Boolean explicit = explicit(index < declared.length ? declared[index] : new Annotation[0],
                index < typeUse.length ? typeUse[index].getAnnotations() : new Annotation[0]);
        if (explicit == null) {
            explicit = ClassFileAnnotations.of(executable.getDeclaringClass()).parameter(executable, index);
        }
        return explicit != null && !explicit;
    }

    /** True for non-null, false for nullable, null when the element says nothing. */
    private static Boolean explicit(AnnotatedElement declaration, AnnotatedType type) {
        return explicit(declaration.getAnnotations(), type.getAnnotations());
    }

    private static Boolean explicit(Annotation[] declaration, Annotation[] typeUse) {
        Boolean verdict = verdict(typeUse);
        return verdict != null ? verdict : verdict(declaration);
    }

    private static Boolean verdict(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            String name = annotation.annotationType().getSimpleName();
            if (NULLABLE.contains(name)) {
                return Boolean.FALSE;
            }
            if (NON_NULL.contains(name)) {
                return Boolean.TRUE;
            }
        }
        return null;
    }

    /** A default on the class, an enclosing class, the package or the module; NullUnmarked cancels it. */
    private static boolean defaults(Class<?> declaring, Set<String> markers) {
        for (Class<?> current = declaring; current != null; current = current.getEnclosingClass()) {
            Boolean marked = marked(current.getAnnotations(), markers);
            if (marked != null) {
                return marked;
            }
        }
        Package pkg = declaring.getPackage();
        if (pkg != null) {
            Boolean marked = marked(pkg.getAnnotations(), markers);
            if (marked != null) {
                return marked;
            }
        }
        Module module = declaring.getModule();
        if (module != null && module.isNamed()) {
            Boolean marked = marked(module.getAnnotations(), markers);
            if (marked != null) {
                return marked;
            }
        }
        return false;
    }

    private static Boolean marked(Annotation[] annotations, Set<String> markers) {
        for (Annotation annotation : annotations) {
            String name = annotation.annotationType().getSimpleName();
            if (name.equals("NullUnmarked")) {
                return Boolean.FALSE;
            }
            if (markers.contains(name)) {
                return Boolean.TRUE;
            }
        }
        return null;
    }
}
