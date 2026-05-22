package com.petsitter.domain.repository;

import com.petsitter.domain.model.CareNoteTemplateItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CareNoteTemplateItemRepository extends JpaRepository<CareNoteTemplateItem, UUID> {
    List<CareNoteTemplateItem> findByTemplateIdOrderBySortOrderAsc(UUID templateId);

    @Modifying
    @Query("DELETE FROM CareNoteTemplateItem c WHERE c.templateId = :templateId")
    void deleteByTemplateId(@Param("templateId") UUID templateId);
}
