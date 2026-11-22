package com.petsitter.architecture;

import com.petsitter.infrastructure.security.AnonymousEndpoints;
import com.petsitter.infrastructure.security.SecurityConfig;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.util.pattern.PathPatternParser;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SD-GLOBAL-SPEC §3.4.1 {@code EndpointAccessRuleArchTest}（NFR-003 SEC-AZ-2）：
 * <ol>
 *   <li>所有 controller handler：不在白名單、不在 {@code /api/internal/**} 者必須掛 {@code @PreAuthorize}（類別或方法層），
 *       且表達式不得為 {@code permitAll()}；</li>
 *   <li>{@code SecurityConfig} 內 {@code permitAll()} 的 matcher 集合 = {@link AnonymousEndpoints}（單一來源），且只有
 *       {@code permitAnonymousEndpoints} 呼叫 {@code permitAll()}；</li>
 *   <li>{@link AnonymousEndpoints} 內容 = §3.4.1 表（20 條）。</li>
 * </ol>
 */
@DisplayName("NFR-003 SEC-AZ-2 EndpointAccessRuleArchTest")
class EndpointAccessRuleArchTest {

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.petsitter");

    /** §3.4.1 表原文（方法 路由樣板）；any = 任何方法。 */
    private static final Set<String> SPEC_TABLE = Set.of(
            "OPTIONS /**",
            "GET /api/sitters/*/plans",
            "GET /api/sitters/*/questions",
            "GET /api/sitters/*/booking-context",
            "GET /api/sitter/profile/*",
            "POST /api/auth/login",
            "POST /api/auth/register",
            "POST /api/auth/register/verify-otp",
            "POST /api/auth/register/resend-otp",
            "POST /api/auth/refresh",
            "POST /api/auth/logout",
            "POST /api/auth/forgot-password",
            "POST /api/auth/reset-password",
            "POST /api/auth/google",
            "POST /api/auth/webauthn/login/options",
            "POST /api/auth/webauthn/login/verify",
            "GET /actuator/health",
            "any /error",
            "PUT /local-storage/**|LOCAL_ONLY",
            "GET /swagger-ui/**,/swagger-ui.html,/v3/api-docs/**|NON_PROD");

    @Test
    @DisplayName("§3.4.1 AnonymousEndpoints 與文件表一致（20 條，含 profile 範圍）")
    void whitelistMatchesSpec() {
        Set<String> actual = AnonymousEndpoints.ENTRIES.stream().map(e -> (e.method() == null ? "any" : e.method().name()) + " "
                + String.join(",", e.patterns()) + (e.scope() == AnonymousEndpoints.Scope.ALL ? "" : "|" + e.scope().name()))
                .collect(Collectors.toSet());
        assertThat(AnonymousEndpoints.ENTRIES).hasSize(20);
        assertThat(actual).isEqualTo(SPEC_TABLE);
    }

    @Test
    @DisplayName("§3.4.1 SecurityConfig 只在 permitAnonymousEndpoints 呼叫 permitAll()；其他類別不得呼叫 permitAll()")
    void permitAllOnlyFromSingleSource() {
        List<String> offenders = new ArrayList<>();
        for (JavaClass c : MAIN) {
            for (JavaMethodCall call : c.getMethodCallsFromSelf()) {
                if (call.getTarget().getName().equals("permitAll")
                        && call.getTargetOwner().getName().contains("AuthorizeHttpRequestsConfigurer")) {
                    boolean allowed = c.isEquivalentTo(SecurityConfig.class)
                            && call.getOrigin().getName().equals("permitAnonymousEndpoints");
                    if (!allowed) {
                        offenders.add(call.getDescription());
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
        assertThat(MAIN.get(SecurityConfig.class).getMethodCallsFromSelf().stream()
                .filter(m -> m.getTarget().getName().equals("permitAll")).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("§3.4.1 非白名單、非 /api/internal/** 的 handler 都掛 @PreAuthorize，且表達式不是 permitAll()")
    void handlersRequirePreAuthorize() {
        PathPatternParser parser = PathPatternParser.defaultInstance;
        List<String> violations = new ArrayList<>();
        int handlers = 0;
        for (JavaClass jc : MAIN) {
            Class<?> type;
            if (!jc.isAnnotatedWith(Controller.class) && !jc.isMetaAnnotatedWith(Controller.class)) {
                continue;
            }
            type = jc.reflect();
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String[] prefixes = classMapping == null || classMapping.path().length == 0 ? new String[]{""} : classMapping.path();
            PreAuthorize classPa = AnnotatedElementUtils.findMergedAnnotation(type, PreAuthorize.class);
            for (Method m : type.getDeclaredMethods()) {
                RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (rm == null) {
                    continue;
                }
                handlers++;
                PreAuthorize pa = AnnotatedElementUtils.findMergedAnnotation(m, PreAuthorize.class);
                PreAuthorize effective = pa != null ? pa : classPa;
                String[] paths = rm.path().length == 0 ? new String[]{""} : rm.path();
                List<HttpMethod> methods = rm.method().length == 0 ? List.of(HttpMethod.GET, HttpMethod.POST)
                        : java.util.Arrays.stream(rm.method()).map(RequestMethod::asHttpMethod).toList();
                for (String prefix : prefixes) {
                    for (String p : paths) {
                        String route = (prefix + p).replaceAll("\\{[^}]+}", "x");
                        String concrete = route.replace("/**", "/x");
                        boolean internal = route.startsWith("/api/internal/");
                        for (HttpMethod hm : methods) {
                            boolean whitelisted = AnonymousEndpoints.ENTRIES.stream()
                                    .filter(e -> e.method() == null || e.method().equals(hm))
                                    .flatMap(e -> e.patterns().stream())
                                    .anyMatch(wp -> parser.parse(wp).matches(PathContainer.parsePath(concrete)));
                            if (whitelisted || internal) {
                                continue;
                            }
                            if (effective == null || effective.value().replace(" ", "").equals("permitAll()")) {
                                violations.add(hm + " " + prefix + p + " (" + type.getSimpleName() + "#" + m.getName() + ")");
                            }
                        }
                    }
                }
            }
        }
        assertThat(handlers).isGreaterThan(100);
        assertThat(violations).isEmpty();
    }
}
