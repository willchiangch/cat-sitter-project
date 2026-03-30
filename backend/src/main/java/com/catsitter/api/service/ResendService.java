package com.catsitter.api.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@Service
public class ResendService {

    private static final Logger logger = LoggerFactory.getLogger(ResendService.class);

    @Value("${application.resend.api-key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    public void sendEmail(String to, String subject, String htmlContent) {
        String url = "https://api.resend.com/emails";

        if (apiKey == null || apiKey.equals("default_if_not_set")) {
            logger.warn("Resend API key is not set. Skipping email sending to {}", to);
            return;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, Object> body = Map.of(
                    "from", "Cat Sitter <onboarding@resend.dev>", 
                    "to", to,
                    "subject", subject,
                    "html", htmlContent
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            restTemplate.postForEntity(url, request, String.class);
            logger.info("Email sent successfully to {}", to);
        } catch (Exception e) {
            logger.error("Failed to send email to {}: {}", to, e.getMessage());
            // In a real app, we might retry or queue this
        }
    }
}
