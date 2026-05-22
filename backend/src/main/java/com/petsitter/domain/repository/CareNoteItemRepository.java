package com.petsitter.domain.repository;

import com.petsitter.domain.model.CareNoteItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CareNoteItemRepository extends JpaRepository<CareNoteItem, UUID> {
    List<CareNoteItem> findByCareNoteIdOrderBySortOrderAsc(UUID careNoteId);

    @Modifying
    @Query("DELETE FROM CareNoteItem c WHERE c.careNoteId = :careNoteId")
    void deleteByCareNoteId(@Param("careNoteId") UUID careNoteId);
}
