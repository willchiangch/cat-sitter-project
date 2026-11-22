package com.petsitter.application.port;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 毛孩存在 / 歸屬 / 邏輯刪除（SD-ORDER §2.4.2；SD-CARE 實作，過渡）。含已刪除者（{@code deleted=true}）。 */
public interface PetQuery {

    List<PetView> findForBooking(Set<UUID> petIds);

    record PetView(UUID petId, UUID ownerId, boolean deleted, String type, String name) {
    }
}
