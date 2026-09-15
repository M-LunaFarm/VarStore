package kr.lunaf.varstore.api;

import java.nio.charset.StandardCharsets;

/** Canonical address validation; input is never silently lowercased or trimmed. */
public final class Names {
    private Names() {}
    public static String identifier(String value, String field) { return match(value, "[a-z0-9._-]+", 64, field); }
    public static String key(String value) { return match(value, "[a-z0-9._/-]+", 128, "key"); }
    public static String ownerType(String value) { return match(value, "[A-Z][A-Z0-9_]*", 32, "owner type"); }
    public static String ownerId(String value) { return match(value, "[a-zA-Z0-9._-]+", 128, "owner ID"); }
    private static String match(String value, String regex, int max, String field) {
        if (value == null || value.length() > max || !value.matches(regex))
            throw new VarStoreException(ErrorCode.INVALID_ARGUMENT, "Invalid " + field);
        return value;
    }
    public static int utf8Bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
}
