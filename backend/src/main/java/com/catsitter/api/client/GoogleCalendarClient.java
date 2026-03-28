package com.catsitter.api.client;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.ClientParametersAuthentication;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

@Component
public class GoogleCalendarClient {

    private static final String APPLICATION_NAME = "Cat Sitter PWA";
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final List<String> SCOPES = Collections.singletonList(CalendarScopes.CALENDAR_EVENTS);
    private static final String TOKEN_SERVER_URL = "https://oauth2.googleapis.com/token";

    @Value("${application.google.client-id}")
    private String clientId;

    @Value("${application.google.client-secret}")
    private String clientSecret;

    @Value("${application.google.redirect-uri}")
    private String redirectUri;

    public String getAuthorizationUrl() throws IOException, GeneralSecurityException {
        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                HTTP_TRANSPORT, JSON_FACTORY, clientId, clientSecret, SCOPES)
                .setAccessType("offline")
                .setApprovalPrompt("force") // Ensure refresh token is returned
                .build();
        return flow.newAuthorizationUrl().setRedirectUri(redirectUri).build();
    }

    public TokenResponse getTokens(String code) throws IOException, GeneralSecurityException {
        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                HTTP_TRANSPORT, JSON_FACTORY, clientId, clientSecret, SCOPES)
                .build();
        return flow.newTokenRequest(code).setRedirectUri(redirectUri).execute();
    }

    public String createEvent(String accessToken, String refreshToken, String summary, String description, 
                              OffsetDateTime start, OffsetDateTime end) throws IOException, GeneralSecurityException {
        Calendar service = getCalendarService(accessToken, refreshToken);
        
        Event event = new Event()
                .setSummary(summary)
                .setDescription(description);

        event.setStart(new EventDateTime().setDateTime(new DateTime(start.toInstant().toEpochMilli())));
        event.setEnd(new EventDateTime().setDateTime(new DateTime(end.toInstant().toEpochMilli())));

        Event createdEvent = service.events().insert("primary", event).execute();
        return createdEvent.getId();
    }

    public void deleteEvent(String accessToken, String refreshToken, String eventId) throws IOException, GeneralSecurityException {
        Calendar service = getCalendarService(accessToken, refreshToken);
        try {
            service.events().delete("primary", eventId).execute();
        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            if (e.getStatusCode() != 404) {
                throw e;
            }
        }
    }

    private Calendar getCalendarService(String accessToken, String refreshToken) throws IOException, GeneralSecurityException {
        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        
        Credential credential = new Credential.Builder(BearerToken.authorizationHeaderAccessMethod())
                .setTransport(HTTP_TRANSPORT)
                .setJsonFactory(JSON_FACTORY)
                .setTokenServerUrl(new GenericUrl(TOKEN_SERVER_URL))
                .setClientAuthentication(new ClientParametersAuthentication(clientId, clientSecret))
                .build()
                .setAccessToken(accessToken)
                .setRefreshToken(refreshToken);
        
        return new Calendar.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }
}
