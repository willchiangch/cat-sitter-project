package com.petsitter.application.port.transitional;

import com.petsitter.application.port.UserDisplayQuery;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.ProfileRepository;
import com.petsitter.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** SD-IDENTITY / SD-SUPPLY 未重建前的過渡實作。 */
@Component
@RequiredArgsConstructor
public class TransitionalUserDisplayQuery implements UserDisplayQuery {

    public static final String TEST_EMAIL_SUFFIX = "@e2e-journey.test";

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Map<UUID, String> sitterDisplayNames(Set<UUID> sitterIds) {
        Map<UUID, String> out = new HashMap<>();
        for (User u : userRepository.findAllById(sitterIds)) {
            String name = profileRepository.findByUserIdAndType(u.getId(), "SITTER")
                    .map(p -> p.getDisplayName()).filter(n -> n != null && !n.isBlank()).orElse(u.getFullName());
            out.put(u.getId(), name);
        }
        return out;
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Map<UUID, String> ownerDisplayNames(Set<UUID> ownerIds) {
        Map<UUID, String> out = new HashMap<>();
        for (User u : userRepository.findAllById(ownerIds)) {
            out.put(u.getId(), u.getFullName());
        }
        return out;
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public boolean isTestAccount(UUID userId) {
        return userRepository.findById(userId).map(u -> u.getEmail() != null && u.getEmail().endsWith(TEST_EMAIL_SUFFIX))
                .orElse(false);
    }
}
