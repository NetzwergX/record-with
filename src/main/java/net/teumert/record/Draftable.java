package net.teumert.record;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Mixin interface for records that enables batched derived creation
 * via a {@link Draft} builder.
 *
 * <p>Use this when multiple components need to change together —
 * for example, when the canonical constructor enforces cross-component
 * constraints that would reject valid intermediate states.
 *
 * <pre>{@code
 * public record Bounded(int lo, int hi) implements Draftable<Bounded> {
 *     public Bounded {
 *         if (lo >= hi) throw new IllegalArgumentException();
 *     }
 * }
 *
 * // Single canonical constructor call — no intermediate validation:
 * var b2 = b.draft()
 *           .set(Bounded::lo, 15)
 *           .set(Bounded::hi, 20)
 *           .construct();
 * }</pre>
 *
 * @param <R> the concrete record type (CRTP)
 * @see Withable
 * @see Transformable
 * @see Copyable
 */
public interface Draftable<R extends Record> {

    /**
     * Returns a {@link Draft} builder seeded with this record's values.
     *
     * <p>The draft collects changes via {@link Draft#set} and
     * {@link Draft#transform}, then invokes the canonical constructor
     * exactly once on {@link Draft#construct()}.
     *
     * @return a new draft builder
     */
    @SuppressWarnings("unchecked")
    default Draft<R> draft() {
        var self = (R) this;
        var meta = RecordIntrospector.metaFor((Class<R>) self.getClass());
        return new Draft<>(self, meta);
    }

    // ──────────────────────────────────────────────────────────────
    //  Draft builder
    // ──────────────────────────────────────────────────────────────

    /**
     * Collects component changes and invokes the canonical constructor
     * exactly once on {@link #construct()}.
     *
     * <p>All transformers see the <em>original</em> record — changes from
     * other {@code set}/{@code transform} calls are not visible to them.
     * This eliminates order-dependence between calls.
     *
     * <pre>{@code
     * var swapped = point.draft()
     *     .transform(Point::x, (old, self) -> self.y())
     *     .transform(Point::y, (old, self) -> self.x())
     *     .construct();
     * }</pre>
     *
     * @param <R> the record type
     */
    final class Draft<R extends Record> {

        private final R origin;
        private final RecordIntrospector.RecordMeta<R> meta;

        /**
         * Stored overrides, keyed by component index.  Each function
         * takes the original record and produces the new component value.
         */
        private final Map<Integer, Function<R, Object>> overrides = new LinkedHashMap<>();

        Draft(R origin, RecordIntrospector.RecordMeta<R> meta) {
            this.origin = origin;
            this.meta = meta;
        }

        /**
         * Sets a component to a fixed value.
         *
         * @param accessor method reference to the component
         * @param value    the new value
         * @param <T>      the component type
         * @return this draft
         */
        public <T> Draft<R> set(SerializableFunction<R, T> accessor, T value) {
            var index = RecordIntrospector.resolveComponentIndex(meta, accessor);
            overrides.put(index, rec -> value);
            return this;
        }

        /**
         * Transforms a component via a function.
         *
         * <p>The transformer receives the <em>original</em> component value
         * and the <em>original</em> record, regardless of other changes
         * already registered on this draft.
         *
         * @param accessor    method reference to the component
         * @param transformer {@code (old, self) -> newValue}
         * @param <T>         the component type
         * @return this draft
         */
        @SuppressWarnings("unchecked")
        public <T> Draft<R> transform(SerializableFunction<R, T> accessor,
                                       BiFunction<T, R, T> transformer) {
            var index = RecordIntrospector.resolveComponentIndex(meta, accessor);
            overrides.put(index, rec -> {
                try {
                    var oldValue = (T) (Object) meta.accessorHandles()[index]
                            .invokeExact((Record) rec);
                    return transformer.apply(oldValue, rec);
                } catch (Throwable e) {
                    throw new RecordCopyException(
                            "Failed to read component at index " + index, e);
                }
            });
            return this;
        }

        /**
         * Constructs the derived record.
         *
         * <p>Reads all component values from the original, applies
         * overrides, and invokes the canonical constructor exactly once.
         *
         * @return a new record instance
         * @throws RecordCopyException if the canonical constructor rejects
         *         the resulting values
         */
        public R construct() {
            var values = RecordIntrospector.readComponents(origin, meta);
            for (var entry : overrides.entrySet()) {
                values[entry.getKey()] = entry.getValue().apply(origin);
            }
            return RecordIntrospector.construct(meta, values);
        }
    }
}
