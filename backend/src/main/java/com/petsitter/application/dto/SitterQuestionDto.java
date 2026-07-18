package com.petsitter.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SitterQuestionDto {
    private UUID id;

    @NotBlank(message = "題目內容不能為空")
    @Size(max = 200, message = "題目內容上限 200 字")
    private String questionText;

    @NotNull(message = "回答類型不能為空")
    @Pattern(regexp = "^(RADIO|CHECKBOX|INPUT|TEXTAREA)$", message = "回答類型必須是 RADIO, CHECKBOX, INPUT 或 TEXTAREA")
    private String answerType;

    @Builder.Default
    private List<@Size(max = 50, message = "選項內容上限 50 字") String> options = List.of();

    private boolean required;
    private int sortOrder;
    private boolean isActive;
    private Integer version;
}
