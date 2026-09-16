package kr.lunaf.varstore.codec;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small strict parser for the deliberately bounded JSON data model. */
final class BoundedJson {
    static final int MAX_BYTES = 16_384, MAX_DEPTH = 32, MAX_NODES = 4096;
    private BoundedJson() {}
    static String write(JsonValue value) {
        Writer writer = new Writer(); writer.value(value, 0);
        String encoded = writer.out.toString();
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw tooLarge();
        return encoded;
    }
    static JsonValue parse(String input) {
        Objects.requireNonNull(input);
        if (input.length() > MAX_BYTES || input.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw tooLarge();
        Parser parser = new Parser(input); JsonValue value = parser.value(0); parser.space();
        if (parser.pos != input.length()) throw invalid();
        return value;
    }
    private static CodecException invalid() { return new CodecException(CodecError.INVALID_ENVELOPE, "Invalid bounded JSON envelope"); }
    private static CodecException tooLarge() { return new CodecException(CodecError.VALUE_TOO_LARGE, "Envelope exceeds JSON byte, depth or node limit"); }
    private static void unicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) { if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid(); }
            else if (Character.isLowSurrogate(c)) throw invalid();
        }
    }
    private static final class Writer {
        final StringBuilder out = new StringBuilder(); int nodes;
        void add(String text) { if (out.length() + text.length() > MAX_BYTES) throw tooLarge(); out.append(text); }
        void value(JsonValue value, int depth) {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES) throw tooLarge();
            switch (value) {
                case JsonValue.ObjectValue object -> {
                    add("{"); boolean first = true;
                    for (var entry : object.fields().entrySet()) {
                        if (!first) add(","); first = false; string(entry.getKey()); add(":"); value(entry.getValue(), depth + 1);
                    }
                    add("}");
                }
                case JsonValue.ArrayValue array -> {
                    add("["); boolean first = true;
                    for (JsonValue item : array.values()) { if (!first) add(","); first = false; value(item, depth + 1); }
                    add("]");
                }
                case JsonValue.StringValue string -> string(string.value());
                case JsonValue.NumberValue number -> add(number.value().toString());
                case JsonValue.BooleanValue bool -> add(Boolean.toString(bool.value()));
                case JsonValue.NullValue ignored -> add("null");
            }
        }
        void string(String text) {
            if (text.length() > MAX_BYTES) throw tooLarge(); unicode(text); add("\"");
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '"' || c == '\\') add("\\" + c);
                else if (c < 32) add(String.format(Locale.ROOT, "\\u%04x", (int) c));
                else add(String.valueOf(c));
            }
            add("\"");
        }
    }
    private static final class Parser {
        final String input; int pos, nodes;
        Parser(String input) { this.input = input; }
        void space() { while (pos < input.length() && " \n\r\t".indexOf(input.charAt(pos)) >= 0) pos++; }
        boolean take(char c) { if (pos < input.length() && input.charAt(pos) == c) { pos++; return true; } return false; }
        void need(char c) { if (!take(c)) throw invalid(); }
        JsonValue value(int depth) {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES) throw tooLarge();
            space(); if (pos == input.length()) throw invalid();
            char c = input.charAt(pos);
            if (c == '"') return JsonValue.string(string());
            if (take('{')) {
                LinkedHashMap<String, JsonValue> fields = new LinkedHashMap<>(); space();
                if (take('}')) return JsonValue.object(fields);
                do {
                    space(); String key = string(); space(); need(':');
                    if (fields.containsKey(key)) throw invalid(); fields.put(key, value(depth + 1)); space();
                    if (take('}')) return JsonValue.object(fields);
                    need(',');
                } while (true);
            }
            if (take('[')) {
                List<JsonValue> values = new ArrayList<>(); space();
                if (take(']')) return JsonValue.array(values);
                do { values.add(value(depth + 1)); space(); if (take(']')) return JsonValue.array(values); need(','); } while (true);
            }
            for (String literal : List.of("true", "false", "null")) {
                if (input.startsWith(literal, pos)) {
                    pos += literal.length();
                    return literal.equals("null") ? JsonValue.nil() : JsonValue.bool(literal.equals("true"));
                }
            }
            int begin = pos; take('-');
            if (take('0')) { /* Leading zero is checked by the enclosing separator. */ }
            else { if (pos == input.length() || input.charAt(pos) < '1' || input.charAt(pos) > '9') throw invalid(); digits(); }
            if (take('.')) { int before = pos; digits(); if (pos == before) throw invalid(); }
            if (take('e') || take('E')) { if (!take('+')) take('-'); int before = pos; digits(); if (pos == before) throw invalid(); }
            try { return JsonValue.number(new BigDecimal(input.substring(begin, pos))); }
            catch (IllegalArgumentException error) { throw invalid(); }
        }
        void digits() { while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') pos++; }
        String string() {
            need('"'); StringBuilder result = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos++);
                if (c == '"') { String text = result.toString(); unicode(text); return text; }
                if (c < 32) throw invalid();
                if (c != '\\') result.append(c);
                else {
                    if (pos == input.length()) throw invalid(); char escaped = input.charAt(pos++);
                    switch (escaped) {
                        case '"', '\\', '/' -> result.append(escaped);
                        case 'b' -> result.append('\b'); case 'f' -> result.append('\f');
                        case 'n' -> result.append('\n'); case 'r' -> result.append('\r'); case 't' -> result.append('\t');
                        case 'u' -> {
                            if (pos + 4 > input.length()) throw invalid(); int code = 0;
                            for (int i = 0; i < 4; i++) {
                                char hex = input.charAt(pos++); int digit = hex >= '0' && hex <= '9' ? hex - '0' : hex >= 'a' && hex <= 'f' ? hex - 'a' + 10 : hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1;
                                if (digit < 0) throw invalid(); code = code * 16 + digit;
                            }
                            result.append((char) code);
                        }
                        default -> throw invalid();
                    }
                }
            }
            throw invalid();
        }
    }
}
