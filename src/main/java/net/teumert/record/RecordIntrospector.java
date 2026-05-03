package net.teumert.record;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.toUnmodifiableMap;

/**
 * Caches reflective metadata for record classes to avoid repeated lookups.
 *
 * <p>This is the performance-critical internals.  Two levels of caching
 * eliminate reflective overhead on the hot path:
 *
 * <ol>
 *   <li><b>Record metadata cache</b> — each record class is analyzed once.
 *       {@link MethodHandle}s for accessors and the canonical constructor are
 *       adapted at build time for {@code invokeExact} compatibility, giving
 *       the JIT the narrowest possible types to inline through.</li>
 *   <li><b>Lambda-class name cache</b> — each method reference (e.g.
 *       {@code Point::x}) has a stable synthetic class.  The mapping from
 *       that class to the component name is resolved via
 *       {@link SerializedLambda} once, then cached.  Subsequent calls
 *       are a {@link ConcurrentHashMap#get}.</li>
 * </ol>
 *
 * <p><b>Note on the {@link SerializedLambda} approach:</b> Extracting the
 * implementation method name from a serializable lambda is a well-known but
 * unsupported technique.  It works reliably with method references
 * ({@code Record::component}) but not with arbitrary lambdas.
 * A future version of this library may migrate to Project Babylon's
 * code reflection API ({@code @CodeReflection}) once it stabilizes,
 * providing a supported path to the same information.
 */
final class RecordIntrospector {

    /** Per-class cache of record metadata. */
    private static final ConcurrentHashMap<Class<?>, RecordMeta<?>> META_CACHE
            = new ConcurrentHashMap<>();

    /**
     * Per-lambda-class cache of method reference names.
     *
     * <p>Each method reference like {@code Point::x} compiles to a synthetic
     * class that is stable per call site.  We resolve the component name
     * via {@link SerializedLambda} once, then cache it keyed on that class.
     * This eliminates the dominant hot-path cost: no {@code writeReplace}
     * reflection on subsequent calls.
     */
    private static final ConcurrentHashMap<Class<?>, String> LAMBDA_NAME_CACHE
            = new ConcurrentHashMap<>();

    private RecordIntrospector() {}

    /**
     * Metadata for a single record class, computed once and cached.
     *
     * <p>All {@link MethodHandle}s are adapted at build time for
     * {@code invokeExact} compatibility:
     * <ul>
     *   <li>Accessor handles are widened to {@code (Record) → Object}</li>
     *   <li>The constructor is spread to {@code (Object[]) → Object}</li>
     * </ul>
     * This gives HotSpot's C2 compiler the narrowest call-site signatures
     * to optimize through.
     *
     * @param <R>              the record type
     * @param recordClass      the record class
     * @param components       the record components in declaration order
     * @param accessorHandles  MethodHandles adapted to {@code (Record) → Object}
     * @param constructorSpreader  MethodHandle adapted to {@code (Object[]) → Object}
     * @param componentIndex   map from component name to its positional index
     */
    record RecordMeta<R extends Record>(
            Class<R> recordClass,
            RecordComponent[] components,
            MethodHandle[] accessorHandles,
            MethodHandle constructorSpreader,
            Map<String, Integer> componentIndex
    ) {}

    /**
     * Returns cached metadata for the given record class, computing it on first access.
     */
    @SuppressWarnings("unchecked")
    static <R extends Record> RecordMeta<R> metaFor(Class<R> recordClass) {
        return (RecordMeta<R>) META_CACHE.computeIfAbsent(recordClass, RecordIntrospector::buildMeta);
    }

    @SuppressWarnings("unchecked")
    private static RecordMeta<?> buildMeta(Class<?> clazz) {
        var recordClass = (Class<? extends Record>) clazz;
        var components = recordClass.getRecordComponents();
        var lookup = MethodHandles.publicLookup();

        // Build accessor handles, adapted to (Record) -> Object for invokeExact
        var accessorHandles = new MethodHandle[components.length];
        var ctorParamTypes = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            try {
                var raw = lookup.unreflect(components[i].getAccessor());
                // Widen: (SpecificRecord) -> SpecificType  →  (Record) -> Object
                accessorHandles[i] = raw.asType(
                        MethodType.methodType(Object.class, Record.class));
            } catch (IllegalAccessException e) {
                throw new RecordCopyException(
                        "Cannot access component accessor: " + components[i].getName()
                        + " on " + recordClass.getName(), e);
            }
            ctorParamTypes[i] = components[i].getType();
        }

