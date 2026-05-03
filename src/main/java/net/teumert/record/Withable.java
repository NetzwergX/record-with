package net.teumert.record;

/**
 * Mixin interface for records that enables type-safe derived creation
 * via simple value replacement.
 *
 * <pre>{@code
 * public record Person(String name, int age) implements Withable<Person> {}
 *
 * var duke = new Person("Duke", 28);
 * var duchess  = duke.with(Person::name, "Duchess");
 * var chained = duke.with(Person::name, "Mocha").with(Person::age, 3);
 * }</pre>
 *
 * <p>The canonical constructor is always invoked, so validation constraints
 * are respected. Only method references ({@code Record::component}) are
 * supported as selectors.
 *
 * @param <R> the concrete record type (CRTP)
 * @see Transformable
 * @see Draftable
 * @see Copyable
 */
public interface Withable<R extends Record> {

    /**
     * Returns a copy of this record with the selected component replaced
     * by the given value.
     *
     * @param accessor method reference to the component accessor
     *                 ({@code Record::component})
     * @param value    the new value
     * @param <T>      the component type
     * @return a new record instance with the component replaced
     * @throws RecordCopyException if the accessor is not a method reference,
     *         the component does not exist, or the canonical constructor
     *         rejects the resulting values
     */
    @SuppressWarnings("unchecked")
    default <T> R with(SerializableFunction<R, T> accessor, T value) {
        var self = (R) this;
        var meta = RecordIntrospector.metaFor((Class<R>) self.getClass());
        var index = RecordIntrospector.resolveComponentIndex(meta, accessor);

        var values = RecordIntrospector.readComponents(self, meta);
        values[index] = value;
        return RecordIntrospector.construct(meta, values);
    }
}
