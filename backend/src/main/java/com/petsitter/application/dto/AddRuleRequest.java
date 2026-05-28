package com.petsitter.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddRuleRequest {
    @NotBlank(message = "目標 Email 不得為空")
    private String targetEmail;

    @NotBlank(message = "規則類型不得為空")
    private String ruleType; // BLACK, WHITE, NO_QUESTIONNAIRE

    @NotBlank(message = "範圍類型不得為空")
    private String scopeType; // GLOBAL, PLAN

    private UUID planId;
}
