package com.petsitter.application.common.idempotency;

import java.util.UUID;

/**
 * 一個冪等請求的身分：範圍（使用者、{@code scope} = HTTP method + 路由樣板、key）與 request hash。
 * 由 {@link IdempotencyInterceptor} 建立，Service 以 {@link IdempotencyService#currentContext()} 取得。
 */
public record IdempotencyContext(UUID userId, String scope, String key, String requestHash) {
}
