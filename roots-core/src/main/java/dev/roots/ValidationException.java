package dev.roots;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A request validation failure with immutable, field-addressable messages. */
public final class ValidationException extends IllegalArgumentException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;
    private static final int MAX_FIELDS = 128;
    private static final int MAX_FIELD_LENGTH = 128;
    private static final int MAX_ERRORS_PER_FIELD = 16;
    private static final int MAX_TOTAL_ERRORS = 256;
    private static final int MAX_MESSAGE_LENGTH = 2_048;
    /** Immutable messages keyed by submitted field. */
    private final Map<String, List<String>> fieldErrors;

    /** Creates a validation failure with the default summary.
     * @param fieldErrors messages keyed by submitted field */
    public ValidationException(Map<String, List<String>> fieldErrors) {
        this("Validation failed", fieldErrors);
    }

    /** Creates a validation failure.
     * @param message summary message
     * @param fieldErrors messages keyed by submitted field */
    public ValidationException(String message, Map<String, List<String>> fieldErrors) {
        super(validationMessage(message));
        Objects.requireNonNull(fieldErrors, "fieldErrors");
        if (fieldErrors.isEmpty()) {
            throw new IllegalArgumentException("At least one field validation error is required");
        }
        if (fieldErrors.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("Validation failures may contain at most 128 fields");
        }
        var copy = new LinkedHashMap<String, List<String>>();
        var totalErrors = 0;
        fieldErrors.forEach((field, errors) -> {
            if (field == null || field.isBlank() || field.length() > MAX_FIELD_LENGTH
                    || field.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(
                        "Validation field names must be printable, non-blank, and at most 128 characters"
                );
            }
            var copiedErrors = List.copyOf(Objects.requireNonNull(errors, "field errors"));
            if (copiedErrors.isEmpty() || copiedErrors.size() > MAX_ERRORS_PER_FIELD
                    || copiedErrors.stream().anyMatch(error -> error == null || error.isBlank()
                    || error.length() > MAX_MESSAGE_LENGTH)) {
                throw new IllegalArgumentException(
                        "Validation fields require 1 to 16 non-blank messages of at most 2048 characters"
                );
            }
            copy.put(field, copiedErrors);
        });
        for (var errors : copy.values()) {
            totalErrors += errors.size();
        }
        if (totalErrors > MAX_TOTAL_ERRORS) {
            throw new IllegalArgumentException("Validation failures may contain at most 256 messages");
        }
        this.fieldErrors = Collections.unmodifiableMap(copy);
    }

    /** Creates a validation failure for one field.
     * @param field submitted field name
     * @param message validation message
     * @return validation failure */
    public static ValidationException field(String field, String message) {
        return new ValidationException(Map.of(field, List.of(message)));
    }

    /** Returns immutable messages keyed by submitted field.
     * @return field errors */
    public Map<String, List<String>> fieldErrors() {
        return fieldErrors;
    }

    private static String validationMessage(String message) {
        var resolved = message == null || message.isBlank() ? "Validation failed" : message;
        if (resolved.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("Validation messages may contain at most 2048 characters");
        }
        return resolved;
    }
}
