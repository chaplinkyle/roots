package com.chaplin.roots.jdbc;

import com.chaplin.roots.Response;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;

/** Bounded, versioned scalar encoding; never Java object serialization. */
record JdbcReceiptResponse(String headers, String body) {
    static JdbcReceiptResponse encode(Response response, int maximum) throws IOException {
        validate(response, maximum);
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeInt(1);
            output.writeInt(response.headers().size());
            for (var header : response.headers().entrySet()) {
                output.writeUTF(header.getKey());
                output.writeInt(header.getValue().size());
                for (var value : header.getValue()) output.writeUTF(value);
            }
        }
        var headers = Base64.getEncoder().encodeToString(bytes.toByteArray());
        if (headers.length() > 32768) throw new IllegalArgumentException("Encoded receipt headers exceed schema limit");
        return new JdbcReceiptResponse(headers, Base64.getEncoder().encodeToString(response.body()));
    }

    private static void validate(Response response, int maximum) {
        if (response.status() < 200 || response.streaming() || response.contentLength().orElseThrow() > maximum) {
            throw new IllegalArgumentException("Receipt requires a final buffered response within its byte limit");
        }
        if (response.headers().size() > 64) throw new IllegalArgumentException("Too many receipt headers");
        int values = 0;
        int characters = 0;
        for (var header : response.headers().entrySet()) {
            if (header.getKey().equalsIgnoreCase("Set-Cookie")) {
                throw new IllegalArgumentException("Receipt responses must not replay cookies");
            }
            values += header.getValue().size();
            characters += header.getKey().length();
            for (var value : header.getValue()) characters += value.length();
            if (values > 128 || characters > 16384) throw new IllegalArgumentException("Receipt headers exceed limits");
        }
    }

    static Response decode(int status, String headers, String body, int maximum) throws IOException {
        if (headers == null || body == null || headers.length() > 32768
                || body.length() > ((maximum + 2) / 3) * 4) {
            throw new IOException("Stored receipt exceeds configured bounds or is incomplete");
        }
        try {
            var decodedBody = Base64.getDecoder().decode(body);
            var decodedHeaders = Base64.getDecoder().decode(headers);
            var values = new LinkedHashMap<String, List<String>>();
            try (var input = new DataInputStream(new ByteArrayInputStream(decodedHeaders))) {
                if (input.readInt() != 1) throw new IOException("Unsupported receipt encoding version");
                int count = input.readInt();
                if (count < 0 || count > 64) throw new IOException("Invalid receipt header count");
                int total = 0;
                for (int index = 0; index < count; index++) {
                    var name = input.readUTF();
                    int size = input.readInt();
                    total += size;
                    if (size < 0 || size > 128 || total > 128) throw new IOException("Invalid receipt value count");
                    var entries = new ArrayList<String>();
                    for (int value = 0; value < size; value++) entries.add(input.readUTF());
                    if (values.put(name, entries) != null) throw new IOException("Duplicate receipt header");
                }
                if (input.read() != -1) throw new IOException("Trailing receipt header bytes");
            }
            var response = new Response(status, values, decodedBody);
            validate(response, maximum);
            return response;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid stored receipt representation", invalid);
        }
    }
}
