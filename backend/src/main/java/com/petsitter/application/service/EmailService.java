package com.petsitter.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 透過 Resend REST API 寄送交易型郵件 (PRD-000 忘記密碼流程)。
 * 沒有設定 RESEND_API_KEY 時 (例如本地開發) 僅記錄 log，不會真的呼叫外部 API。
 */
@Slf4j
@Service
public class EmailService {

    private static final String RESEND_ENDPOINT = "https://api.resend.com/emails";

    private final String apiKey;
    private final String fromAddress;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EmailService(
            @Value("${app.email.resend-api-key}") String apiKey,
            @Value("${app.email.from-address}") String fromAddress) {
        this.apiKey = apiKey;
        this.fromAddress = fromAddress;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public void sendEmail(String to, String subject, String html) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("RESEND_API_KEY 未設定，略過實際寄信。收件者: {}, 主旨: {}", to, subject);
            return;
        }

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "from", fromAddress,
                    "to", List.of(to),
                    "subject", subject,
                    "html", html
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(RESEND_ENDPOINT))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                log.error("Resend 寄信失敗，狀態碼: {}, 回應: {}", response.statusCode(), response.body());
            } else {
                log.info("已透過 Resend 寄出郵件給 {}", to);
            }
        } catch (Exception e) {
            log.error("寄信時發生例外，收件者: {}", to, e);
        }
    }
}
