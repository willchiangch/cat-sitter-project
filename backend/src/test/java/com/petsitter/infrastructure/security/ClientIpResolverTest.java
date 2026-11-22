package com.petsitter.infrastructure.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SD-GLOBAL-SPEC §2.8「來源 IP」驗證 (a)（NFR-003 CQ-30）：四種 XFF 樣本。樣本中的網段為文件用保留位址
 * （RFC 5737 / 3849），Firebase 出口網段實測後才填入正式設定。
 */
@DisplayName("NFR-003 CQ-30 ClientIpResolverTest")
class ClientIpResolverTest {

    /** 假設的 Firebase Hosting 轉發出口網段。 */
    private static final List<String> TRUSTED = List.of("198.51.100.0/24", "2001:db8:f00::/48");
    private static final String GFE = "169.254.1.1";
    private final ClientIpResolver resolver = new ClientIpResolver(TRUSTED);

    @Test
    @DisplayName("情境 1 直打 run.app：XFF 只有實際來源 → 取該來源")
    void directRunApp() {
        assertThat(resolver.resolve("203.0.113.7", GFE)).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("情境 2 經 Firebase 轉發：最右項為可信出口（IPv4 / IPv6）→ 往前退一項取使用者 IP")
    void viaFirebase() {
        assertThat(resolver.resolve("203.0.113.7, 198.51.100.20", GFE)).isEqualTo("203.0.113.7");
        assertThat(resolver.resolve("2001:db8:1::5, 2001:db8:f00:1::9", GFE)).isEqualTo("2001:db8:1:0:0:0:0:5");
    }

    @Test
    @DisplayName("情境 3 直打並偽造 XFF：最右項（GFE 附加的實際來源）不在可信網段 → 取實際來源，偽造項不採用")
    void directWithForgedXff() {
        assertThat(resolver.resolve("1.2.3.4, 203.0.113.7", GFE)).isEqualTo("203.0.113.7");
        // 偽造成「看起來像經 Firebase」：使用者自填可信網段 IP 在最左，最右仍是實際來源
        assertThat(resolver.resolve("9.9.9.9, 198.51.100.20, 203.0.113.7", GFE)).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("情境 4 自 GCP 客戶網段直打並偽造 XFF：GCP 位址不在可信網段 → 計數鍵為該 GCP 位址；最多往前退一項")
    void gcpCustomerRangeWithForgedXff() {
        assertThat(resolver.resolve("1.2.3.4, 34.80.0.10", GFE)).isEqualTo("34.80.0.10");
        // 即使經可信出口，也只退一項（再往前的偽造項不採用）
        assertThat(resolver.resolve("1.2.3.4, 203.0.113.7, 198.51.100.20", GFE)).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("無 XFF（local）→ getRemoteAddr；最右項非 IP 字面值 → getRemoteAddr（不做 DNS 解析）")
    void fallbacks() {
        assertThat(resolver.resolve(null, "127.0.0.1")).isEqualTo("127.0.0.1");
        assertThat(resolver.resolve("", "127.0.0.1")).isEqualTo("127.0.0.1");
        assertThat(resolver.resolve("evil.example", "127.0.0.1")).isEqualTo("127.0.0.1");
        assertThat(resolver.resolve("1.2.3.4, 999.1.1.1", "127.0.0.1")).isEqualTo("127.0.0.1");
        // 可信最右項但前一項不是 IP → 用最右項
        assertThat(resolver.resolve("not-an-ip, 198.51.100.20", GFE)).isEqualTo("198.51.100.20");
        // 未設定可信網段（預設）→ 一律最右項
        assertThat(new ClientIpResolver(List.of()).resolve("203.0.113.7, 198.51.100.20", GFE)).isEqualTo("198.51.100.20");
    }

    @Test
    @DisplayName("trusted-proxy-cidrs 格式錯誤 → 啟動失敗")
    void invalidCidr() {
        assertThatThrownBy(() -> new ClientIpResolver(List.of("example.com/24"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ClientIpResolver(List.of("10.0.0.0/33"))).isInstanceOf(IllegalStateException.class);
    }
}
