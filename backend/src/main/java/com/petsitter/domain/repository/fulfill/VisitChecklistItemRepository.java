package com.petsitter.domain.repository.fulfill;

import com.petsitter.domain.model.fulfill.VisitChecklistItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SD-FULFILL 擁有；只准 {@code application.service.fulfill} 依賴（FulfillAggregateArchTest）。 */
public interface VisitChecklistItemRepository extends JpaRepository<VisitChecklistItem, UUID> {

    List<VisitChecklistItem> findByVisitIdOrderBySortOrderAsc(UUID visitId);

    List<VisitChecklistItem> findByVisitIdInOrderBySortOrderAsc(Collection<UUID> visitIds);

    long countByVisitId(UUID visitId);

    /** 鎖前投影：項目所屬行程（G4）。 */
    @Query("SELECT i.visitId FROM VisitChecklistItem i WHERE i.id = :itemId")
    Optional<UUID> findVisitIdById(@Param("itemId") UUID itemId);

    /** 鎖前投影：行程目前全部項目 id（F11 G6 集合比對）。 */
    @Query("SELECT i.id FROM VisitChecklistItem i WHERE i.visitId = :visitId")
    List<UUID> findIdsByVisitId(@Param("visitId") UUID visitId);
}
