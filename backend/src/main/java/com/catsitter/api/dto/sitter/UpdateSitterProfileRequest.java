package com.catsitter.api.dto.sitter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UpdateSitterProfileRequest(
    @NotBlank @Size(max = 100) String name,
    @Size(max = 1024) String avatarUrl,
    @Size(max = 50) String phone,
    List<String> serviceAreas,
    @Size(max = 2000) String bioSummary,
    List<String> professionalLabels,
    String bankCode,
    String bankAccount,
    String bankAccountHolder
) {}
