package com.petsitter.interfaces.controller.fulfill;

import com.petsitter.application.common.idempotency.Idempotent;
import com.petsitter.application.dto.fulfill.FulfillRequests;
import com.petsitter.application.dto.fulfill.VisitResponses;
import com.petsitter.application.service.fulfill.VisitLifecycleService;
import com.petsitter.application.service.fulfill.VisitReadService;
import com.petsitter.application.service.order.OrderClock;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 行程端點 F1–F7（SD-FULFILL §6.2 / §6.3）。不用 {@code @RequirePlan}（行程不分方案、訂閱鎖定不擋）；DTO 不掛 {@code @Valid}、
 * {@code Idempotency-Key} 非必填宣告（判斷順序 G6）；身分一律取自 token。
 */
@RestController
@RequiredArgsConstructor
public class VisitController {

    static final String KEY = "Idempotency-Key";
    static final String VISIT = "/api/orders/{orderId}/visits/{visitId}";

    private final VisitReadService readService;
    private final VisitLifecycleService lifecycleService;
    private final OrderClock clock;

    /** F1 訂單行程清單。 */
    @GetMapping("/api/orders/{orderId}/visits")
    @PreAuthorize("hasAnyRole('OWNER','SITTER','ADMIN')")
    public VisitResponses.VisitList list(@PathVariable UUID orderId) {
        return readService.list(orderId);
    }

    /** F2 服務面板。 */
    @GetMapping(VISIT)
    @PreAuthorize("hasAnyRole('OWNER','SITTER','ADMIN')")
    public VisitResponses.VisitDetail detail(@PathVariable UUID orderId, @PathVariable UUID visitId) {
        return readService.detail(orderId, visitId);
    }

    /** F3 開始行程（T1，可能觸發 O9）。 */
    @PostMapping(VISIT + "/start")
    @Idempotent
    @PreAuthorize("hasRole('SITTER')")
    public VisitResponses.Started start(@PathVariable UUID orderId, @PathVariable UUID visitId,
                                        @RequestHeader(value = KEY, required = false) String key) {
        return lifecycleService.start(orderId, visitId);
    }

    /** F4 結束行程（T2）。 */
    @PostMapping(VISIT + "/complete")
    @Idempotent
    @PreAuthorize("hasRole('SITTER')")
    public VisitResponses.Completed complete(@PathVariable UUID orderId, @PathVariable UUID visitId,
                                             @RequestHeader(value = KEY, required = false) String key) {
        return lifecycleService.complete(orderId, visitId);
    }

    /** F5 行程命名（整值覆寫）。 */
    @PutMapping(VISIT + "/name")
    @PreAuthorize("hasRole('SITTER')")
    public VisitResponses.Renamed rename(@PathVariable UUID orderId, @PathVariable UUID visitId,
                                         @RequestBody(required = false) FulfillRequests.Rename body) {
        OffsetDateTime receivedAt = clock.now();
        return lifecycleService.rename(orderId, visitId, body, receivedAt);
    }

    /** F6 保母今日行程 / 待處理。 */
    @GetMapping("/api/visits/sitter/dashboard")
    @PreAuthorize("hasRole('SITTER')")
    public VisitResponses.SitterDashboard sitterDashboard() {
        return readService.sitterDashboard();
    }

    /** F7 飼主今日到府。 */
    @GetMapping("/api/visits/owner/today")
    @PreAuthorize("hasRole('OWNER')")
    public VisitResponses.OwnerToday ownerToday() {
        return readService.ownerToday();
    }
}
