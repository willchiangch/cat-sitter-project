package com.petsitter.application.port;

import java.util.UUID;

/** KYC 與停權（SD-ORDER §2.4.2；SD-TRUST 實作，過渡）。 */
public interface SitterStandingQuery {

    SitterStanding get(UUID sitterId);

    /** {@code kycStatus}：UNVERIFIED / PENDING_REVIEW / VERIFIED / REJECTED；停權另以 {@code suspended} 表示。 */
    record SitterStanding(String kycStatus, boolean suspended) {
        public boolean verified() {
            return suspended || "VERIFIED".equals(kycStatus);
        }
    }
}
