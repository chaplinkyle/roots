package dev.roots.validation;

import dev.roots.UploadedFile;
import dev.roots.ValidationException;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Binds immutable submitted fields to validated Java records. */
public final class FormBinder {
    private static final int MAX_COMPONENTS = 128;
    private static final int MAX_FIELD_LENGTH = 128;
    private static final int MAX_SUMMARY_LENGTH = 2_048;
    private static final ClassValue<RecordSchema> SCHEMAS = new ClassValue<>() {
        @Override
        protected RecordSchema computeValue(Class<?> type) {
            return compile(type);
        }
    };

    private FormBinder() {
    }

    /**
     * Binds submitted text values to a record.
     *
     * @param values immutable or mutable submitted values
     * @param recordType target record type
     * @param <T> record type
     * @return constructed, validated record
     * @throws ValidationException when conversion or constraints reject submitted fields
     */
    public static <T> T bind(Map<String, List<String>> values, Class<T> recordType) {
        return bind(values, Map.of(), recordType);
    }

    /**
     * Binds submitted text values and uploaded files to a record.
     *
     * @param values immutable or mutable submitted values
     * @param files immutable or mutable submitted files
     * @param recordType target record type
     * @param <T> record type
     * @return constructed, validated record
     * @throws ValidationException when conversion or constraints reject submitted fields
     */
    public static <T> T bind(
            Map<String, List<String>> values,
            Map<String, List<UploadedFile>> files,
            Class<T> recordType
    ) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(files, "files");
        Objects.requireNonNull(recordType, "recordType");
        var schema = SCHEMAS.get(recordType);
        var arguments = new Object[schema.fields().size()];
        var errors = new LinkedHashMap<String, List<String>>();
        for (var index = 0; index < schema.fields().size(); index++) {
            var field = schema.fields().get(index);
            final Object value;
            try {
                value = field.converter().convert(
                        safeValues(values, field.name(), "text"),
                        safeValues(files, field.name(), "file")
                );
            } catch (ConversionFailure failure) {
                errors.put(field.name(), List.of(failure.getMessage()));
                continue;
            }
            arguments[index] = value;
            var fieldErrors = new ArrayList<String>();
            for (var constraint : field.constraints()) {
                validate(constraint, value).ifPresent(fieldErrors::add);
            }
            if (!fieldErrors.isEmpty()) {
                errors.put(field.name(), List.copyOf(fieldErrors));
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(schema.message(), errors);
        }
        return recordType.cast(construct(schema.constructor(), arguments));
    }

    private static <T> List<T> safeValues(Map<String, List<T>> values, String name, String description) {
        var result = values.getOrDefault(name, List.of());
        Objects.requireNonNull(result, description + " values for " + name);
        result.forEach(value -> Objects.requireNonNull(value, description + " value for " + name));
        return result;
    }

