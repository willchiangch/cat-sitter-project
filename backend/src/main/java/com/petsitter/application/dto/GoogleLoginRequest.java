package com.petsitter.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GoogleLoginRequest {
    @NotBlank(message = "idToken 不能為空")
    private String idToken;

    /**
     * 選填。首次使用 Google 登入（Email 尚未有對應帳號）時，前端需帶入選定的角色才會正式建立帳號；
     * 未帶入時後端回傳 NEEDS_ROLE_SELECTION，不建立帳號。
     */
    private String role;
}
