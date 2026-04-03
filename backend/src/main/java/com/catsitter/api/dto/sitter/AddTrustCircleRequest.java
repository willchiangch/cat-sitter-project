package com.catsitter.api.dto.sitter;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public class AddTrustCircleRequest {

    @NotNull
    private UUID trustedSitterId;

    public UUID getTrustedSitterId() {
        return trustedSitterId;
    }

    public void setTrustedSitterId(UUID trustedSitterId) {
        this.trustedSitterId = trustedSitterId;
    }
}
