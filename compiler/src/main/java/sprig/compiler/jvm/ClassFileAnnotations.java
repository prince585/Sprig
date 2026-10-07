package sprig.compiler.jvm;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The nullability annotations of one class file, read from its annotation
 * attributes rather than through reflection, so that CLASS-retention
 * annotations count too: {@code org.jetbrains.annotations}, the Android and
 * Eclipse ones live only in the class file and are invisible to
 * {@code getAnnotations()}. Both the visible and the invisible attributes are
 * read, on fields, method results and parameters, as declaration annotations
 * and as type annotations on the top-level type (a {@code List<@Nullable
 * String>} says nothing about the list). Annotations are matched by simple
 * name, exactly as {@link JvmNullability} matches the reflected ones, and an
 * annotation class missing from the classpath is still recognised by name.
 * A class that cannot be read yields no verdicts.
 */
final class ClassFileAnnotations {
    private static final Set<String> NON_NULL = Set.of("NotNull", "NonNull", "Nonnull");
    private static final Set<String> NULLABLE = Set.of("Nullable", "CheckForNull");
    private static final ClassFileAnnotations EMPTY = new ClassFileAnnotations();
    private static final Map<Class<?>, ClassFileAnnotations> CACHE = new ConcurrentHashMap<>();

    /** TRUE non-null, FALSE nullable, keyed by "name descriptor". */
    private final Map<String, Boolean> members = new HashMap<>();
    /** Per-parameter verdicts, keyed by "name descriptor" then parameter index. */
    private final Map<String, Map<Integer, Boolean>> parameters = new HashMap<>();

    private ClassFileAnnotations() {}

    static ClassFileAnnotations of(Class<?> clazz) {
        if (clazz == null || clazz.isPrimitive() || clazz.isArray() || clazz.getClassLoader() == null) {
            return EMPTY; // JDK classes carry no nullability annotations; skip the parse
        }
        return CACHE.computeIfAbsent(clazz, ClassFileAnnotations::parse);
    }

    /** The verdict for a method result, or null when the class file says nothing. */
    Boolean result(Method method) {
        return members.get(method.getName() + " " + descriptor(method));
    }

    /** The verdict for a field, or null. */
    Boolean field(Field field) {
        return members.get(field.getName() + " " + descriptor(field.getType()));
    }

    /** The verdict for one parameter, or null. */
    Boolean parameter(Executable executable, int index) {
        String name = executable instanceof Constructor<?> ? "<init>" : executable.getName();
        Map<Integer, Boolean> byIndex = parameters.get(name + " " + descriptor(executable));
        return byIndex == null ? null : byIndex.get(index);
    }

    // ------------------------------------------------------------------
    // Descriptors from reflection, so a member can be looked up by its class-file key
    // ------------------------------------------------------------------

    static String descriptor(Executable executable) {
        StringBuilder out = new StringBuilder("(");
        for (Class<?> param : executable.getParameterTypes()) {
            out.append(descriptor(param));
        }
        out.append(')');
        return out.append(executable instanceof Method method ? descriptor(method.getReturnType()) : "V").toString();
    }

    static String descriptor(Class<?> type) {
        if (type.isArray()) {
            return "[" + descriptor(type.getComponentType());
        }
        if (type == void.class) return "V";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        return "L" + type.getName().replace('.', '/') + ";";
    }

    // ------------------------------------------------------------------
    // Class-file parsing (JVMS chapter 4): constant pool, fields, methods and
    // their annotation attributes; everything else is skipped by length.
    // ------------------------------------------------------------------

    private static ClassFileAnnotations parse(Class<?> clazz) {
        String resource = "/" + clazz.getName().replace('.', '/') + ".class";
        try (InputStream stream = clazz.getResourceAsStream(resource)) {
            if (stream == null) {
                return EMPTY;
            }
            byte[] bytes = stream.readAllBytes();
            if (bytes.length == 0) {
                return EMPTY;
            }
            ClassFileAnnotations out = new ClassFileAnnotations();
            new Reader(new DataInputStream(new java.io.ByteArrayInputStream(bytes)), out);
            return out;
        } catch (IOException | RuntimeException e) {
            return EMPTY; // an unreadable or malformed class file says nothing
        }
    }

    private static final class Reader {
        private final DataInputStream in;
        private final ClassFileAnnotations out;
        private String[] utf8;

        Reader(DataInputStream in, ClassFileAnnotations out) throws IOException {
            this.in = in;
            this.out = out;
            read();
        }