    private static RecordSchema compile(Class<?> type) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException("Form binding requires a record type: " + type.getName());
        }
        var components = type.getRecordComponents();
        if (components.length > MAX_COMPONENTS) {
            throw new IllegalArgumentException("Form records may contain at most 128 components");
        }
        var model = type.getAnnotation(FormModel.class);
        var message = model == null ? "Validation failed" : model.message();
        if (message == null || message.isBlank() || message.length() > MAX_SUMMARY_LENGTH) {
            throw new IllegalArgumentException("Form validation summaries must contain 1 to 2048 characters");
        }
        var parameterTypes = java.util.Arrays.stream(components).map(RecordComponent::getType).toArray(Class[]::new);
        final Constructor<?> constructor;
        try {
            constructor = type.getDeclaredConstructor(parameterTypes);
        } catch (NoSuchMethodException exception) {
            throw new IllegalArgumentException("Could not find the canonical record constructor: " + type.getName(), exception);
        }
        if (!constructor.trySetAccessible()) {
            throw new IllegalArgumentException("The canonical record constructor is not accessible: " + type.getName());
        }
        var names = new java.util.LinkedHashSet<String>();
        var fields = new ArrayList<FieldBinding>();
        for (var component : components) {
            var mapping = component.getAnnotation(FormField.class);
            var name = mapping == null || mapping.value().isBlank() ? component.getName() : mapping.value();
            validateFieldName(name);
            if (!names.add(name)) {
                throw new IllegalArgumentException("Duplicate form field mapping: " + name);
            }
            var trim = mapping != null && mapping.trim();
            fields.add(new FieldBinding(
                    name,
                    converter(component.getGenericType(), trim, name),
                    constraints(component)
            ));
        }
        return new RecordSchema(constructor, List.copyOf(fields), message);
    }

    private static void validateFieldName(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_FIELD_LENGTH
                || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "Form field names must be printable, non-blank, and at most 128 characters"
            );
        }
    }

    private static List<BoundConstraint> constraints(RecordComponent component) {
        var constraints = new ArrayList<BoundConstraint>();
        for (var annotation : component.getAnnotations()) {
            var declaration = annotation.annotationType().getAnnotation(FormConstraint.class);
            if (declaration == null) {
                continue;
            }
            final ConstraintValidator<?> validator;
            try {
                var constructor = declaration.validatedBy().getDeclaredConstructor();
                if (!constructor.trySetAccessible()) {
                    throw new IllegalArgumentException("Constraint validator constructor is not accessible: "
                            + declaration.validatedBy().getName());
                }
                validator = constructor.newInstance();
            } catch (ReflectiveOperationException exception) {
                throw new IllegalArgumentException("Could not create constraint validator: "
                        + declaration.validatedBy().getName(), exception);
            }
            constraints.add(new BoundConstraint(annotation, validator));
        }
        return List.copyOf(constraints);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Optional<String> validate(BoundConstraint constraint, Object value) {
        var result = (Optional<String>) ((ConstraintValidator) constraint.validator())
                .validate(value, constraint.annotation());
        if (result == null) {
            throw new IllegalArgumentException("Constraint validators must return Optional.empty() when valid");
        }
        return result.map(message -> {
            if (message.isBlank()) {
                throw new IllegalArgumentException("Constraint validators must return non-blank messages");
            }
            return message;
        });
    }

    private static Converter converter(Type type, boolean trim, String field) {
        if (type instanceof ParameterizedType parameterized) {
            if (!(parameterized.getRawType() instanceof Class<?> raw)
                    || parameterized.getActualTypeArguments().length != 1
                    || !(parameterized.getActualTypeArguments()[0] instanceof Class<?> itemType)) {
                throw unsupported(type, field);
            }
            if (raw == Optional.class) {
                var scalar = scalar(itemType, trim, field);
                return (text, files) -> {
                    if (itemType == UploadedFile.class && files.isEmpty()) {
                        return Optional.empty();
                    }
                    if (itemType != UploadedFile.class) {
                        requireSingle(text.size(), field);
                        if (text.isEmpty() || normalized(text.getFirst(), trim).isBlank()) {
                            return Optional.empty();
                        }
                    }
                    return Optional.of(scalar.convert(text, files));
                };
            }
            if (raw == List.class) {
                if (itemType == UploadedFile.class) {
                    return (text, files) -> List.copyOf(files);
                }
                var item = textScalar(itemType, trim, field);
                return (text, files) -> text.stream().map(value -> {
                    try {
                        return item.convert(List.of(value), List.of());
                    } catch (ConversionFailure failure) {
                        throw failure;
                    }
                }).toList();
            }
            throw unsupported(type, field);
        }
        if (!(type instanceof Class<?> raw)) {
            throw unsupported(type, field);
        }
        return scalar(raw, trim, field);
    }

    private static Converter scalar(Class<?> type, boolean trim, String field) {
        if (type == UploadedFile.class) {
            return (text, files) -> {
                requireSingle(files.size(), field);
                if (files.isEmpty()) {
                    throw new ConversionFailure("Select a file.");
                }
                return files.getFirst();
            };
        }
        return textScalar(type, trim, field);
    }

    private static Converter textScalar(Class<?> type, boolean trim, String field) {
        if (!supportedTextType(type)) {
            throw unsupported(type, field);
        }
        return (text, files) -> {
            requireSingle(text.size(), field);
            if (type == boolean.class || type == Boolean.class) {
                return text.isEmpty() ? false : parseBoolean(normalized(text.getFirst(), trim));
            }
            var value = text.isEmpty() ? "" : normalized(text.getFirst(), trim);
            if (type == String.class) {
                return value;
            }
            if (value.isBlank()) {
                throw new ConversionFailure("Enter a value.");
            }
            try {
                if (type == byte.class || type == Byte.class) return Byte.valueOf(value);
                if (type == short.class || type == Short.class) return Short.valueOf(value);
                if (type == int.class || type == Integer.class) return Integer.valueOf(value);
                if (type == long.class || type == Long.class) return Long.valueOf(value);
                if (type == float.class || type == Float.class) {
                    var parsed = Float.valueOf(value);
                    if (!Float.isFinite(parsed)) throw new NumberFormatException();
                    return parsed;
                }
                if (type == double.class || type == Double.class) {
                    var parsed = Double.valueOf(value);
                    if (!Double.isFinite(parsed)) throw new NumberFormatException();
                    return parsed;
                }
                if (type == BigInteger.class) return new BigInteger(value);
                if (type == BigDecimal.class) return new BigDecimal(value);
                if (type == UUID.class) return UUID.fromString(value);
                if (type == LocalDate.class) return LocalDate.parse(value);
                if (type == LocalDateTime.class) return LocalDateTime.parse(value);
                if (type == OffsetDateTime.class) return OffsetDateTime.parse(value);
                if (type == Instant.class) return Instant.parse(value);
                if (type.isEnum()) return enumValue(type, value);
            } catch (ConversionFailure failure) {
                throw failure;
            } catch (RuntimeException exception) {
                throw new ConversionFailure(conversionMessage(type));
            }
            throw new AssertionError("Unhandled supported form type: " + type.getName());
        };
    }

    private static boolean supportedTextType(Class<?> type) {
        return type == String.class
                || type == boolean.class || type == Boolean.class
                || type == byte.class || type == Byte.class
                || type == short.class || type == Short.class
                || type == int.class || type == Integer.class
                || type == long.class || type == Long.class
                || type == float.class || type == Float.class
                || type == double.class || type == Double.class
                || type == BigInteger.class || type == BigDecimal.class
                || type == UUID.class || type == LocalDate.class
                || type == LocalDateTime.class || type == OffsetDateTime.class
                || type == Instant.class || type.isEnum();
    }

    private static Object parseBoolean(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "on", "1", "yes" -> true;
            case "false", "off", "0", "no" -> false;
            default -> throw new ConversionFailure("Select a valid true or false value.");
        };
    }

    private static Object enumValue(Class<?> type, String value) {
        for (var constant : type.getEnumConstants()) {
            if (((Enum<?>) constant).name().equalsIgnoreCase(value)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("Unknown enum value");
    }

    private static String conversionMessage(Class<?> type) {
        if (Number.class.isAssignableFrom(type) || type.isPrimitive()) return "Enter a valid number.";
        if (type == UUID.class) return "Enter a valid identifier.";
        if (type == LocalDate.class) return "Enter a valid date.";
        if (type == LocalDateTime.class || type == OffsetDateTime.class || type == Instant.class) {
            return "Enter a valid date and time.";
        }
        if (type.isEnum()) return "Select a valid value.";
        return "Enter a valid value.";
    }

    private static String normalized(String value, boolean trim) {
        Objects.requireNonNull(value, "submitted value");
        return trim ? value.strip() : value;
    }

    private static void requireSingle(int count, String field) {
        if (count > 1) {
            throw new ConversionFailure("Submit one value for " + field + ".");
        }
    }

    private static IllegalArgumentException unsupported(Type type, String field) {
        return new IllegalArgumentException("Unsupported form component type for " + field + ": " + type.getTypeName());
    }

    private static Object construct(Constructor<?> constructor, Object[] arguments) {
        try {
            return constructor.newInstance(arguments);
        } catch (InvocationTargetException exception) {
            switch (exception.getCause()) {
                case RuntimeException runtime -> throw runtime;
                case Error error -> throw error;
                case Throwable cause -> throw new IllegalStateException("Form record constructor failed", cause);
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not construct form record", exception);
        }
    }

    @FunctionalInterface
    private interface Converter {
        Object convert(List<String> text, List<UploadedFile> files);
    }

    private record RecordSchema(Constructor<?> constructor, List<FieldBinding> fields, String message) {
    }

    private record FieldBinding(String name, Converter converter, List<BoundConstraint> constraints) {
    }

    private record BoundConstraint(Annotation annotation, ConstraintValidator<?> validator) {
    }

    private static final class ConversionFailure extends IllegalArgumentException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        private ConversionFailure(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}
