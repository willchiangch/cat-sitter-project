package com.petsitter.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServiceAreaDto {

    @NotBlank(message = "縣市不可為空")
    @Size(max = 50, message = "縣市名稱過長")
    private String city;

    @NotBlank(message = "行政區不可為空")
    @Size(max = 50, message = "行政區名稱過長")
    private String district;
}
