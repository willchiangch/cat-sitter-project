package com.catsitter.api.dto.payment;

public record MockPayUniRequest(
    String merTradeNo,
    String status // "1" = Success, "0" = Failure
) {}
