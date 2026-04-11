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
        System.out.println("[EMAIL SERVICE] Initialized with API Key: " + (apiKey != null ? (apiKey.startsWith("re_") ? "SECRET_KEY" : apiKey) : "NULL"));
        System.out.flush();
    }

    public void sendEmail(String to, String subject, String htmlContent) {
        if (apiKey == null || apiKey.equals("mock_key") || apiKey.isEmpty() || apiKey.equals("re_default_key")) {
            System.out.println("\n\n");
            System.out.println("************************************************************");
            System.out.println("**********        [MOCK EMAIL DISPATCH]         **********");
            System.out.println("************************************************************");
            System.out.println("** TO:      " + to);
            System.out.println("** SUBJECT: " + subject);
            System.out.println("** CONTENT:");
            System.out.println(htmlContent);
            System.out.println("************************************************************");
            System.out.println("**********        [END OF MOCK EMAIL]           **********");
            System.out.println("************************************************************");
            System.out.println("\n\n");
            System.out.flush();
            return;
        }

        try {
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
            System.out.println("[EMAIL SERVICE] Successfully sent email to " + to);
        } catch (Exception e) {
            System.err.println("[EMAIL SERVICE] Critical error sending email via Resend: " + e.getMessage());
            if (e instanceof org.springframework.web.client.HttpClientErrorException hex) {
                System.err.println("[EMAIL SERVICE] Response body from Resend: " + hex.getResponseBodyAsString());
            }
            // We do NOT re-throw here to prevent downstream 401/500 errors if email fails.
            System.err.println("[EMAIL SERVICE] Falling back to MOCK log for visibility:");
            System.out.println("TO: " + to + " SUBJECT: " + subject + " CODE extracted: " + (htmlContent.contains("font-size: 32px") ? "CHECK LOG" : "N/A"));
        }
    }
}
