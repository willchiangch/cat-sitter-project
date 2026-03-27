package com.catsitter.api.dto.booking;

import com.catsitter.api.entity.enums.OrderStatus;
import com.catsitter.api.entity.enums.PaymentStatus;
import java.math.BigDecimal;
import java.util.UUID;

public record BookingResponse(
    UUID id,
    UUID clientProfileId,
    UUID sitterProfileId,
    UUID serviceId,
    String serviceName,
    BigDecimal totalAmount,
    OrderStatus orderStatus,
    PaymentStatus paymentStatus
) {}
