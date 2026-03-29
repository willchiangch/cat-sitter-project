package com.catsitter.api.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
public class EmailService {

    private final RestClient restClient;
    private final String apiKey;
    private final String fromEmail;

    public EmailService(
            @Value("${application.resend.api-key}") String apiKey,
            @Value("${application.resend.from-email}") String fromEmail) {
        this.apiKey = apiKey;
        this.fromEmail = fromEmail;
        this.restClient = RestClient.builder()
                .baseUrl("https://api.resend.com")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    public void sendEmail(String to, String subject, String htmlContent) {
        Map<String, Object> body = Map.of(
            "from", fromEmail,
            "to", to,
            "subject", subject,
            "html", htmlContent
        );

        restClient.post()
                .uri("/emails")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }
}
