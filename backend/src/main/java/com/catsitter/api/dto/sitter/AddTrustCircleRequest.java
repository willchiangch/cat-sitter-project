package com.catsitter.api.dto.sitter;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public class AddTrustCircleRequest {

    @NotNull
    private UUID sitterProfileId;

    public UUID getSitterProfileId() {
        return sitterProfileId;
    }

    public void setSitterProfileId(UUID sitterProfileId) {
        this.sitterProfileId = sitterProfileId;
    }
}
