package com.petsitter.application.common.validation;

import com.petsitter.application.exception.RequestValidationException;

import java.util.Map;

/**
 * 分頁參數（SD-GLOBAL-SPEC §2.8「分頁」、NFR-003 SEC-INPUT-5、CQ-27）：{@code size} 預設 20、全域上限 100，
 * 超過 → 400 {@code VALIDATION_FAILED}（{@code field=size}、{@code max}）；模組可訂更小上限。
 * 不依賴 {@code spring.data.web.pageable.max-page-size}（靜默截斷，不回 400）。
 */
public record PageParams(int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int GLOBAL_MAX_SIZE = 100;

    public static PageParams of(Integer page, Integer size) {
        return of(page, size, GLOBAL_MAX_SIZE);
    }

    /** {@code moduleMax} 只能比全域上限小（大於 100 時仍以 100 為準）。 */
    public static PageParams of(Integer page, Integer size, int moduleMax) {
        int max = Math.min(moduleMax, GLOBAL_MAX_SIZE);
        int p = page == null ? 0 : page;
        if (p < 0) {
            throw new RequestValidationException("page");
        }
        int s = size == null ? Math.min(DEFAULT_SIZE, max) : size;
        if (s < 1 || s > max) {
            throw new RequestValidationException("size", Map.of("max", max));
        }
        return new PageParams(p, s);
    }

    public org.springframework.data.domain.PageRequest toPageRequest() {
        return org.springframework.data.domain.PageRequest.of(page, size);
    }
}