        // Canonical constructor, adapted to (Object[]) -> Object for invokeExact
        MethodHandle constructorSpreader;
        try {
            var raw = lookup.unreflectConstructor(
                    recordClass.getDeclaredConstructor(ctorParamTypes));

            // Widen return: (...) -> SpecificRecord  →  (...) -> Object
            var widened = raw.asType(raw.type().changeReturnType(Object.class));

            // Widen params: (SpecificType, ...) -> Object  →  (Object, ...) -> Object
            var paramTypes = new Class<?>[components.length];
            java.util.Arrays.fill(paramTypes, Object.class);
            widened = widened.asType(
                    MethodType.methodType(Object.class, paramTypes));

            // Spread: (Object, Object, ...) -> Object  →  (Object[]) -> Object
            constructorSpreader = widened.asSpreader(Object[].class, components.length);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RecordCopyException(
                    "Cannot access canonical constructor of " + recordClass.getName(), e);
        }

        // Name -> index map
        var componentIndex = IntStream.range(0, components.length)
                .boxed()
                .collect(toUnmodifiableMap(
                        i -> components[i].getName(),
                        i -> i));

        return new RecordMeta<>(recordClass, components, accessorHandles,
                constructorSpreader, componentIndex);
    }

    /**
     * Resolves a method reference to its component index, using the
     * lambda-class cache to avoid repeated {@link SerializedLambda}
     * introspection.
     *
     * <p>First call for a given method reference (e.g. {@code Point::x})
     * extracts the method name via {@link SerializedLambda} and caches it
     * keyed on the lambda's synthetic class.  Subsequent calls are a
     * {@link ConcurrentHashMap#get} followed by an unmodifiable map lookup.
     *
     * @param meta     cached metadata for the record class
     * @param accessor the method reference
     * @return the component index
     * @throws RecordCopyException if the accessor is not a method reference
     *         or the component does not exist
     */
    static int resolveComponentIndex(RecordMeta<?> meta,
                                     SerializableFunction<?, ?> accessor) {
        var name = LAMBDA_NAME_CACHE.computeIfAbsent(
                accessor.getClass(),
                cls -> extractMethodName(accessor));
        return resolveIndex(meta, name);
    }

    /**
     * Extracts the implementation method name from a serializable method
     * reference via {@link SerializedLambda}.
     *
     * <p>This is the cold-path operation, called once per lambda class.
     */
    private static String extractMethodName(SerializableFunction<?, ?> lambda) {
        try {
            Method writeReplace = lambda.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            var serialized = (SerializedLambda) writeReplace.invoke(lambda);
            return serialized.getImplMethodName();
        } catch (NoSuchMethodException e) {
            throw new RecordCopyException(
                    "Lambda is not a method reference. Use Record::component syntax.", e);
        } catch (ReflectiveOperationException e) {
            throw new RecordCopyException(
                    "Failed to introspect method reference.", e);
        }
    }

    /**
     * Reads all component values from a record instance using
     * {@code invokeExact} on pre-adapted handles.
     *
     * @param record the record instance
     * @param meta   cached metadata for the record's class
     * @return array of component values in declaration order
     */
    static Object[] readComponents(Record record, RecordMeta<?> meta) {
        var handles = meta.accessorHandles();
        var values = new Object[handles.length];
        for (int i = 0; i < handles.length; i++) {
            try {
                // Handle type is (Record) -> Object, matching this call site
                values[i] = (Object) handles[i].invokeExact((Record) record);
            } catch (Throwable e) {
                throw new RecordCopyException(
                        "Failed to read component: " + meta.components()[i].getName(), e);
            }
        }
        return values;
    }

    /**
     * Invokes the canonical constructor with the given component values
     * using {@code invokeExact} on the pre-adapted spreader handle.
     *
     * @param meta   cached metadata
     * @param values component values in declaration order
     * @return a new record instance
     */
    @SuppressWarnings("unchecked")
    static <R extends Record> R construct(RecordMeta<R> meta, Object[] values) {
        try {
            // Handle type is (Object[]) -> Object, matching this call site
            return (R) (Object) meta.constructorSpreader().invokeExact(values);
        } catch (Throwable e) {
            throw new RecordCopyException(
                    "Canonical constructor of " + meta.recordClass().getName()
                    + " rejected the values (validation constraint?)", e);
        }
    }

    /**
     * Resolves a component name to its index, throwing if not found.
     */
    static int resolveIndex(RecordMeta<?> meta, String componentName) {
        var index = meta.componentIndex().get(componentName);
        if (index == null) {
            throw new RecordCopyException(
                    "No component named '" + componentName
                    + "' in record " + meta.recordClass().getName()
                    + ". Available: " + meta.componentIndex().keySet());
        }
        return index;
    }
}