        private void read() throws IOException {
            if (in.readInt() != 0xCAFEBABE) {
                return;
            }
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int poolCount = in.readUnsignedShort();
            utf8 = new String[poolCount];
            for (int i = 1; i < poolCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        i++; // takes two entries
                    }
                    case 7, 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> in.readInt();
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    default -> throw new IOException("unknown constant pool tag " + tag);
                }
            }
            in.readUnsignedShort(); // access flags
            in.readUnsignedShort(); // this class
            in.readUnsignedShort(); // super class
            int interfaces = in.readUnsignedShort();
            in.skipNBytes(2L * interfaces);
            int fields = in.readUnsignedShort();
            for (int i = 0; i < fields; i++) {
                member(false);
            }
            int methods = in.readUnsignedShort();
            for (int i = 0; i < methods; i++) {
                member(true);
            }
            // class attributes: nothing needed
        }

        private void member(boolean method) throws IOException {
            in.readUnsignedShort(); // access flags
            String name = utf8[in.readUnsignedShort()];
            String descriptor = utf8[in.readUnsignedShort()];
            String key = name + " " + descriptor;
            int attributes = in.readUnsignedShort();
            for (int i = 0; i < attributes; i++) {
                String attribute = utf8[in.readUnsignedShort()];
                int length = in.readInt();
                switch (attribute) {
                    case "RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations" -> {
                        int count = in.readUnsignedShort();
                        for (int j = 0; j < count; j++) {
                            record(key, annotation());
                        }
                    }
                    case "RuntimeVisibleParameterAnnotations", "RuntimeInvisibleParameterAnnotations" -> {
                        int params = in.readUnsignedByte();
                        for (int p = 0; p < params; p++) {
                            int count = in.readUnsignedShort();
                            for (int j = 0; j < count; j++) {
                                recordParameter(key, p, annotation());
                            }
                        }
                    }
                    case "RuntimeVisibleTypeAnnotations", "RuntimeInvisibleTypeAnnotations" -> {
                        int count = in.readUnsignedShort();
                        for (int j = 0; j < count; j++) {
                            typeAnnotation(key, method);
                        }
                    }
                    default -> in.skipNBytes(length);
                }
            }
        }

        /** Reads one annotation structure and returns its verdict (TRUE non-null, FALSE nullable, null other). */
        private Boolean annotation() throws IOException {
            String type = utf8[in.readUnsignedShort()];
            int pairs = in.readUnsignedShort();
            for (int i = 0; i < pairs; i++) {
                in.readUnsignedShort(); // element name
                elementValue();
            }
            return verdict(type);
        }

        private void elementValue() throws IOException {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's', 'c' -> in.readUnsignedShort();
                case 'e' -> in.readInt();
                case '@' -> annotation();
                case '[' -> {
                    int values = in.readUnsignedShort();
                    for (int i = 0; i < values; i++) {
                        elementValue();
                    }
                }
                default -> throw new IOException("unknown element value tag " + tag);
            }
        }

        /** A type annotation on the top-level type of a field, a method result or a formal parameter. */
        private void typeAnnotation(String key, boolean method) throws IOException {
            int target = in.readUnsignedByte();
            int parameter = -1;
            switch (target) {
                case 0x00, 0x01 -> in.readUnsignedByte();
                case 0x10 -> in.readUnsignedShort();
                case 0x11, 0x12 -> in.readUnsignedShort();
                case 0x13, 0x14, 0x15 -> { }
                case 0x16 -> parameter = in.readUnsignedByte();
                case 0x17 -> in.readUnsignedShort();
                case 0x40, 0x41 -> in.skipNBytes(6L * in.readUnsignedShort());
                case 0x42, 0x43, 0x44, 0x45, 0x46 -> in.readUnsignedShort();
                case 0x47, 0x48, 0x49, 0x4A, 0x4B -> {
                    in.readUnsignedShort();
                    in.readUnsignedByte();
                }
                default -> throw new IOException("unknown type annotation target " + target);
            }
            int pathLength = in.readUnsignedByte();
            in.skipNBytes(2L * pathLength);
            Boolean verdict = annotation();
            if (verdict == null || pathLength != 0) {
                return; // nested type arguments say nothing about the member itself
            }
            if (target == 0x16) {
                recordParameter(key, parameter, verdict);
            } else if (target == 0x14 && method || target == 0x13 && !method) {
                record(key, verdict);
            }
        }

        private void record(String key, Boolean verdict) {
            if (verdict != null) {
                out.members.merge(key, verdict, (first, second) -> first); // nullable and non-null both present: keep the first
            }
        }

        private void recordParameter(String key, int index, Boolean verdict) {
            if (verdict != null) {
                out.parameters.computeIfAbsent(key, ignored -> new HashMap<>()).putIfAbsent(index, verdict);
            }
        }

        private static Boolean verdict(String descriptor) {
            // Lorg/jetbrains/annotations/Nullable; or Laudit/Invisible$Nullable;
            String name = descriptor;
            if (name.startsWith("L") && name.endsWith(";")) {
                name = name.substring(1, name.length() - 1);
            }
            name = name.substring(name.lastIndexOf('/') + 1);
            name = name.substring(name.lastIndexOf('$') + 1);
            if (NULLABLE.contains(name)) {
                return Boolean.FALSE;
            }
            if (NON_NULL.contains(name)) {
                return Boolean.TRUE;
            }
            return null;
        }
    }
}
