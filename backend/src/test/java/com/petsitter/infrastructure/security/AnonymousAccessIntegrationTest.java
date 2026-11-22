package com.petsitter.infrastructure.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * SD-GLOBAL-SPEC §3.4.1 {@code AnonymousAccessIntegrationTest}（NFR-003 SEC-AZ-2）：自 {@link RequestMappingHandlerMapping}
 * 列舉所有 handler，以匿名請求逐一呼叫；不在白名單者必須 401 {@code UNAUTHENTICATED}（需要身分的 auth 端點也是 401，不是 403）；
 * {@code /api/internal/**} 匿名 → 403 {@code FORBIDDEN}（§3.7）。
 */
@DisplayName("NFR-003 SEC-AZ-2 AnonymousAccessIntegrationTest")
class AnonymousAccessIntegrationTest extends SecurityIntegrationTestBase {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private Environment environment;

    private record Route(HttpMethod method, String pattern, String url) {
    }

    private List<Route> routes() {
        List<Route> out = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            List<HttpMethod> hm = methods.isEmpty() ? List.of(HttpMethod.GET, HttpMethod.POST)
                    : methods.stream().map(m -> HttpMethod.valueOf(m.name())).toList();
            for (String pattern : info.getPatternValues()) {
                String url = pattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString()).replace("/**", "/x");
                for (HttpMethod m : hm) {
                    out.add(new Route(m, pattern, url));
                }
            }
        }
        return out;
    }

    private boolean whitelisted(Route r) {
        MockHttpServletRequest req = request(r.method(), r.url()).buildRequest(new MockServletContext());
        return AnonymousEndpoints.matches(environment, req);
    }

    @Test
    @DisplayName("§3.4.1 所有 handler 匿名呼叫：非白名單 → 401 UNAUTHENTICATED；/api/internal/** → 403 FORBIDDEN")
    void everyHandler() throws Exception {
        List<Route> routes = routes();
        assertThat(routes).hasSizeGreaterThan(100);
        List<String> violations = new ArrayList<>();
        int checked = 0;
        for (Route r : routes) {
            if (whitelisted(r)) {
                continue;
            }
            MvcResult res = mvc.perform(request(r.method(), r.url())).andReturn();
            int status = res.getResponse().getStatus();
            String body = res.getResponse().getContentAsString();
            boolean internal = r.pattern().startsWith("/api/internal/");
            int expected = internal ? 403 : 401;
            String expectedCode = internal ? "FORBIDDEN" : "UNAUTHENTICATED";
            if (status != expected || !body.contains("\"" + expectedCode + "\"")) {
                violations.add(r.method() + " " + r.pattern() + " → " + status + " " + body);
            }
            checked++;
        }
        assertThat(violations).isEmpty();
        assertThat(checked).isGreaterThan(100);
    }

    @Test
    @DisplayName("§3.4.1 需要身分的 auth 端點匿名 → 401（不是 403 ROLE_MISMATCH）；靜態檔路徑不再匿名放行")
    void identityAuthEndpointsAnd401() throws Exception {
        for (String url : List.of("/api/auth/switch-role", "/api/auth/deactivate", "/api/auth/logout-all-devices",
                "/api/auth/webauthn/register/options", "/api/auth/webauthn/register/verify")) {
            MvcResult r = mvc.perform(post(url)).andReturn();
            assertThat(r.getResponse().getStatus()).as(url).isEqualTo(401);
            assertThat(r.getResponse().getContentAsString()).contains("UNAUTHENTICATED");
        }
        assertThat(mvc.perform(get("/api/auth/webauthn/credentials")).andReturn().getResponse().getStatus()).isEqualTo(401);
        for (String url : List.of("/", "/index.html", "/assets/app.js", "/static/x.css", "/main.js")) {
            assertThat(mvc.perform(get(url)).andReturn().getResponse().getStatus()).as(url).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("§3.4 身分只認 Authorization：只帶 __session cookie（內含有效 JWT）→ 401 UNAUTHENTICATED")
    void cookieIsNotIdentity() throws Exception {
        String jwt = ownerToken().substring("Bearer ".length());
        MvcResult r = mvc.perform(get("/api/orders/{id}", UUID.randomUUID()).cookie(new Cookie("__session", jwt))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(r.getResponse().getContentAsString()).contains("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("§3.4.1 白名單端點匿名可呼叫（公開方案 200、E31 200、actuator health 200）")
    void whitelistReachable() throws Exception {
        assertThat(mvc.perform(get("/api/sitters/{id}/plans", sitter)).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/api/sitters/{id}/booking-context", sitter)).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
        // actuator 只暴露 health（§6.3、SEC-LOG-3.1）
        assertThat(mvc.perform(get("/actuator/env").header("Authorization", ownerToken())).andReturn().getResponse().getStatus()).isEqualTo(404);
    }
}
