package com.petsitter.application.port.transitional;

import com.petsitter.application.port.PayoutAccountQuery;
import com.petsitter.domain.model.BankAccountInfo;
import com.petsitter.domain.repository.ProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** SD-SUPPLY 未重建前的過渡實作（SD-ORDER §8.4.1）：讀 {@code profiles.bank_account_info}。 */
@Component
@RequiredArgsConstructor
public class TransitionalPayoutAccountQuery implements PayoutAccountQuery {

    private final ProfileRepository profileRepository;

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public boolean isConfigured(UUID sitterId) {
        return getSnapshot(sitterId).isPresent();
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Optional<PayoutAccountSnapshot> getSnapshot(UUID sitterId) {
        return profileRepository.findByUserIdAndType(sitterId, "SITTER")
                .map(p -> p.getBankAccountInfo())
                .filter(TransitionalPayoutAccountQuery::complete)
                .map(b -> new PayoutAccountSnapshot(b.getBankCode(), b.getBankBranch(), b.getBankAccount(), b.getBankPayeeName()));
    }

    private static boolean complete(BankAccountInfo b) {
        return b != null && notBlank(b.getBankCode()) && notBlank(b.getBankAccount()) && notBlank(b.getBankPayeeName());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
