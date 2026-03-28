package com.catsitter.api.dto.payment;

import java.util.Map;

public record PayUniWebhookRequest(
    String MerID,
    String MerTradeNo,
    String TradeNo,
    String TradeAmt,
    String Status, // 1 = Success, 0 = Fail
    String PaymentType,
    String Token,
    String Timestamp
) {
    public Map<String, String> toTreeMap() {
        java.util.Map<String, String> map = new java.util.TreeMap<>();
        map.put("MerID", MerID);
        map.put("MerTradeNo", MerTradeNo);
        map.put("TradeNo", TradeNo);
        map.put("TradeAmt", TradeAmt);
        map.put("Status", Status);
        map.put("PaymentType", PaymentType);
        map.put("Timestamp", Timestamp);
        return map;
    }
}
