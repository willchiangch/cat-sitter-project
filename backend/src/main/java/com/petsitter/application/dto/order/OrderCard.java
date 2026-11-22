package com.petsitter.application.dto.order;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** E3 / E4 列表卡片。 */
public record OrderCard(UUID orderId, String status, String counterpartName, LocalDate firstServiceDate,
                        LocalDate lastServiceDate, int baseAmount, Integer totalAmount, boolean hasOpenModification,
                        String initialPaymentStatus, OffsetDateTime createdAt, OffsetDateTime terminalAt, Integer netPaid) {
}
