package com.petsitter.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ForbiddenKeywordDto {
    private UUID id;

    @NotBlank(message = "關鍵字不可為空")
    @Size(max = 50, message = "關鍵字長度最多 50 字")
    private String keyword;

    private UUID createdBy;
    private OffsetDateTime createdAt;
}
