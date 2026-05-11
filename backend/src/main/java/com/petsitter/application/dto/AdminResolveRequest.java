package com.petsitter.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminResolveRequest {
    @NotNull
    @Min(0)
    private Integer finalTotalAmount;
    
    private String evidenceUrl;
    
    @NotNull
    private String resolutionNote;
}
