import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.Profile;
import com.catsitter.api.entity.SitterCalendarConfig;
import com.catsitter.api.entity.enums.RoleType;
import com.catsitter.api.client.GoogleCalendarClient;
import com.catsitter.api.repository.ProfileRepository;
import com.catsitter.api.repository.SitterCalendarConfigRepository;
import com.catsitter.api.service.IcalService;
import com.google.api.client.auth.oauth2.TokenResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class CalendarSyncController {

    private final GoogleCalendarClient googleCalendarClient;
    private final SitterCalendarConfigRepository calendarConfigRepository;
    private final ProfileRepository profileRepository;
    private final IcalService icalService;

    public CalendarSyncController(GoogleCalendarClient googleCalendarClient,
                                  SitterCalendarConfigRepository calendarConfigRepository,
                                  ProfileRepository profileRepository,
                                  IcalService icalService) {
        this.googleCalendarClient = googleCalendarClient;
        this.calendarConfigRepository = calendarConfigRepository;
        this.profileRepository = profileRepository;
        this.icalService = icalService;
    }

    /**
     * Public Ical Feed for third-party calendar apps.
     */
    @GetMapping(value = "/calendar/feed/{token}.ics", produces = "text/calendar")
    public ResponseEntity<String> getIcalFeed(@PathVariable("token") String token) {
        return calendarConfigRepository.findByIcalToken(token)
                .map(config -> ResponseEntity.ok(icalService.generateSitterIcal(config.getSitterProfile().getId())))
                .orElse(ResponseEntity.status(404).build());
    }

    /**
     * Get the Google Auth URL for the sitter to link their calendar.
     */
    @GetMapping("/sitters/me/calendar/auth-url")
    public ResponseEntity<Map<String, String>> getAuthUrl() throws Exception {
        String url = googleCalendarClient.getAuthorizationUrl();
        return ResponseEntity.ok(Collections.singletonMap("url", url));
    }

    /**
     * Reset the Ical Feed Token for the current sitter.
     */
    @PostMapping("/sitters/me/calendar/reset-token")
    public ResponseEntity<Map<String, String>> resetIcalToken(@AuthenticationPrincipal Account account) {
        Profile sitterProfile = profileRepository.findByAccountIdAndRoleType(account.getId(), RoleType.SITTER)
                .orElseThrow(() -> new RuntimeException("Sitter profile not found."));

        SitterCalendarConfig config = calendarConfigRepository.findBySitterProfileId(sitterProfile.getId())
                .orElseGet(() -> {
                    SitterCalendarConfig newConfig = new SitterCalendarConfig();
                    newConfig.setSitterProfile(sitterProfile);
                    newConfig.setProvider("NONE");
                    newConfig.setAccessToken("NONE");
                    newConfig.setExpiresAt(Instant.now());
                    return newConfig;
                });
        
        String newToken = UUID.randomUUID().toString().replace("-", "");
        config.setIcalToken(newToken);
        calendarConfigRepository.save(config);

        return ResponseEntity.ok(Map.of("icalToken", newToken, "feedUrl", "/api/v1/calendar/feed/" + newToken + ".ics"));
    }

    /**
     * Callback from Google OAuth2 redirect.
     */
    @GetMapping("/sitters/me/calendar/callback")
    public ResponseEntity<String> handleCallback(
            @RequestParam("code") String code, 
            @AuthenticationPrincipal Account account) throws Exception {
        
        if (account == null) {
            return ResponseEntity.status(401).body("User not authenticated.");
        }

        Profile sitterProfile = profileRepository.findByAccountIdAndRoleType(account.getId(), RoleType.SITTER)
                .orElseThrow(() -> new RuntimeException("Sitter profile not found for current user."));

        TokenResponse tokenResponse = googleCalendarClient.getTokens(code);

        // Store or update configuration
        SitterCalendarConfig config = calendarConfigRepository.findBySitterProfileId(sitterProfile.getId())
                .orElse(new SitterCalendarConfig());
        
        config.setSitterProfile(sitterProfile);
        config.setProvider("GOOGLE");
        config.setAccessToken(tokenResponse.getAccessToken());
        
        if (tokenResponse.getRefreshToken() != null) {
            config.setRefreshToken(tokenResponse.getRefreshToken());
        }
        
        if (config.getIcalToken() == null) {
            config.setIcalToken(UUID.randomUUID().toString().replace("-", ""));
        }
        
        config.setExpiresAt(Instant.now().plusSeconds(tokenResponse.getExpiresInSeconds() != null ? tokenResponse.getExpiresInSeconds() : 3600));
        
        calendarConfigRepository.save(config);

        return ResponseEntity.ok("Successfully linked Google Calendar! All future confirmed bookings will be synced.");
    }

    /**
     * Check current sync status.
     */
    @GetMapping("/sitters/me/calendar/status")
    public ResponseEntity<Map<String, Object>> getSyncStatus(@AuthenticationPrincipal Account account) {
        Profile sitterProfile = profileRepository.findByAccountIdAndRoleType(account.getId(), RoleType.SITTER)
                .orElseThrow(() -> new RuntimeException("Sitter profile not found."));

        Optional<SitterCalendarConfig> configOpt = calendarConfigRepository.findBySitterProfileId(sitterProfile.getId());
        
        if (configOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of("linked", false, "provider", "NONE"));
        }

        SitterCalendarConfig config = configOpt.get();
        return ResponseEntity.ok(Map.of(
            "linked", !"NONE".equals(config.getProvider()),
            "provider", config.getProvider(),
            "hasIcalToken", config.getIcalToken() != null,
            "feedUrl", config.getIcalToken() != null ? "/api/v1/calendar/feed/" + config.getIcalToken() + ".ics" : null
        ));
    }
}
