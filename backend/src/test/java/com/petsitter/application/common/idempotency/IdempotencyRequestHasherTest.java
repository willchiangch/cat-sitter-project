package com.petsitter.application.common.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SD-GLOBAL §2.4 request hash 與 JSON 正規化")
class IdempotencyRequestHasherTest {

    private static String canon(String json) {
        return IdempotencyRequestHasher.canonicalJson(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(String template, Map<String, String> vars, String json) {
        return IdempotencyRequestHasher.hashJson(template, vars, json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("物件 key 依碼位排序、緊湊輸出、巢狀與陣列保留順序")
    void sortsKeysCompact() {
        assertThat(canon("{ \"b\": 1, \"a\": { \"d\": [3, 1], \"c\": null } }"))
                .isEqualTo("{\"a\":{\"c\":null,\"d\":[3,1]},\"b\":1}");
    }

    @Test
    @DisplayName("數字：整數不帶小數點；1 與 1.0 不同；大數不失精度")
    void numbers() {
        assertThat(canon("{\"n\":1}")).isEqualTo("{\"n\":1}");
        assertThat(canon("{\"n\":1.0}")).isEqualTo("{\"n\":1.0}");
        assertThat(canon("{\"n\":12345678901234567890123}")).isEqualTo("{\"n\":12345678901234567890123}");
        assertThat(canon("{\"n\":0.1}")).isEqualTo("{\"n\":0.1}");
        assertThat(hash("/t", Map.of(), "{\"n\":1}")).isNotEqualTo(hash("/t", Map.of(), "{\"n\":1.0}"));
    }

    @Test
    @DisplayName("字串保持原樣（不 trim）、跳脫一致")
    void stringsUntouched() {
        assertThat(canon("{\"s\":\" a \\u3000\"}")).isEqualTo("{\"s\":\" a \u3000\"}");
        assertThat(hash("/t", Map.of(), "{\"s\":\"a\"}")).isNotEqualTo(hash("/t", Map.of(), "{\"s\":\" a\"}"));
        assertThat(canon("{\"s\":\"q\\\"\\n\"}")).isEqualTo("{\"s\":\"q\\\"\\n\"}");
    }

    @Test
    @DisplayName("空 body 視為 {}")
    void emptyBody() {
        assertThat(canon("")).isEqualTo("{}");
        assertThat(canon("  \n")).isEqualTo("{}");
        assertThat(hash("/t", Map.of(), "")).isEqualTo(hash("/t", Map.of(), "{}"));
    }

    @Test
    @DisplayName("key 順序、空白不同 → 同 hash；路徑變數 / 路由樣板不同 → 不同 hash")
    void hashInputs() {
        String a = hash("/api/orders/{orderId}/quote", Map.of("orderId", "1"), "{\"a\":1,\"b\":2}");
        String b = hash("/api/orders/{orderId}/quote", Map.of("orderId", "1"), "{ \"b\":2,\n\"a\":1 }");
        assertThat(a).isEqualTo(b).hasSize(64);
        assertThat(hash("/api/orders/{orderId}/quote", Map.of("orderId", "2"), "{\"a\":1,\"b\":2}")).isNotEqualTo(a);
        assertThat(hash("/api/orders/{orderId}/decline", Map.of("orderId", "1"), "{\"a\":1,\"b\":2}")).isNotEqualTo(a);
    }

    @Test
    @DisplayName("碼位排序：補充平面字元排在 U+FFxx 之後（String.compareTo 以 UTF-16 code unit 會排錯）")
    void codePointOrder() {
        String emoji = "\uD83D\uDE00"; // U+1F600
        String halfwidth = "\uFF61";   // U+FF61
        assertThat(halfwidth.compareTo(emoji)).isPositive();
        assertThat(IdempotencyRequestHasher.compareCodePoints(halfwidth, emoji)).isNegative();
        assertThat(canon("{\"" + emoji + "\":1,\"" + halfwidth + "\":2}"))
                .isEqualTo("{\"" + halfwidth + "\":2,\"" + emoji + "\":1}");
    }

    @Test
    @DisplayName("無法解析的 JSON 以原始位元組計 hash（交給 message converter 回 400）")
    void invalidJsonFallsBack() {
        assertThat(hash("/t", Map.of(), "{bad")).isNotEqualTo(hash("/t", Map.of(), "{bad2"));
    }

    @Test
    @DisplayName("multipart：欄位依名稱排序 + 檔案 SHA-256")
    void multipart() {
        String a = IdempotencyRequestHasher.hashMultipart("/u", Map.of(),
                Map.of("b", List.of("2"), "a", List.of("1")), Map.of("file", List.of("aa")));
        String b = IdempotencyRequestHasher.hashMultipart("/u", Map.of(),
                Map.of("a", List.of("1"), "b", List.of("2")), Map.of("file", List.of("aa")));
        String c = IdempotencyRequestHasher.hashMultipart("/u", Map.of(),
                Map.of("a", List.of("1"), "b", List.of("2")), Map.of("file", List.of("bb")));
        assertThat(a).isEqualTo(b).isNotEqualTo(c);
    }
}
