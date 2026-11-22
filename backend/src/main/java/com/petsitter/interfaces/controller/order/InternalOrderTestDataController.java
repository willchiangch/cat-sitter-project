package com.petsitter.interfaces.controller.order;

import com.petsitter.application.dto.order.TestDataRequests;
import com.petsitter.application.service.order.OrderTestDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** SD-ORDER §10.4 訂單 inspect / shift-time（{@code X-Internal-Secret}；只限測試帳號訂單，其他 404）。 */
@RestController
@RequestMapping("/api/internal/test-data/orders/{orderId}")
@RequiredArgsConstructor
public class InternalOrderTestDataController {

    private final OrderTestDataService testDataService;

    @GetMapping("/inspect")
    public Map<String, Object> inspect(@PathVariable UUID orderId) {
        return testDataService.inspect(orderId);
    }

    @PostMapping("/shift-time")
    public Map<String, Object> shiftTime(@PathVariable UUID orderId, @RequestBody(required = false) TestDataRequests.ShiftTime body) {
        return testDataService.shiftTime(orderId, body);
    }
}
