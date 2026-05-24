package com.petsitter.application.dto;

import lombok.*;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServicePlanSortRequest {
    private List<UUID> planIds;
}
