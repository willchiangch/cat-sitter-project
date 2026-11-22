package com.petsitter.application.common.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 標在 controller 方法上宣告冪等端點（SD-GLOBAL-SPEC §2.4）。不維護寫死的端點清單。
 * <p>
 * 範圍 = （token 使用者, HTTP method + 路由樣板, {@code Idempotency-Key}）。宣告後：
 * <ul>
 *   <li>段 0：{@link IdempotencyInterceptor} 在 {@code @PreAuthorize} 之前回放已完成的同內容請求（判斷順序第 2 步）</li>
 *   <li>Service：第 6 步以 {@link IdempotencyService#currentContext()} 檢查 key（缺 / 格式錯 → 400
 *       {@code VALIDATION_FAILED}，{@code field=Idempotency-Key}）；第 7 步 {@link IdempotencyService#claim}；
 *       業務交易內 {@link IdempotencyService#complete}</li>
 *   <li>失敗段由 {@link IdempotencyInterceptor#afterCompletion} 依回應狀態碼與錯誤碼自動呼叫
 *       {@link IdempotencyService#fail}（需要依結果做清理的端點可自行先呼叫，第二次呼叫不會動資料）</li>
 * </ul>
 * Controller 以 {@code @RequestHeader(value = "Idempotency-Key", required = false)} 讀 header（GLOBAL §2.6）。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
}
