package com.petsitter.application.service.order;

import com.petsitter.infrastructure.security.TokenContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/** token 身分（SD-GLOBAL-SPEC §3.4：身分一律取自 token）：userId + currentRole。 */
public record CurrentActor(UUID userId, String role) {

    public static CurrentActor fromToken() {
        UUID userId = TokenContext.getUserId();
        return new CurrentActor(userId, currentRole());
    }

    public static String currentRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            String s = a.getAuthority();
            if (s != null && s.startsWith("ROLE_")) {
                return s.substring(5);
            }
        }
        return null;
    }

    public boolean isOwnerRole() {
        return "OWNER".equals(role);
    }

    public boolean isSitterRole() {
        return "SITTER".equals(role);
    }

    public boolean isAdminRole() {
        return "ADMIN".equals(role);
    }
}
