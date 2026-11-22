package com.petsitter.application.port.transitional;

import com.petsitter.application.port.PublicProfileQuery;
import com.petsitter.domain.model.Profile;
import com.petsitter.domain.repository.ProfileRepository;
import com.petsitter.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** SD-SUPPLY 未重建前的過渡實作。 */
@Component
@RequiredArgsConstructor
public class TransitionalPublicProfileQuery implements PublicProfileQuery {

    private final ProfileRepository profileRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public boolean sitterExists(UUID sitterId) {
        return userRepository.findById(sitterId).filter(u -> !u.isDeleted()).isPresent()
                && profileRepository.findByUserIdAndType(sitterId, "SITTER").isPresent();
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public boolean isVisible(UUID sitterId) {
        return profileRepository.findByUserIdAndType(sitterId, "SITTER").map(Profile::isVisible).orElse(false);
    }
}
