package com.petsitter.interfaces.controller.order;

import com.petsitter.application.common.idempotency.Idempotent;
import com.petsitter.application.dto.order.ModificationRequests;
import com.petsitter.application.dto.order.ModificationResponses;
import com.petsitter.application.dto.order.ModificationSummary;
import com.petsitter.application.service.order.ModificationQueryService;
import com.petsitter.application.service.order.ModificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 變更端點 E17–E23（SD-ORDER §6.2 / §6.3）。 */
@RestController
@RequestMapping("/api/orders/{orderId}/modifications")
@RequiredArgsConstructor
public class OrderModificationController {

    private final ModificationService modificationService;
    private final ModificationQueryService modificationQueryService;

    /** E17 發起變更（OWNER → REQUESTED；SITTER → QUOTED）。 */
    @PostMapping
    @Idempotent
    @PreAuthorize("hasAnyRole('OWNER','SITTER')")
    public ResponseEntity<ModificationResponses.Initiated> initiate(@PathVariable UUID orderId,
                                                                    @RequestHeader(value = OrderController.KEY, required = false) String key,
                                                                    @RequestBody(required = false) ModificationRequests.Initiate body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(modificationService.initiate(orderId, body));
    }

    /** E18 變更歷史（created_at 倒序）。 */
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','SITTER','ADMIN')")
    public List<ModificationSummary> list(@PathVariable UUID orderId) {
        return modificationQueryService.list(orderId);
    }

    /** E19 協商頁。 */
    @GetMapping("/{modificationId}")
    @PreAuthorize("hasAnyRole('OWNER','SITTER','ADMIN')")
    public ModificationResponses.Detail detail(@PathVariable UUID orderId, @PathVariable UUID modificationId) {
        return modificationQueryService.detail(orderId, modificationId);
    }

    /** E20 T3 保母報價。 */
    @PostMapping("/{modificationId}/quote")
    @Idempotent
    @PreAuthorize("hasRole('SITTER')")
    public ModificationResponses.Quoted quote(@PathVariable UUID orderId, @PathVariable UUID modificationId,
                                              @RequestHeader(value = OrderController.KEY, required = false) String key,
                                              @RequestBody(required = false) ModificationRequests.Quote body) {
        return modificationService.quote(orderId, modificationId, body);
    }

    /** E21 T4 拒絕（SITTER 拒 REQUESTED、OWNER 拒 QUOTED）。 */
    @PostMapping("/{modificationId}/reject")
    @Idempotent
    @PreAuthorize("hasAnyRole('OWNER','SITTER')")
    public ModificationResponses.Ended reject(@PathVariable UUID orderId, @PathVariable UUID modificationId,
                                              @RequestHeader(value = OrderController.KEY, required = false) String key,
                                              @RequestBody(required = false) ModificationRequests.Reject body) {
        return modificationService.reject(orderId, modificationId, body);
    }

    /** E22 T5 發起者撤回。 */
    @PostMapping("/{modificationId}/withdraw")
    @Idempotent
    @PreAuthorize("hasAnyRole('OWNER','SITTER')")
    public ModificationResponses.Ended withdraw(@PathVariable UUID orderId, @PathVariable UUID modificationId,
                                                @RequestHeader(value = OrderController.KEY, required = false) String key) {
        return modificationService.withdraw(orderId, modificationId);
    }

    /** E23 飼主同意（三分支）。 */
    @PostMapping("/{modificationId}/accept")
    @Idempotent
    @PreAuthorize("hasRole('OWNER')")
    public ModificationResponses.Accepted accept(@PathVariable UUID orderId, @PathVariable UUID modificationId,
                                                 @RequestHeader(value = OrderController.KEY, required = false) String key) {
        return modificationService.accept(orderId, modificationId);
    }
}
