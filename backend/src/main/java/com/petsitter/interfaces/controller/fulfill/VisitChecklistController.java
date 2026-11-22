package com.petsitter.interfaces.controller.fulfill;

import com.petsitter.application.common.idempotency.Idempotent;
import com.petsitter.application.dto.fulfill.FulfillRequests;
import com.petsitter.application.dto.fulfill.VisitResponses;
import com.petsitter.application.service.fulfill.VisitChecklistService;
import com.petsitter.application.service.fulfill.VisitReadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** SOP 檢核項端點 F8–F12（SD-FULFILL §6.2 / §6.3；PRD-028）。 */
@RestController
@RequestMapping(VisitController.VISIT + "/checklist")
@RequiredArgsConstructor
public class VisitChecklistController {

    private final VisitReadService readService;
    private final VisitChecklistService checklistService;

    /** F8 檢核項清單。 */
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','SITTER','ADMIN')")
    public VisitResponses.Checklist list(@PathVariable UUID orderId, @PathVariable UUID visitId) {
        return readService.checklist(orderId, visitId);
    }

    /** F9 新增檢核項。 */
    @PostMapping("/items")
    @Idempotent
    @PreAuthorize("hasRole('SITTER')")
    public ResponseEntity<VisitResponses.ItemCreated> add(@PathVariable UUID orderId, @PathVariable UUID visitId,
                                                          @RequestHeader(value = VisitController.KEY, required = false) String key,
                                                          @RequestBody(required = false) FulfillRequests.AddItem body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(checklistService.add(orderId, visitId, body));
    }

    /** F10 刪除檢核項（重送 404）。 */
    @DeleteMapping("/items/{itemId}")
    @PreAuthorize("hasRole('SITTER')")
    public VisitResponses.Checklist delete(@PathVariable UUID orderId, @PathVariable UUID visitId, @PathVariable UUID itemId) {
        return checklistService.delete(orderId, visitId, itemId);
    }

    /** F11 排序（整值覆寫）。 */
    @PutMapping("/order")
    @PreAuthorize("hasRole('SITTER')")
    public VisitResponses.Checklist reorder(@PathVariable UUID orderId, @PathVariable UUID visitId,
                                            @RequestBody(required = false) FulfillRequests.Reorder body) {
        return checklistService.reorder(orderId, visitId, body);
    }

    /** F12 打勾 / 取消打勾（整值覆寫）。 */
    @PutMapping("/items/{itemId}/check")
    @PreAuthorize("hasRole('SITTER')")
    public VisitResponses.ItemChecked check(@PathVariable UUID orderId, @PathVariable UUID visitId, @PathVariable UUID itemId,
                                            @RequestBody(required = false) FulfillRequests.Check body) {
        return checklistService.check(orderId, visitId, itemId, body);
    }
}
