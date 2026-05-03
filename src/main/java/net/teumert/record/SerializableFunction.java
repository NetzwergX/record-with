package net.teumert.record;

import java.io.Serializable;
import java.util.function.Function;

/**
 * A {@link Function} that is also {@link Serializable}, enabling
 * introspection of method references via {@link java.lang.invoke.SerializedLambda}.
 *
 * <p>Used as the accessor selector in {@link Copyable#with} to provide
 * type-safe, compiler-checked field selection without any new language constructs.
 *
 * <p>Example usage: {@code Person::name} passed as a {@code SerializableFunction<Person, String>}.
 *
 * @param <R> the record type
 * @param <T> the component (return) type
 */
@FunctionalInterface
public interface SerializableFunction<R, T> extends Function<R, T>, Serializable {
}
