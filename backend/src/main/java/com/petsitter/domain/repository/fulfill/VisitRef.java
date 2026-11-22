package com.petsitter.domain.repository.fulfill;

import java.util.UUID;

/** 鎖前投影（SD-FULFILL §2.5 第 1 點）：G4 / G5 只用這組欄位判斷，不載入 {@code Visit} 實體。 */
public record VisitRef(UUID visitId, UUID orderId, UUID sitterId, UUID ownerId) {
}
