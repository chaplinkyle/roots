package com.chaplin.roots.jdbc;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

enum StandardJdbcSessionValueCodec implements JdbcSessionValueCodec {
    INSTANCE;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    @Override
    public String encode(Object value) {
        Objects.requireNonNull(value, "value");
        return switch (value) {
            case String text -> "str:" + encoded(text.getBytes(StandardCharsets.UTF_8));
            case Boolean flag -> "bool:" + (flag ? "1" : "0");
            case Byte number -> "byte:" + number;
            case Short number -> "short:" + number;
            case Integer number -> "int:" + number;
            case Long number -> "long:" + number;
            case Float number -> "float:" + number;
            case Double number -> "double:" + number;
            case BigInteger number -> "bigint:" + number;
            case BigDecimal number -> "decimal:" + number.toPlainString();
            case Character character -> "char:" + (int) character;
            case UUID uuid -> "uuid:" + uuid;
            case Instant instant -> "instant:" + instant;
            case byte[] bytes -> "bytes:" + encoded(bytes);
            default -> throw new IllegalArgumentException(
                    "Standard JDBC session codec does not support " + value.getClass().getName());
        };
    }

    @Override
    public Object decode(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        var separator = encoded.indexOf(':');
        if (separator < 1) {
            throw new IllegalArgumentException("Malformed JDBC session value");
        }
        var tag = encoded.substring(0, separator);
        var value = encoded.substring(separator + 1);
        try {
            return switch (tag) {
                case "str" -> new String(DECODER.decode(value), StandardCharsets.UTF_8);
                case "bool" -> switch (value) {
                    case "1" -> true;
                    case "0" -> false;
                    default -> throw new IllegalArgumentException("Malformed boolean JDBC session value");
                };
                case "byte" -> Byte.valueOf(value);
                case "short" -> Short.valueOf(value);
                case "int" -> Integer.valueOf(value);
                case "long" -> Long.valueOf(value);
                case "float" -> Float.valueOf(value);
                case "double" -> Double.valueOf(value);
                case "bigint" -> new BigInteger(value);
                case "decimal" -> new BigDecimal(value);
                case "char" -> character(value);
                case "uuid" -> UUID.fromString(value);
                case "instant" -> Instant.parse(value);
                case "bytes" -> DECODER.decode(value);
                default -> throw new IllegalArgumentException("Unknown JDBC session value tag: " + tag);
            };
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("Malformed")) {
                throw exception;
            }
            throw new IllegalArgumentException("Malformed JDBC session value for tag " + tag, exception);
        }
    }

    private static Character character(String value) {
        var codePoint = Integer.parseInt(value);
        if (codePoint < Character.MIN_VALUE || codePoint > Character.MAX_VALUE) {
            throw new IllegalArgumentException("Malformed character JDBC session value");
        }
        return (char) codePoint;
    }

    private static String encoded(byte[] value) {
        return ENCODER.encodeToString(value);
    }
}
