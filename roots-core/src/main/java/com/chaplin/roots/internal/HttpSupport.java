package com.chaplin.roots.internal;

import com.chaplin.roots.Request;
import com.chaplin.roots.Response;
import com.chaplin.roots.Session;
import com.chaplin.roots.UploadedFile;
import com.chaplin.roots.BinaryContent;
import com.chaplin.roots.RootsConfig;
import com.chaplin.roots.RootsCache;
import com.chaplin.roots.RequestBodyPolicy;

import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class HttpSupport {
    private static final int MAX_MULTIPART_PARTS = 256;
    private static final int MAX_PART_HEADER_BYTES = 16_384;
    private static final int MAX_PART_HEADERS = 32;

    private HttpSupport() {
    }

    static Request request(TransportExchange exchange, Map<String, String> parameters, Session session) throws IOException {
        return request(exchange, parameters, session, RootsConfig.DEFAULT_MAX_REQUEST_BYTES, RootsCache.disabled());
    }

    static Request request(
            TransportExchange exchange,
            Map<String, String> parameters,
            Session session,
            int maxRequestBytes
    ) throws IOException {
        return request(exchange, parameters, session, maxRequestBytes, RootsCache.disabled());
    }

    static Request request(
            TransportExchange exchange,
            Map<String, String> parameters,
            Session session,
            int maxRequestBytes,
            RootsCache cache
    ) throws IOException {
        return request(exchange, parameters, session, maxRequestBytes, cache, RequestBodyPolicy.defaults());
    }

    static Request request(
            TransportExchange exchange,
            Map<String, String> parameters,
            Session session,
            int maxRequestBytes,
            RootsCache cache,
            RequestBodyPolicy requestBodyPolicy
    ) throws IOException {
        var body = readBody(exchange, maxRequestBytes, requestBodyPolicy);
        try {
            var contentType = exchange.requestHeader("Content-Type");
            Map<String, List<String>> form = Map.of();
            Map<String, List<UploadedFile>> files = Map.of();
            var baseContentType = contentType == null ? "" : contentType.split(";", 2)[0].trim();
            if (baseContentType.equalsIgnoreCase("application/x-www-form-urlencoded")) {
                form = parameters(new String(body.readAllBytes(), StandardCharsets.UTF_8));
            } else if (baseContentType.equalsIgnoreCase("multipart/form-data")) {
                var multipart = multipart(contentType, body, requestBodyPolicy.maxMultipartTextFieldBytes());
                form = multipart.values();
                files = multipart.files();
            }
            return new Request(
                    exchange.method(),
                    exchange.uri().getPath(),
                    exchange.uri().getPath(),
                    parameters,
                    parameters(exchange.uri().getRawQuery()),
                    exchange.requestHeaders(),
                    form,
                    files,
                    body,
                    session,
                    cache,
                    exchange.identity(),
                    exchange.traceContext(),
                    exchange.mountPath(),
                    exchange.connection()
            );
        } catch (Throwable failure) {
            try {
                body.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    static Map<String, List<String>> parameters(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return Map.of();
        }
        var result = new LinkedHashMap<String, List<String>>();
        for (var pair : encoded.split("&")) {
            var parts = pair.split("=", 2);
            var key = decode(parts[0]);
            var value = parts.length == 2 ? decode(parts[1]) : "";
            result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
        var immutable = new LinkedHashMap<String, List<String>>();
        result.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
        return Map.copyOf(immutable);
    }

    static Multipart multipart(String contentType, byte[] body) {
        var mediaType = headerValue(contentType, "Content-Type");
        if (!mediaType.value().equalsIgnoreCase("multipart/form-data")) {
            throw new BadRequestException("Expected multipart/form-data");
        }
        var boundary = mediaType.parameters().get("boundary");
        if (boundary == null
                || boundary.isEmpty()
                || boundary.length() > 70
                || !boundary.matches("[0-9A-Za-z'()+_,./:=?-]+")) {
            throw new BadRequestException("Invalid multipart boundary");
        }

        var delimiter = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
        var marker = concat("\r\n".getBytes(StandardCharsets.US_ASCII), delimiter);
        if (!startsWith(body, 0, delimiter)) {
            throw new BadRequestException("Malformed multipart body");
        }

        var values = new LinkedHashMap<String, List<String>>();
        var files = new LinkedHashMap<String, List<UploadedFile>>();
        var cursor = delimiter.length;
        var partCount = 0;
        if (finalBoundary(body, cursor)) {
            requireMultipartEnd(body, cursor + 2);
            return new Multipart(values, files);
        }
        cursor = requireCrlf(body, cursor);

        while (true) {
            if (++partCount > MAX_MULTIPART_PARTS) {
                throw new BadRequestException("Multipart request has too many parts");
            }
            var headersEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII), cursor);
            if (headersEnd < 0 || headersEnd - cursor > MAX_PART_HEADER_BYTES) {
                throw new BadRequestException("Malformed multipart part headers");
            }
            var headers = partHeaders(Arrays.copyOfRange(body, cursor, headersEnd));
            var contentStart = headersEnd + 4;
            var boundaryAt = nextBoundary(body, marker, contentStart);
            if (boundaryAt < 0) {
                throw new BadRequestException("Multipart closing boundary is missing");
            }
            var content = Arrays.copyOfRange(body, contentStart, boundaryAt);
            addPart(headers, content, values, files);

            cursor = boundaryAt + marker.length;
            if (finalBoundary(body, cursor)) {
                requireMultipartEnd(body, cursor + 2);
                return new Multipart(values, files);
            }
            cursor = requireCrlf(body, cursor);
        }
    }

    static Multipart multipart(
            String contentType,
            SpoolingBody body,
            int maxTextPartBytes
    ) throws IOException {
        var mediaType = headerValue(contentType, "Content-Type");
        if (!mediaType.value().equalsIgnoreCase("multipart/form-data")) {
            throw new BadRequestException("Expected multipart/form-data");
        }
        var boundary = mediaType.parameters().get("boundary");
        if (boundary == null
                || boundary.isEmpty()
                || boundary.length() > 70
                || !boundary.matches("[0-9A-Za-z'()+_,./:=?-]+")) {
            throw new BadRequestException("Invalid multipart boundary");
        }

        var delimiter = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
        var marker = concat("\r\n".getBytes(StandardCharsets.US_ASCII), delimiter);
        if (!startsWith(body, 0, delimiter)) {
            throw new BadRequestException("Malformed multipart body");
        }

        var values = new LinkedHashMap<String, List<String>>();
        var files = new LinkedHashMap<String, List<UploadedFile>>();
        long cursor = delimiter.length;
        var partCount = 0;
        if (finalBoundary(body, cursor)) {
            requireMultipartEnd(body, cursor + 2);
            return new Multipart(values, files);
        }
        cursor = requireCrlf(body, cursor);

        while (true) {
            if (++partCount > MAX_MULTIPART_PARTS) {
                throw new BadRequestException("Multipart request has too many parts");
            }
            var headersEnd = indexOf(
                    body,
                    "\r\n\r\n".getBytes(StandardCharsets.US_ASCII),
                    cursor,
                    MAX_PART_HEADER_BYTES + 4L
            );
            if (headersEnd < 0 || headersEnd - cursor > MAX_PART_HEADER_BYTES) {
                throw new BadRequestException("Malformed multipart part headers");
            }
            var headers = partHeaders(readRange(body, cursor, Math.toIntExact(headersEnd - cursor)));
            var contentStart = headersEnd + 4;
            var boundaryAt = nextBoundary(body, marker, contentStart);
            if (boundaryAt < 0) {
                throw new BadRequestException("Multipart closing boundary is missing");
            }
            addPart(headers, body, contentStart, boundaryAt - contentStart, maxTextPartBytes, values, files);

            cursor = boundaryAt + marker.length;
            if (finalBoundary(body, cursor)) {
                requireMultipartEnd(body, cursor + 2);
                return new Multipart(values, files);
            }
            cursor = requireCrlf(body, cursor);
        }
    }

    static void send(TransportExchange exchange, Response response, boolean head) throws IOException {
        response.headers().forEach(exchange::responseHeader);
        exchange.responseHeader("X-Content-Type-Options", "nosniff");
        var length = response.contentLength();
        if (head || suppressesBody(response.status())) {
            if (head && length.isPresent() && !hasHeader(response.headers(), "Content-Length")) {
                exchange.responseHeader("Content-Length", Long.toString(length.orElseThrow()));
            }
            exchange.sendResponseHeaders(response.status(), -1);
        } else if (length.isPresent() && length.orElseThrow() == 0) {
            if (!hasHeader(response.headers(), "Content-Length")) {
                exchange.responseHeader("Content-Length", "0");
            }
            exchange.sendResponseHeaders(response.status(), -1);
        } else {
            exchange.sendResponseHeaders(response.status(), length.orElse(0));
            try (var output = exchange.responseBody()) {
                response.transferTo(output);
            }
        }
        exchange.close();
    }

    private static boolean hasHeader(Map<String, List<String>> headers, String name) {
        return headers.keySet().stream().anyMatch(existing -> existing.equalsIgnoreCase(name));
    }

    private static boolean suppressesBody(int status) {
        return status < 200 || status == 204 || status == 205 || status == 304;
    }

    static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        var result = new StringBuilder(value.length() + 16).append('"');
        for (var character : value.toCharArray()) {
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                case '<' -> result.append("\\u003c");
                case '>' -> result.append("\\u003e");
                case '&' -> result.append("\\u0026");
                default -> {
                    if (character < 0x20) {
                        result.append("\\u%04x".formatted((int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.append('"').toString();
    }

    private static SpoolingBody readBody(
            TransportExchange exchange,
            int maxRequestBytes,
            RequestBodyPolicy requestBodyPolicy
    ) throws IOException {
        var declaredLength = exchange.requestHeader("Content-Length");
        if (declaredLength != null) {
            final long parsedLength;
            try {
                parsedLength = Long.parseLong(declaredLength);
            } catch (NumberFormatException exception) {
                throw new BadRequestException("Invalid Content-Length header", exception);
            }
            if (parsedLength < 0) {
                throw new BadRequestException("Invalid Content-Length header");
            }
            if (parsedLength > maxRequestBytes) {
                throw new RequestTooLargeException();
            }
        }
        return SpoolingBody.read(
                exchange.requestBody(),
                maxRequestBytes,
                Math.min(maxRequestBytes, requestBodyPolicy.memoryThreshold()),
                requestBodyPolicy.temporaryDirectory()
        );
    }

    private static Map<String, String> partHeaders(byte[] encoded) {
        var result = new LinkedHashMap<String, String>();
        if (encoded.length == 0) {
            throw new BadRequestException("Multipart part headers are missing");
        }
        var lines = new String(encoded, StandardCharsets.ISO_8859_1).split("\\r\\n", -1);
        if (lines.length > MAX_PART_HEADERS) {
            throw new BadRequestException("Multipart part has too many headers");
        }
        for (var line : lines) {
            var separator = line.indexOf(':');
            if (separator < 1) {
                throw new BadRequestException("Malformed multipart part header");
            }
            var name = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            var value = line.substring(separator + 1).trim();
            if (!name.matches("[!#$%&'*+.^_`|~0-9a-z-]+")
                    || value.isEmpty()
                    || containsControl(value)) {
                throw new BadRequestException("Malformed multipart part header");
            }
            if (result.putIfAbsent(name, value) != null) {
                throw new BadRequestException("Duplicate multipart part header: " + name);
            }
        }
        return Map.copyOf(result);
    }

    private static void addPart(
            Map<String, String> headers,
            byte[] content,
            Map<String, List<String>> values,
            Map<String, List<UploadedFile>> files
    ) {
        var disposition = headers.get("content-disposition");
        if (disposition == null) {
            throw new BadRequestException("Multipart part is missing Content-Disposition");
        }
        var parsed = headerValue(disposition, "Content-Disposition");
        if (!parsed.value().equalsIgnoreCase("form-data")) {
            throw new BadRequestException("Multipart disposition must be form-data");
        }
        var name = parsed.parameters().containsKey("name*")
                ? extendedValue(parsed.parameters().get("name*"), "multipart field name")
                : utf8Header(parsed.parameters().get("name"));
        if (name == null || name.isBlank() || containsControl(name)) {
            throw new BadRequestException("Multipart field name is missing");
        }
        if (parsed.parameters().containsKey("filename") || parsed.parameters().containsKey("filename*")) {
            var filename = parsed.parameters().containsKey("filename*")
                    ? extendedValue(parsed.parameters().get("filename*"), "multipart filename")
                    : utf8Header(parsed.parameters().get("filename"));
            if (filename == null || containsControl(filename)) {
                throw new BadRequestException("Multipart filename is invalid");
            }
            var partContentType = headers.getOrDefault("content-type", "application/octet-stream");
            files.computeIfAbsent(name, ignored -> new ArrayList<>())
                    .add(new UploadedFile(filename, partContentType, content));
            return;
        }

        var charset = StandardCharsets.UTF_8;
        var partContentType = headers.get("content-type");
        if (partContentType != null) {
            var contentType = headerValue(partContentType, "part Content-Type");
            var charsetName = contentType.parameters().get("charset");
            if (charsetName != null) {
                try {
                    charset = Charset.forName(charsetName);
                } catch (IllegalArgumentException exception) {
                    throw new BadRequestException("Unsupported multipart field charset", exception);
                }
            }
        }
        values.computeIfAbsent(name, ignored -> new ArrayList<>()).add(new String(content, charset));
    }

    private static void addPart(
            Map<String, String> headers,
            SpoolingBody body,
            long contentOffset,
            long contentLength,
            int maxTextPartBytes,
            Map<String, List<String>> values,
            Map<String, List<UploadedFile>> files
    ) throws IOException {
        var disposition = headers.get("content-disposition");
        if (disposition == null) {
            throw new BadRequestException("Multipart part is missing Content-Disposition");
        }
        var parsed = headerValue(disposition, "Content-Disposition");
        if (!parsed.value().equalsIgnoreCase("form-data")) {
            throw new BadRequestException("Multipart disposition must be form-data");
        }
        var name = parsed.parameters().containsKey("name*")
                ? extendedValue(parsed.parameters().get("name*"), "multipart field name")
                : utf8Header(parsed.parameters().get("name"));
        if (name == null || name.isBlank() || containsControl(name)) {
            throw new BadRequestException("Multipart field name is missing");
        }
        if (parsed.parameters().containsKey("filename") || parsed.parameters().containsKey("filename*")) {
            var filename = parsed.parameters().containsKey("filename*")
                    ? extendedValue(parsed.parameters().get("filename*"), "multipart filename")
                    : utf8Header(parsed.parameters().get("filename"));
            if (filename == null || containsControl(filename)) {
                throw new BadRequestException("Multipart filename is invalid");
            }
            var partContentType = headers.getOrDefault("content-type", "application/octet-stream");
            files.computeIfAbsent(name, ignored -> new ArrayList<>())
                    .add(new UploadedFile(filename, partContentType, body.slice(contentOffset, contentLength)));
            return;
        }

        if (contentLength > maxTextPartBytes) {
            throw new BadRequestException("Multipart text field is too large");
        }
        var charset = StandardCharsets.UTF_8;
        var partContentType = headers.get("content-type");
        if (partContentType != null) {
            var parsedContentType = headerValue(partContentType, "part Content-Type");
            var charsetName = parsedContentType.parameters().get("charset");
            if (charsetName != null) {
                try {
                    charset = Charset.forName(charsetName);
                } catch (IllegalArgumentException exception) {
                    throw new BadRequestException("Unsupported multipart field charset", exception);
                }
            }
        }
        values.computeIfAbsent(name, ignored -> new ArrayList<>())
                .add(new String(readRange(body, contentOffset, Math.toIntExact(contentLength)), charset));
    }

    private static HeaderValue headerValue(String raw, String description) {
        if (raw == null) {
            throw new BadRequestException(description + " is missing");
        }
        var segments = splitHeader(raw, description);
        var value = segments.getFirst().trim();
        if (value.isEmpty()) {
            throw new BadRequestException("Malformed " + description);
        }
        var parameters = new LinkedHashMap<String, String>();
        for (var index = 1; index < segments.size(); index++) {
            var segment = segments.get(index).trim();
            var equals = segment.indexOf('=');
            if (equals < 1) {
                throw new BadRequestException("Malformed " + description + " parameter");
            }
            var name = segment.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            var parameterValue = unquote(segment.substring(equals + 1).trim(), description);
            if (!name.matches("[!#$%&'*+.^_`|~0-9a-z-]+")
                    || parameters.putIfAbsent(name, parameterValue) != null) {
                throw new BadRequestException("Malformed " + description + " parameter");
            }
        }
        return new HeaderValue(value, Map.copyOf(parameters));
    }

    private static List<String> splitHeader(String value, String description) {
        var result = new ArrayList<String>();
        var current = new StringBuilder();
        var quoted = false;
        var escaped = false;
        for (var character : value.toCharArray()) {
            if (escaped) {
                current.append(character);
                escaped = false;
            } else if (quoted && character == '\\') {
                current.append(character);
                escaped = true;
            } else if (character == '"') {
                quoted = !quoted;
                current.append(character);
            } else if (character == ';' && !quoted) {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        if (quoted || escaped) {
            throw new BadRequestException("Malformed quoted " + description);
        }
        result.add(current.toString());
        return result;
    }

    private static String unquote(String value, String description) {
        if (!value.startsWith("\"")) {
            if (value.indexOf('"') >= 0 || value.isEmpty()) {
                throw new BadRequestException("Malformed " + description + " parameter");
            }
            return value;
        }
        if (value.length() < 2 || !value.endsWith("\"")) {
            throw new BadRequestException("Malformed quoted " + description);
        }
        var result = new StringBuilder();
        var escaped = false;
        for (var index = 1; index < value.length() - 1; index++) {
            var character = value.charAt(index);
            if (escaped) {
                result.append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"' || character == '\r' || character == '\n') {
                throw new BadRequestException("Malformed quoted " + description);
            } else {
                result.append(character);
            }
        }
        if (escaped) {
            throw new BadRequestException("Malformed quoted " + description);
        }
        return result.toString();
    }

    private static int nextBoundary(byte[] body, byte[] marker, int start) {
        var cursor = start;
        while (true) {
            var candidate = indexOf(body, marker, cursor);
            if (candidate < 0) {
                return -1;
            }
            var after = candidate + marker.length;
            if (validFinalBoundary(body, after)
                    || startsWith(body, after, "\r\n".getBytes(StandardCharsets.US_ASCII))) {
                return candidate;
            }
            cursor = candidate + 1;
        }
    }

    private static boolean finalBoundary(byte[] body, int offset) {
        return startsWith(body, offset, "--".getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean validFinalBoundary(byte[] body, int offset) {
        if (!finalBoundary(body, offset)) {
            return false;
        }
        var end = offset + 2;
        return end == body.length
                || (end + 2 == body.length
                && startsWith(body, end, "\r\n".getBytes(StandardCharsets.US_ASCII)));
    }

    private static int requireCrlf(byte[] body, int offset) {
        if (!startsWith(body, offset, "\r\n".getBytes(StandardCharsets.US_ASCII))) {
            throw new BadRequestException("Malformed multipart boundary");
        }
        return offset + 2;
    }

    private static void requireMultipartEnd(byte[] body, int offset) {
        if (offset == body.length) {
            return;
        }
        if (offset + 2 == body.length && startsWith(body, offset, "\r\n".getBytes(StandardCharsets.US_ASCII))) {
            return;
        }
        throw new BadRequestException("Unexpected data after multipart closing boundary");
    }

    private static long nextBoundary(SpoolingBody body, byte[] marker, long start) throws IOException {
        var cursor = start;
        while (true) {
            var candidate = indexOf(body, marker, cursor);
            if (candidate < 0) {
                return -1;
            }
            var after = candidate + marker.length;
            if (validFinalBoundary(body, after)
                    || startsWith(body, after, "\r\n".getBytes(StandardCharsets.US_ASCII))) {
                return candidate;
            }
            cursor = candidate + 1;
        }
    }

    private static boolean finalBoundary(SpoolingBody body, long offset) throws IOException {
        return startsWith(body, offset, "--".getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean validFinalBoundary(SpoolingBody body, long offset) throws IOException {
        if (!finalBoundary(body, offset)) {
            return false;
        }
        var end = offset + 2;
        return end == body.size()
                || (end + 2 == body.size()
                && startsWith(body, end, "\r\n".getBytes(StandardCharsets.US_ASCII)));
    }

    private static long requireCrlf(SpoolingBody body, long offset) throws IOException {
        if (!startsWith(body, offset, "\r\n".getBytes(StandardCharsets.US_ASCII))) {
            throw new BadRequestException("Malformed multipart boundary");
        }
        return offset + 2;
    }

    private static void requireMultipartEnd(SpoolingBody body, long offset) throws IOException {
        if (offset == body.size()) {
            return;
        }
        if (offset + 2 == body.size()
                && startsWith(body, offset, "\r\n".getBytes(StandardCharsets.US_ASCII))) {
            return;
        }
        throw new BadRequestException("Unexpected data after multipart closing boundary");
    }

    private static String utf8Header(String value) {
        return value == null ? null : new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    private static String extendedValue(String value, String description) {
        var firstQuote = value.indexOf('\'');
        var secondQuote = firstQuote < 0 ? -1 : value.indexOf('\'', firstQuote + 1);
        if (firstQuote < 1 || secondQuote < 0) {
            throw new BadRequestException("Malformed RFC 5987 " + description);
        }
        final Charset charset;
        try {
            charset = Charset.forName(value.substring(0, firstQuote));
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Unsupported RFC 5987 " + description + " charset", exception);
        }
        var encoded = value.substring(secondQuote + 1);
        var bytes = new ByteArrayOutputStream(encoded.length());
        for (var index = 0; index < encoded.length();) {
            var character = encoded.charAt(index);
            if (character == '%') {
                if (index + 2 >= encoded.length()) {
                    throw new BadRequestException("Malformed RFC 5987 " + description);
                }
                var high = Character.digit(encoded.charAt(index + 1), 16);
                var low = Character.digit(encoded.charAt(index + 2), 16);
                if (high < 0 || low < 0) {
                    throw new BadRequestException("Malformed RFC 5987 " + description);
                }
                bytes.write((high << 4) | low);
                index += 3;
            } else {
                if (character > 0x7f) {
                    throw new BadRequestException("Malformed RFC 5987 " + description);
                }
                bytes.write(character);
                index++;
            }
        }
        return new String(bytes.toByteArray(), charset);
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(character -> character < 0x20 || character == 0x7f);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        var result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int start) {
        for (var index = Math.max(0, start); index <= haystack.length - needle.length; index++) {
            if (startsWith(haystack, index, needle)) {
                return index;
            }
        }
        return -1;
    }

    private static long indexOf(SpoolingBody body, byte[] needle, long start) throws IOException {
        return indexOf(body, needle, start, body.size() - Math.max(0, start));
    }

    private static long indexOf(
            SpoolingBody body,
            byte[] needle,
            long start,
            long maximumScanBytes
    ) throws IOException {
        if (needle.length == 0) {
            return Math.max(0, start);
        }
        var prefix = new int[needle.length];
        for (var index = 1; index < needle.length; index++) {
            var matched = prefix[index - 1];
            while (matched > 0 && needle[index] != needle[matched]) {
                matched = prefix[matched - 1];
            }
            if (needle[index] == needle[matched]) {
                matched++;
            }
            prefix[index] = matched;
        }
        var matched = 0;
        var position = Math.max(0, start);
        var scanLength = Math.min(body.size() - position, Math.max(0, maximumScanBytes));
        try (var input = new BufferedInputStream(body.slice(position, scanLength).openStream())) {
            int value;
            while ((value = input.read()) >= 0) {
                var current = (byte) value;
                while (matched > 0 && current != needle[matched]) {
                    matched = prefix[matched - 1];
                }
                if (current == needle[matched]) {
                    matched++;
                }
                if (matched == needle.length) {
                    return position - needle.length + 1;
                }
                position++;
            }
        }
        return -1;
    }

    private static boolean startsWith(byte[] value, int offset, byte[] prefix) {
        if (offset < 0 || offset + prefix.length > value.length) {
            return false;
        }
        for (var index = 0; index < prefix.length; index++) {
            if (value[offset + index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(SpoolingBody body, long offset, byte[] prefix) throws IOException {
        if (offset < 0 || offset > body.size() - prefix.length) {
            return false;
        }
        return Arrays.equals(readRange(body, offset, prefix.length), prefix);
    }

    private static byte[] readRange(SpoolingBody body, long offset, int length) throws IOException {
        try (var input = body.slice(offset, length).openStream()) {
            var result = input.readNBytes(length);
            if (result.length != length || input.read() >= 0) {
                throw new IOException("Request body ended before its declared size");
            }
            return result;
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Malformed URL-encoded data", exception);
        }
    }

    static final class RequestTooLargeException extends IOException {
    }

    static final class BadRequestException extends RuntimeException {
        BadRequestException(String message) {
            super(message);
        }

        BadRequestException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    record Multipart(Map<String, List<String>> values, Map<String, List<UploadedFile>> files) {
        Multipart {
            values = copyLists(values);
            files = copyLists(files);
        }

        private static <T> Map<String, List<T>> copyLists(Map<String, List<T>> source) {
            var copy = new LinkedHashMap<String, List<T>>();
            source.forEach((name, entries) -> copy.put(name, List.copyOf(entries)));
            return Map.copyOf(copy);
        }
    }

    private record HeaderValue(String value, Map<String, String> parameters) {
    }
}
