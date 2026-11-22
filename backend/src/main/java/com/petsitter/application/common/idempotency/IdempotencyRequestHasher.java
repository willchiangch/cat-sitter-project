package com.petsitter.application.common.idempotency;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * request hash（SD-GLOBAL-SPEC §2.4）：SHA-256（路由樣板 + 已解析路徑變數 + 正規化 JSON；multipart = 各欄位值依名稱排序 +
 * 檔案 SHA-256）。路徑變數必須入 hash，否則同一把 key 用在兩張訂單會互相回放（SD-ORDER §3.6.2）。
 * <p>
 * JSON 正規化：解析成樹 → 物件 key 依 Unicode 碼位排序 → 數字以十進位字串表示（整數不帶小數點；{@code 1} 與 {@code 1.0}
 * 不同）→ 字串保持原樣（不 trim）→ 緊湊輸出；空 body 視為 {@code {}}。
 */
final class IdempotencyRequestHasher {

    /** 只用來解析樹：數字保留原始精度與小數位（BigDecimal / BigInteger）。 */
    private static final JsonMapper TREE_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .build();

    private IdempotencyRequestHasher() {
    }

    /** JSON body 的 hash；body 無法解析時以原始位元組計（之後由 message converter 回 400）。 */
    static String hashJson(String routeTemplate, Map<String, String> pathVariables, byte[] body) {
        String canonicalBody;
        try {
            canonicalBody = canonicalJson(body);
        } catch (JacksonException e) {
            canonicalBody = "raw:" + sha256Hex(body);
        }
        return sha256Hex(prefix(routeTemplate, pathVariables) + canonicalBody);
    }

    /**
     * multipart 的 hash。
     *
     * @param fields 欄位名 → 值（依名稱排序；同名多值保留原順序）
     * @param files  檔案欄位名 → 各檔 SHA-256 hex
     */
    static String hashMultipart(String routeTemplate, Map<String, String> pathVariables,
                                Map<String, List<String>> fields, Map<String, List<String>> files) {
        StringBuilder sb = new StringBuilder("multipart:");
        sb.append("fields=");
        appendSortedMultiMap(sb, fields);
        sb.append(";files=");
        appendSortedMultiMap(sb, files);
        return sha256Hex(prefix(routeTemplate, pathVariables) + sb);
    }

    private static String prefix(String routeTemplate, Map<String, String> pathVariables) {
        StringBuilder sb = new StringBuilder();
        writeString(sb, routeTemplate);
        sb.append('\n');
        appendSortedMap(sb, pathVariables == null ? Map.of() : pathVariables);
        sb.append('\n');
        return sb.toString();
    }

    static String canonicalJson(byte[] body) {
        if (body == null || isAllWhitespace(body)) {
            return "{}";
        }
        JsonNode node = TREE_MAPPER.readTree(body);
        if (node == null || node.isMissingNode()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder();
        write(sb, node);
        return sb.toString();
    }

    private static boolean isAllWhitespace(byte[] body) {
        for (byte b : body) {
            if (b != ' ' && b != '\t' && b != '\n' && b != '\r') {
                return false;
            }
        }
        return true;
    }

    private static void write(StringBuilder sb, JsonNode node) {
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>(IdempotencyRequestHasher::compareCodePoints);
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                sorted.put(e.getKey(), e.getValue());
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> e : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (node.isArray()) {
            sb.append('[');
            boolean first = true;
            for (JsonNode child : node.values()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, child);
            }
            sb.append(']');
        } else if (node.isString()) {
            writeString(sb, node.stringValue());
        } else if (node.isIntegralNumber()) {
            sb.append(node.bigIntegerValue().toString());
        } else if (node.isNumber()) {
            sb.append(node.decimalValue().toPlainString());
        } else if (node.isBoolean()) {
            sb.append(node.booleanValue());
        } else if (node.isNull()) {
            sb.append("null");
        } else {
            writeString(sb, node.asString());
        }
    }

    /** 依 Unicode 碼位比較（{@link String#compareTo} 比的是 UTF-16 code unit，補充平面字元順序會不同）。 */
    static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static void appendSortedMap(StringBuilder sb, Map<String, String> map) {
        TreeMap<String, String> sorted = new TreeMap<>(IdempotencyRequestHasher::compareCodePoints);
        sorted.putAll(map);
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, e.getKey());
            sb.append(':');
            writeString(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void appendSortedMultiMap(StringBuilder sb, Map<String, List<String>> map) {
        TreeMap<String, List<String>> sorted = new TreeMap<>(IdempotencyRequestHasher::compareCodePoints);
        sorted.putAll(map);
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, List<String>> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, e.getKey());
            sb.append(":[");
            List<String> values = e.getValue() == null ? List.of() : new ArrayList<>(e.getValue());
            for (int k = 0; k < values.size(); k++) {
                if (k > 0) {
                    sb.append(',');
                }
                writeString(sb, values.get(k));
            }
            sb.append(']');
        }
        sb.append('}');
    }

    /** JSON 字串字面值（RFC 8259 最小跳脫）。 */
    private static void writeString(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256Hex(String data) {
        return sha256Hex(data.getBytes(StandardCharsets.UTF_8));
    }
}
