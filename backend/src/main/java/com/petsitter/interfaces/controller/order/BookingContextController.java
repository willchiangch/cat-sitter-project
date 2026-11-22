package com.petsitter.interfaces.controller.order;

import com.petsitter.application.dto.order.BookingContextResponse;
import com.petsitter.application.service.order.BookingContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** E31 公開預約頁脈絡（匿名可，Optional JWT；SD-ORDER §6.3 E31）。 */
@RestController
@RequiredArgsConstructor
public class BookingContextController {

    private final BookingContextService bookingContextService;

    @GetMapping("/api/sitters/{sitterId}/booking-context")
    public BookingContextResponse get(@PathVariable UUID sitterId) {
        return bookingContextService.get(sitterId);
    }
}
