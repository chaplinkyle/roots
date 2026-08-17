package dev.roots.validation;

import dev.roots.ActionEvent;
import dev.roots.Session;
import dev.roots.UploadedFile;
import dev.roots.ValidationException;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FormBinderTest {
    @Test
    void bindsTypedTextListsOptionalsEnumsAndUploadsThroughActionEvents() {
        var id = UUID.randomUUID();
        var attachment = new UploadedFile("proof.txt", "text/plain", new byte[]{1, 2, 3});
        var event = new ActionEvent(
                "submit",
                Map.ofEntries(
                        Map.entry("company_name", List.of("  Ada Lovelace  ")),
                        Map.entry("email", List.of("ada@example.com")),
                        Map.entry("age", List.of("36")),
                        Map.entry("active", List.of("on")),
                        Map.entry("tier", List.of("enterprise")),
                        Map.entry("id", List.of(id.toString())),
                        Map.entry("joined", List.of("2026-08-16")),
                        Map.entry("score", List.of("42")),
                        Map.entry("tags", List.of(" java ", "roots"))
                ),
                Map.of("attachment", List.of(attachment)),
                new Session("bind")
        );

        var form = event.bind(CustomerForm.class);

        assertEquals("Ada Lovelace", form.name());
        assertEquals("ada@example.com", form.email());
        assertEquals(36, form.age());
        assertTrue(form.active());
        assertEquals(Tier.ENTERPRISE, form.tier());
        assertEquals(id, form.id());
        assertEquals(LocalDate.of(2026, 8, 16), form.joined());
        assertEquals(Optional.of(42), form.score());
        assertEquals(List.of("java", "roots"), form.tags());
        assertEquals("proof.txt", form.attachment().orElseThrow().filename());
        assertArrayEquals(new byte[]{1, 2, 3}, form.attachment().orElseThrow().content());
    }

    @Test
    void supportsEveryNumericAndTemporalConversionAndMissingCheckboxes() {
        var form = FormBinder.bind(Map.ofEntries(
                Map.entry("tiny", List.of("1")),
                Map.entry("small", List.of("2")),
                Map.entry("count", List.of("3")),
                Map.entry("large", List.of("4")),
                Map.entry("ratio", List.of("1.5")),
                Map.entry("precise", List.of("2.5")),
                Map.entry("integer", List.of("12345678901234567890")),
                Map.entry("decimal", List.of("123.45")),
                Map.entry("local", List.of("2026-08-16T12:30:00")),
                Map.entry("offset", List.of("2026-08-16T12:30:00-05:00")),
                Map.entry("instant", List.of("2026-08-16T17:30:00Z"))
        ), ConversionForm.class);

        assertEquals((byte) 1, form.tiny());
        assertEquals((short) 2, form.small());
        assertEquals(3, form.count());
        assertEquals(4L, form.large());
        assertEquals(1.5F, form.ratio());
        assertEquals(2.5D, form.precise());
        assertEquals(new BigInteger("12345678901234567890"), form.integer());
        assertEquals(new BigDecimal("123.45"), form.decimal());
        assertEquals(LocalDateTime.parse("2026-08-16T12:30:00"), form.local());
        assertEquals(OffsetDateTime.parse("2026-08-16T12:30:00-05:00"), form.offset());
        assertEquals(Instant.parse("2026-08-16T17:30:00Z"), form.instant());
        assertFalse(form.checked());
    }

    @Test
    void aggregatesConversionAndBuiltInConstraintErrorsWithMappedFieldNames() {
        var failure = assertThrows(ValidationException.class, () -> FormBinder.bind(Map.of(
                "company_name", List.of("   "),
                "email", List.of("not-an-email"),
                "age", List.of("151"),
                "active", List.of("sometimes"),
                "tier", List.of("unknown"),
                "joined", List.of("yesterday"),
                "tags", List.of("one", "two", "three", "four")
        ), CustomerForm.class));

        assertEquals("Check the highlighted customer fields.", failure.getMessage());
        assertEquals(List.of("Enter a customer name.", "Use 2 to 40 characters."),
                failure.fieldErrors().get("company_name"));
        assertEquals(List.of("Enter a business email."), failure.fieldErrors().get("email"));
        assertEquals(List.of("Age must be at most 120."), failure.fieldErrors().get("age"));
        assertEquals(List.of("Select a valid true or false value."), failure.fieldErrors().get("active"));
        assertEquals(List.of("Select a valid value."), failure.fieldErrors().get("tier"));
        assertEquals(List.of("Enter a valid date."), failure.fieldErrors().get("joined"));
        assertEquals(List.of("Select no more than 3 tags."), failure.fieldErrors().get("tags"));
    }

    @Test
    void reportsMissingRepeatedAndNonFiniteScalarValues() {
        var missing = assertThrows(ValidationException.class,
                () -> FormBinder.bind(Map.of(), RequiredNumbers.class));
        assertEquals(List.of("Enter a value."), missing.fieldErrors().get("number"));
        assertEquals(Optional.empty(), FormBinder.bind(Map.of(), OptionalForm.class).number());

        var repeated = assertThrows(ValidationException.class, () -> FormBinder.bind(
                Map.of("number", List.of("1", "2")), RequiredNumbers.class));
        assertEquals(List.of("Submit one value for number."), repeated.fieldErrors().get("number"));
        var repeatedOptional = assertThrows(ValidationException.class, () -> FormBinder.bind(
                Map.of("number", List.of("", "2")), OptionalForm.class));
        assertEquals(List.of("Submit one value for number."), repeatedOptional.fieldErrors().get("number"));

        for (var invalid : List.of("NaN", "Infinity", "not-a-number")) {
            var decimal = assertThrows(ValidationException.class,
                    () -> FormBinder.bind(Map.of("number", List.of(invalid)), FloatingForm.class));
            assertEquals(List.of("Enter a valid number."), decimal.fieldErrors().get("number"));
        }
    }

    @Test
    void bindsRequiredAndRepeatedUploads() {
        var first = new UploadedFile("one.txt", "text/plain", new byte[]{1});
        var second = new UploadedFile("two.txt", "text/plain", new byte[]{2});

        var form = FormBinder.bind(Map.of(), Map.of(
                "primary", List.of(first),
                "evidence", List.of(first, second)
        ), UploadForm.class);

        assertEquals("one.txt", form.primary().filename());
        assertEquals(List.of("one.txt", "two.txt"), form.evidence().stream().map(UploadedFile::filename).toList());

        var missing = assertThrows(ValidationException.class,
                () -> FormBinder.bind(Map.of(), Map.of(), UploadForm.class));
        assertEquals(List.of("Select a file."), missing.fieldErrors().get("primary"));

        var repeated = assertThrows(ValidationException.class, () -> FormBinder.bind(
                Map.of(), Map.of("primary", List.of(first, second)), UploadForm.class));
        assertEquals(List.of("Submit one value for primary."), repeated.fieldErrors().get("primary"));
    }

    @Test
    void supportsApplicationDefinedConstraintAnnotations() {
        var valid = FormBinder.bind(Map.of("code", List.of("ROOTS-42")), CustomForm.class);
        assertEquals("ROOTS-42", valid.code());

        var failure = assertThrows(ValidationException.class,
                () -> FormBinder.bind(Map.of("code", List.of("OTHER")), CustomForm.class));
        assertEquals(List.of("Use a ROOTS- code."), failure.fieldErrors().get("code"));
    }

    @Test
    void acceptsExplicitBooleanTokensAndEnforcesMinimumValues() {
        for (var token : List.of("true", "on", "1", "yes", "TRUE")) {
            assertTrue(FormBinder.bind(Map.of("value", List.of(token)), BooleanForm.class).value());
        }
        for (var token : List.of("false", "off", "0", "no", "FALSE")) {
            assertFalse(FormBinder.bind(Map.of("value", List.of(token)), BooleanForm.class).value());
        }

        var failure = assertThrows(ValidationException.class,
                () -> FormBinder.bind(Map.of("value", List.of("17")), MinimumForm.class));
        assertEquals(List.of("Must be 18 or older."), failure.fieldErrors().get("value"));
    }

    @Test
    void rejectsInvalidSchemasAndBrokenCustomValidators() {
        assertThrows(IllegalArgumentException.class, () -> FormBinder.bind(Map.of(), String.class));
        assertThrows(IllegalArgumentException.class, () -> FormBinder.bind(Map.of(), DuplicateFields.class));
        assertThrows(IllegalArgumentException.class, () -> FormBinder.bind(Map.of(), UnsupportedForm.class));
        assertThrows(IllegalArgumentException.class, () -> FormBinder.bind(
                Map.of("value", List.of("text")), WrongConstraintType.class));
        assertThrows(IllegalArgumentException.class, () -> FormBinder.bind(
                Map.of("value", List.of("text")), InvalidSize.class));
        assertThrows(IllegalArgumentException.class, () -> FormBinder.bind(
                Map.of("value", List.of("text")), NullValidatorResult.class));
        assertThrows(IllegalArgumentException.class, () -> FormBinder.bind(
                Map.of("value", List.of("text")), BlankValidatorMessage.class));
        assertThrows(IllegalArgumentException.class, () -> FormBinder.bind(
                Map.of("value", List.of("text")), BrokenValidatorConstructor.class));
        assertThrows(IllegalArgumentException.class, () -> FormBinder.bind(
                Map.of("value", List.of("1")), WrongNotBlankType.class));
        assertThrows(IllegalArgumentException.class, () -> FormBinder.bind(
                Map.of("value", List.of("1")), WrongEmailType.class));
        assertThrows(IllegalArgumentException.class, () -> FormBinder.bind(
                Map.of("value", List.of("1")), WrongSizeType.class));
        assertThrows(IllegalArgumentException.class, () -> FormBinder.bind(Map.of(), InvalidSummary.class));
    }

    @Test
    void preservesRecordConstructorFailuresAndValidatesInputMaps() {
        assertThrows(IllegalStateException.class,
                () -> FormBinder.bind(Map.of("value", List.of("bad")), CheckedConstructorForm.class));
        assertThrows(IllegalArgumentException.class,
                () -> FormBinder.bind(Map.of("value", List.of("bad")), RuntimeConstructorForm.class));
        assertThrows(NullPointerException.class, () -> FormBinder.bind(null, CustomerForm.class));
        assertThrows(NullPointerException.class, () -> FormBinder.bind(Map.of(), null));
    }

    private enum Tier { GROWTH, ENTERPRISE }

    @FormModel(message = "Check the highlighted customer fields.")
    private record CustomerForm(
            @FormField(value = "company_name", trim = true)
            @NotBlank(message = "Enter a customer name.")
            @Size(min = 2, max = 40, message = "Use {min} to {max} characters.") String name,
            @Email(message = "Enter a business email.") String email,
            @Min(value = 18, message = "Age must be at least {value}.")
            @Max(value = 120, message = "Age must be at most {value}.") int age,
            boolean active,
            Tier tier,
            UUID id,
            LocalDate joined,
            Optional<Integer> score,
            @FormField(trim = true)
            @Size(max = 3, message = "Select no more than {max} tags.") List<String> tags,
            Optional<UploadedFile> attachment
    ) {
    }

    private record ConversionForm(
            byte tiny,
            short small,
            int count,
            long large,
            float ratio,
            double precise,
            BigInteger integer,
            BigDecimal decimal,
            LocalDateTime local,
            OffsetDateTime offset,
            Instant instant,
            boolean checked
    ) {
    }

    private record RequiredNumbers(int number) {
    }

    private record FloatingForm(double number) {
    }

    private record BooleanForm(boolean value) {
    }

    private record MinimumForm(@Min(value = 18, message = "Must be {value} or older.") int value) {
    }

    private record OptionalForm(Optional<Integer> number) {
    }

    private record UploadForm(UploadedFile primary, List<UploadedFile> evidence) {
    }

    @Target(ElementType.RECORD_COMPONENT)
    @Retention(RetentionPolicy.RUNTIME)
    @FormConstraint(validatedBy = StartsWithValidator.class)
    private @interface StartsWith {
        String value();

        String message();
    }

    public static final class StartsWithValidator implements ConstraintValidator<StartsWith> {
        public StartsWithValidator() {
        }

        @Override
        public Optional<String> validate(Object value, StartsWith constraint) {
            return value instanceof String text && text.startsWith(constraint.value())
                    ? Optional.empty()
                    : Optional.of(constraint.message());
        }
    }

    private record CustomForm(@StartsWith(value = "ROOTS-", message = "Use a ROOTS- code.") String code) {
    }

    private record DuplicateFields(
            @FormField("same") String one,
            @FormField("same") String two
    ) {
    }

    private record UnsupportedForm(Map<String, String> value) {
    }

    private record WrongConstraintType(@Min(1) String value) {
    }

    private record InvalidSize(@Size(min = 3, max = 2) String value) {
    }

    private record WrongNotBlankType(@NotBlank int value) {
    }

    private record WrongEmailType(@Email int value) {
    }

    private record WrongSizeType(@Size int value) {
    }

    @FormModel(message = " ")
    private record InvalidSummary(String value) {
    }

    @Target(ElementType.RECORD_COMPONENT)
    @Retention(RetentionPolicy.RUNTIME)
    @FormConstraint(validatedBy = NullResultValidator.class)
    private @interface NullResult {
    }

    public static final class NullResultValidator implements ConstraintValidator<NullResult> {
        public NullResultValidator() {
        }

        @Override
        public Optional<String> validate(Object value, NullResult constraint) {
            return null;
        }
    }

    private record NullValidatorResult(@NullResult String value) {
    }

    @Target(ElementType.RECORD_COMPONENT)
    @Retention(RetentionPolicy.RUNTIME)
    @FormConstraint(validatedBy = BlankResultValidator.class)
    private @interface BlankResult {
    }

    public static final class BlankResultValidator implements ConstraintValidator<BlankResult> {
        public BlankResultValidator() {
        }

        @Override
        public Optional<String> validate(Object value, BlankResult constraint) {
            return Optional.of(" ");
        }
    }

    private record BlankValidatorMessage(@BlankResult String value) {
    }

    @Target(ElementType.RECORD_COMPONENT)
    @Retention(RetentionPolicy.RUNTIME)
    @FormConstraint(validatedBy = ThrowingValidator.class)
    private @interface BrokenValidator {
    }

    public static final class ThrowingValidator implements ConstraintValidator<BrokenValidator> {
        public ThrowingValidator() {
            throw new IllegalStateException("expected validator construction failure");
        }

        @Override
        public Optional<String> validate(Object value, BrokenValidator constraint) {
            return Optional.empty();
        }
    }

    private record BrokenValidatorConstructor(@BrokenValidator String value) {
    }

    private record CheckedConstructorForm(String value) {
        private CheckedConstructorForm {
            sneakyThrow(new Exception("expected checked failure"));
        }
    }

    private record RuntimeConstructorForm(String value) {
        private RuntimeConstructorForm {
            throw new IllegalArgumentException("expected runtime failure");
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable failure) throws E {
        throw (E) failure;
    }
}
