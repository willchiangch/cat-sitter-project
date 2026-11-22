package com.petsitter.interfaces.controller.fulfill;

import com.petsitter.application.dto.fulfill.FulfillRequests;
import com.petsitter.application.service.fulfill.FulfillTestDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** 測試造資料 FT1、FT2（SD-FULFILL §6.3；{@code X-Internal-Secret}；只限 {@code @e2e-journey.test} 帳號的訂單，其他 404）。 */
@RestController
@RequestMapping("/api/internal/test-data/visits/{visitId}")
@RequiredArgsConstructor
public class FulfillTestDataController {

    private final FulfillTestDataService testDataService;

    @PostMapping("/force-status")
    public Map<String, Object> forceStatus(@PathVariable UUID visitId, @RequestBody(required = false) FulfillRequests.ForceStatus body) {
        return testDataService.forceStatus(visitId, body);
    }

    @PostMapping("/shift-time")
    public Map<String, Object> shiftTime(@PathVariable UUID visitId, @RequestBody(required = false) FulfillRequests.ShiftTime body) {
        return testDataService.shiftTime(visitId, body);
    }
}
