package net.teumert.record;

/**
 * Convenience interface that combines {@link Withable}, {@link Transformable},
 * and {@link Draftable} into a single mixin.
 *
 * <p>Use this when you want the full derived-creation API without thinking
 * about granularity. For finer control, implement the individual interfaces:
 *
 * <ul>
 *   <li>{@link Withable} — simple value replacement only</li>
 *   <li>{@link Transformable} — adds computed component transformation</li>
 *   <li>{@link Draftable} — adds batched multi-field derivation via {@link Draftable.Draft}</li>
 * </ul>
 *
 * <pre>{@code
 * public record Person(String name, int age) implements Copyable<Person> {}
 *
 * var duke = new Person("Duke", 28);
 *
 * // Withable
 * var duchess = duke.with(Person::name, "Duchess");
 *
 * // Transformable
 * var older = duke.transform(Person::age, (old, self) -> old + 1);
 *
 * // Draftable
 * var other = duke.draft()
 *     .set(Person::name, "Mocha")
 *     .set(Person::age, 3)
 *     .construct();
 * }</pre>
 *
 * @param <R> the concrete record type (CRTP)
 */
public interface Copyable<R extends Record>
        extends Withable<R>, Transformable<R>, Draftable<R> {
}
