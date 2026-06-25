package com.petsitter.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdatePublicProfileRequest {

    @NotBlank(message = "暱稱不可為空")
    @Size(max = 100, message = "暱稱最多 100 字")
    private String displayName;

    @Size(max = 2000, message = "自我介紹最多 2000 字")
    private String bio;

    @NotNull(message = "是否公開狀態不可為空")
    private Boolean isVisible;

    @Size(max = 10, message = "最多僅能設定 10 個標籤")
    private List<@Size(max = 10, message = "單一標籤最多 10 字") String> tags;

    @Valid
    private List<ServiceAreaDto> serviceAreas;

    @NotNull(message = "版本號不可為空")
    private Integer version;
}
