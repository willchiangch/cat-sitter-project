package com.catsitter.api.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.TreeMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class PayUniService {

    @Value("${application.payuni.mer-id:}")
    private String merId;

    @Value("${application.payuni.hash-key:}")
    private String hashKey;

    @Value("${application.payuni.hash-iv:}")
    private String hashIv;

    @Value("${application.payuni.api-url:https://api.payuni.com.tw/upp/v1}")
    private String apiUrl;

    /**
     * Generate PayUni UPP request parameters with signature
     */
    public Map<String, String> generateUppParams(String merTradeNo, Integer amount, String itemDesc, String notifyUrl, String returnUrl) {
        Map<String, String> params = new TreeMap<>();
        params.put("MerID", merId);
        params.put("MerTradeNo", merTradeNo);
        params.put("TradeAmt", String.valueOf(amount));
        params.put("ItemDesc", itemDesc);
        params.put("NotifyURL", notifyUrl);
        params.put("ReturnURL", returnUrl);
        params.put("Timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        
        // Generate Token (Signature)
        // Standard formula: SHA256(HashKey + Sorted Params + HashIV)
        String token = calculateToken(params);
        params.put("Token", token);
        
        return params;
    }

    public boolean verifyWebhookToken(com.catsitter.api.dto.payment.PayUniWebhookRequest request) {
        if (request.Token() == null) return false;
        java.util.Map<String, String> params = request.toTreeMap();
        String calculatedToken = calculateToken(params);
        return calculatedToken.equalsIgnoreCase(request.Token());
    }

    private String calculateToken(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        sb.append(hashKey);
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isBlank()) {
                sb.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
            }
        }
        // Remove the last &
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '&') {
            sb.setLength(sb.length() - 1);
        }
        sb.append(hashIv);
        return sha256(sb.toString()).toUpperCase();
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }
}
