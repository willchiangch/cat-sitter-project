package com.catsitter.api.dto.visit;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record UpdateChecklistRequest(
    @NotNull
    UUID itemId,
    
    @NotNull
    Boolean isCompleted,
    
    String photoUrl
) {}
