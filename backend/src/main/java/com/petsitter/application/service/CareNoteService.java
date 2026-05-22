package com.petsitter.application.service;

import com.petsitter.application.dto.CareNoteDto;
import com.petsitter.application.dto.CareNoteItemDto;
import com.petsitter.application.dto.CareNoteTemplateDto;
import com.petsitter.domain.model.CareNote;
import com.petsitter.domain.model.CareNoteItem;
import com.petsitter.domain.model.CareNoteTemplate;
import com.petsitter.domain.model.CareNoteTemplateItem;
import com.petsitter.domain.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CareNoteService {

    private final CareNoteRepository careNoteRepository;
    private final CareNoteItemRepository careNoteItemRepository;
    private final CareNoteTemplateRepository templateRepository;
    private final CareNoteTemplateItemRepository templateItemRepository;
    private final AdvisoryLockRepository advisoryLockRepository;
    
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;
    private final SystemConfigService systemConfigService;

    private static final List<String> SECTIONS = Arrays.asList(
            "SERVICE", "CONTACT", "WARNING", "PREFERENCE", "HOSPITAL", "OTHER"
    );

    @Transactional(readOnly = true)
    public CareNoteDto getCareNote(UUID sitterId, UUID ownerId) {
        Optional<CareNote> headerOpt = careNoteRepository.findBySitterIdAndOwnerId(sitterId, ownerId);
        
        if (headerOpt.isEmpty()) {
            // 首次存取若查無 Header，回傳全空結構
            Map<String, List<CareNoteItemDto>> emptySections = new HashMap<>();
            SECTIONS.forEach(sec -> emptySections.put(sec, new ArrayList<>()));
            return CareNoteDto.builder()
                    .careNoteId(null)
                    .sitterId(sitterId)
                    .ownerId(ownerId)
                    .sections(emptySections)
                    .build();
        }

        CareNote header = headerOpt.get();
        List<CareNoteItem> items = careNoteItemRepository.findByCareNoteIdOrderBySortOrderAsc(header.getId());
        
        Map<String, List<CareNoteItemDto>> sections = new HashMap<>();
        SECTIONS.forEach(sec -> sections.put(sec, new ArrayList<>()));
        
        for (CareNoteItem item : items) {
            sections.get(item.getSectionType()).add(mapToDto(item));
        }

        return CareNoteDto.builder()
                .careNoteId(header.getId())
                .sitterId(sitterId)
                .ownerId(ownerId)
                .sections(sections)
                .build();
    }

    @Transactional
    public UUID saveCareNote(UUID sitterId, UUID ownerId, List<CareNoteItemDto> dtoList) {
        try {
            CareNote header = careNoteRepository.findBySitterIdAndOwnerId(sitterId, ownerId)
                    .orElseGet(() -> {
                        CareNote newHeader = CareNote.builder()
                                .sitterId(sitterId)
                                .ownerId(ownerId)
                                .version(0)
                                .build();
                        return careNoteRepository.save(newHeader);
                    });

            careNoteItemRepository.deleteByCareNoteId(header.getId());
            
            saveItemsWithOrder(header.getId(), dtoList);

            auditLogService.writeLog(sitterId, "SAVE_CARE_NOTE", "SUCCESS", "Saved notes for owner " + ownerId);
            
            try {
                notificationService.sendNotification(ownerId, "記事本已更新");
            } catch (Exception e) {
                log.error("Failed to send notification", e);
            }
            return header.getId();
        } catch (Exception e) {
            auditLogService.writeLog(sitterId, "SAVE_CARE_NOTE", "FAILED", e.getMessage());
            throw e;
        }
    }

    private void saveItemsWithOrder(UUID careNoteId, List<CareNoteItemDto> dtoList) {
        Map<String, Integer> orderTracker = new HashMap<>();
        SECTIONS.forEach(sec -> orderTracker.put(sec, 0));

        List<CareNoteItem> newItems = new ArrayList<>();
        for (CareNoteItemDto dto : dtoList) {
            String sec = dto.getSectionType();
            int currentOrder = orderTracker.getOrDefault(sec, 0);
            
            newItems.add(CareNoteItem.builder()
                    .careNoteId(careNoteId)
                    .sectionType(sec)
                    .title(dto.getTitle())
                    .content(dto.getContent())
                    .sortOrder(currentOrder)
                    .build());
            
            orderTracker.put(sec, currentOrder + 1);
        }
        careNoteItemRepository.saveAll(newItems);
    }

    @Transactional
    public UUID createTemplate(UUID sitterId, String name, List<CareNoteItemDto> dtoList) {
        long lockId = Math.abs(sitterId.hashCode());
        advisoryLockRepository.acquireXactLock(lockId);

        int currentCount = templateRepository.countBySitterId(sitterId);
        int limit = systemConfigService.getTemplateLimit();
        if (currentCount >= limit) {
            throw new IllegalArgumentException("模板數量已達上限，請選擇覆蓋既有模板");
        }

        CareNoteTemplate template = CareNoteTemplate.builder()
                .sitterId(sitterId)
                .name(name)
                .version(0)
                .build();
        template = templateRepository.save(template);

        saveTemplateItemsWithOrder(template.getId(), dtoList);
        auditLogService.writeLog(sitterId, "CREATE_TEMPLATE", "SUCCESS", "Template ID: " + template.getId());
        return template.getId();
    }

    @Transactional
    public void updateTemplate(UUID sitterId, UUID templateId, String name, List<CareNoteItemDto> dtoList) {
        CareNoteTemplate template = templateRepository.findByIdAndSitterId(templateId, sitterId)
                .orElseThrow(() -> new AccessDeniedException("Template not found or not owned"));

        template.setName(name);
        templateRepository.save(template);

        templateItemRepository.deleteByTemplateId(templateId);
        saveTemplateItemsWithOrder(templateId, dtoList);

        auditLogService.writeLog(sitterId, "UPDATE_TEMPLATE", "SUCCESS", "Template ID: " + templateId);
    }

    private void saveTemplateItemsWithOrder(UUID templateId, List<CareNoteItemDto> dtoList) {
        Map<String, Integer> orderTracker = new HashMap<>();
        SECTIONS.forEach(sec -> orderTracker.put(sec, 0));

        List<CareNoteTemplateItem> newItems = new ArrayList<>();
        for (CareNoteItemDto dto : dtoList) {
            String sec = dto.getSectionType();
            int currentOrder = orderTracker.getOrDefault(sec, 0);
            
            newItems.add(CareNoteTemplateItem.builder()
                    .templateId(templateId)
                    .sectionType(sec)
                    .title(dto.getTitle())
                    .content(dto.getContent())
                    .sortOrder(currentOrder)
                    .build());
            
            orderTracker.put(sec, currentOrder + 1);
        }
        templateItemRepository.saveAll(newItems);
    }

    @Transactional
    public void deleteTemplate(UUID sitterId, UUID templateId) {
        CareNoteTemplate template = templateRepository.findByIdAndSitterId(templateId, sitterId)
                .orElseThrow(() -> new AccessDeniedException("Template not found or not owned"));
        
        templateRepository.delete(template);
        auditLogService.writeLog(sitterId, "DELETE_TEMPLATE", "SUCCESS", "Template ID: " + templateId);
    }

    @Transactional(readOnly = true)
    public List<CareNoteTemplateDto> getTemplates(UUID sitterId) {
        List<CareNoteTemplate> templates = templateRepository.findBySitterIdOrderByCreatedAtDesc(sitterId);
        return templates.stream().map(t -> {
            List<CareNoteTemplateItem> items = templateItemRepository.findByTemplateIdOrderBySortOrderAsc(t.getId());
            List<CareNoteItemDto> itemDtos = items.stream().map(this::mapTemplateItemToDto).collect(Collectors.toList());
            return CareNoteTemplateDto.builder()
                    .id(t.getId())
                    .name(t.getName())
                    .items(itemDtos)
                    .updatedAt(t.getUpdatedAt())
                    .build();
        }).collect(Collectors.toList());
    }

    @Transactional
    public void applyTemplate(UUID sitterId, UUID ownerId, UUID templateId) {
        try {
            // 驗證擁有權
            templateRepository.findByIdAndSitterId(templateId, sitterId)
                    .orElseThrow(() -> new AccessDeniedException("Template not found or not owned"));

            // 首次操作初始化 Header
            CareNote header = careNoteRepository.findBySitterIdAndOwnerId(sitterId, ownerId)
                    .orElseGet(() -> careNoteRepository.save(CareNote.builder()
                            .sitterId(sitterId)
                            .ownerId(ownerId)
                            .version(0)
                            .build()));

            List<CareNoteItem> existingItems = careNoteItemRepository.findByCareNoteIdOrderBySortOrderAsc(header.getId());
            
            // 取得 MAX(sort_order) COALESCE(max, -1) + 1
            Map<String, Integer> maxOrderMap = new HashMap<>();
            SECTIONS.forEach(sec -> maxOrderMap.put(sec, 0));
            for (CareNoteItem item : existingItems) {
                int currentOrder = item.getSortOrder();
                if (currentOrder >= maxOrderMap.get(item.getSectionType())) {
                    maxOrderMap.put(item.getSectionType(), currentOrder + 1);
                }
            }

            List<CareNoteTemplateItem> templateItems = templateItemRepository.findByTemplateIdOrderBySortOrderAsc(templateId);
            List<CareNoteItem> newItems = new ArrayList<>();

            for (CareNoteTemplateItem tItem : templateItems) {
                String sec = tItem.getSectionType();
                int nextOrder = maxOrderMap.get(sec);
                
                newItems.add(CareNoteItem.builder()
                        .careNoteId(header.getId())
                        .sectionType(sec)
                        .title(tItem.getTitle())
                        .content(tItem.getContent())
                        .sortOrder(nextOrder)
                        .build());
                
                maxOrderMap.put(sec, nextOrder + 1);
            }
            careNoteItemRepository.saveAll(newItems);

            auditLogService.writeLog(sitterId, "APPLY_TEMPLATE", "SUCCESS", "Template ID: " + templateId);

            try {
                notificationService.sendNotification(ownerId, "記事本已更新");
            } catch (Exception e) {
                log.error("Failed to send notification", e);
            }
        } catch (Exception e) {
            auditLogService.writeLog(sitterId, "APPLY_TEMPLATE", "FAILED", e.getMessage());
            throw e;
        }
    }

    private CareNoteItemDto mapToDto(CareNoteItem item) {
        return CareNoteItemDto.builder()
                .id(item.getId())
                .sectionType(item.getSectionType())
                .title(item.getTitle())
                .content(item.getContent())
                .sortOrder(item.getSortOrder())
                .build();
    }

    private CareNoteItemDto mapTemplateItemToDto(CareNoteTemplateItem item) {
        return CareNoteItemDto.builder()
                .id(item.getId())
                .sectionType(item.getSectionType())
                .title(item.getTitle())
                .content(item.getContent())
                .sortOrder(item.getSortOrder())
                .build();
    }
}
