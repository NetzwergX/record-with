package net.teumert.record;

/**
 * Thrown when a record copy operation fails.
 *
 * <p>Common causes include inaccessible components, canonical constructor
 * validation failures, or passing a non-method-reference lambda to
 * {@link Copyable#with}.
 */
public class RecordCopyException extends RuntimeException {

    public RecordCopyException(String message) {
        super(message);
    }

    public RecordCopyException(String message, Throwable cause) {
        super(message, cause);
    }
}
