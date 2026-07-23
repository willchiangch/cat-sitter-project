package com.petsitter.application.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RegisterResponse {
    private String status;
    private String email;
}
