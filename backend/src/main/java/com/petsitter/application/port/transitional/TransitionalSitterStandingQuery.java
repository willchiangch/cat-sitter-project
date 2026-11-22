package com.petsitter.application.port.transitional;

import com.petsitter.application.port.SitterStandingQuery;
import com.petsitter.domain.repository.ProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** SD-TRUST 未重建前的過渡實作：{@code profiles.kyc_status}；{@code SUSPENDED} = ADMIN 停權。 */
@Component
@RequiredArgsConstructor
public class TransitionalSitterStandingQuery implements SitterStandingQuery {

    private final ProfileRepository profileRepository;

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public SitterStanding get(UUID sitterId) {
        String kyc = profileRepository.findByUserIdAndType(sitterId, "SITTER").map(p -> p.getKycStatus()).orElse("UNVERIFIED");
        return new SitterStanding("SUSPENDED".equals(kyc) ? "VERIFIED" : kyc, "SUSPENDED".equals(kyc));
    }
}
