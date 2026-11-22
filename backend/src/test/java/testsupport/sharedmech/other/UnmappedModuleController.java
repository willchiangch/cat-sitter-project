package testsupport.sharedmech.other;

import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** 不在模組 advice 範圍內的 controller：只受 GlobalExceptionHandler 對映。 */
@RestController
@RequestMapping("/api/test-global")
public class UnmappedModuleController {

    public record IntBody(Integer amount) {
    }

    @GetMapping("/sitter-only")
    @PreAuthorize("hasRole('SITTER')")
    public Map<String, Object> sitterOnly() {
        return Map.of("ok", true);
    }

    @GetMapping("/ownership")
    public Map<String, Object> ownership() {
        throw new AccessDeniedException("not yours");
    }

    @GetMapping("/items/{id}")
    public Map<String, Object> item(@PathVariable UUID id) {
        return Map.of("id", id.toString());
    }

    @PostMapping(value = "/amount", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> amount(@RequestBody IntBody body) {
        return Map.of("amount", body.amount());
    }

    @GetMapping("/boom")
    public Map<String, Object> boom() {
        throw new UnsupportedOperationException("secret detail");
    }
}
