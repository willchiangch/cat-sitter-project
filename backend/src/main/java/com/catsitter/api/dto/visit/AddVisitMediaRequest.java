package com.catsitter.api.dto.visit;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record AddVisitMediaRequest(
    @NotBlank String mediaUrl,
    String caption,
    String mediaType // "IMAGE" or "VIDEO"
) {}
