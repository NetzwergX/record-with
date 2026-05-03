package net.teumert.record;

import java.util.function.BiFunction;

/**
 * Mixin interface for records that enables computed component
 * transformation during derived creation.
 *
 * <pre>{@code
 * public record Point(int x, int y) implements Transformable<Point> {}
 *
 * var p = new Point(3, 7);
 *
 * // Transform based on old value
 * var doubled = p.transform(Point::x, (old, self) -> old * 2);
 *
 * // Cross-field computation
 * var swapped = p.transform(Point::x, (old, self) -> self.y());
 * }</pre>
 *
 * <p>The transformer receives two arguments:
 * <ol>
 *   <li>{@code old} — the current value of the component being changed</li>
 *   <li>{@code self} — a reference to the entire (unmodified) record</li>
 * </ol>
 *
 * @param <R> the concrete record type (CRTP)
 * @see Withable
 * @see Draftable
 * @see Copyable
 */
public interface Transformable<R extends Record> {

    /**
     * Returns a copy of this record with the selected component transformed
     * by the given function.
     *
     * @param accessor    method reference to the component accessor
     * @param transformer {@code (old, self) -> newValue}
     * @param <T>         the component type
     * @return a new record instance with the component transformed
     * @throws RecordCopyException if the accessor is not a method reference,
     *         the component does not exist, or the canonical constructor
     *         rejects the resulting values
     */
    @SuppressWarnings("unchecked")
    default <T> R transform(SerializableFunction<R, T> accessor,
                             BiFunction<T, R, T> transformer) {
        var self = (R) this;
        var meta = RecordIntrospector.metaFor((Class<R>) self.getClass());
        var index = RecordIntrospector.resolveComponentIndex(meta, accessor);

        var values = RecordIntrospector.readComponents(self, meta);
        var oldValue = (T) values[index];
        values[index] = transformer.apply(oldValue, self);
        return RecordIntrospector.construct(meta, values);
    }
}
