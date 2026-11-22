package com.petsitter.application.port.transitional;

import com.petsitter.application.port.PetQuery;
import com.petsitter.domain.repository.PetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** SD-CARE 未重建前的過渡實作（含已邏輯刪除的毛孩）。 */
@Component
@RequiredArgsConstructor
public class TransitionalPetQuery implements PetQuery {

    private final PetRepository petRepository;

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<PetView> findForBooking(Set<UUID> petIds) {
        if (petIds.isEmpty()) {
            return List.of();
        }
        return petRepository.findAllById(petIds).stream()
                .map(p -> new PetView(p.getId(), p.getOwnerId(), p.isDeleted(), p.getSpecies(), p.getName()))
                .toList();
    }
}
