import com.catsitter.api.dto.sitter.AddTrustCircleRequest;
import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.SitterTrustCircle;
import com.catsitter.api.service.SitterTrustCircleService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sitters/me/trust-circle")
public class SitterTrustCircleController {

    private final SitterTrustCircleService trustCircleService;

    public SitterTrustCircleController(SitterTrustCircleService trustCircleService) {
        this.trustCircleService = trustCircleService;
    }

    @GetMapping
    public ResponseEntity<List<SitterTrustCircle>> getTrustCircle(@AuthenticationPrincipal Account account) {
        return ResponseEntity.ok(trustCircleService.getTrustCircle(account));
    }

    @PostMapping
    public ResponseEntity<SitterTrustCircle> addMember(
            @AuthenticationPrincipal Account account,
            @Valid @RequestBody AddTrustCircleRequest request) {
        return ResponseEntity.ok(trustCircleService.addMember(account, request.getTrustedSitterId()));
    }

    @DeleteMapping("/{partnerId}")
    public ResponseEntity<Void> removeMember(
            @AuthenticationPrincipal Account account,
            @PathVariable UUID partnerId) {
        trustCircleService.removeMember(account, partnerId);
        return ResponseEntity.noContent().build();
    }
}
