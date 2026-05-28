package com.petsitter.application.service;

import com.petsitter.application.dto.PetDto;
import com.petsitter.application.dto.PetNotesDto;
import com.petsitter.domain.model.Order;
import com.petsitter.domain.model.Pet;
import com.petsitter.domain.model.PetEditLog;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.OrderRepository;
import com.petsitter.domain.repository.PetEditLogRepository;
import com.petsitter.domain.repository.PetRepository;
import com.petsitter.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PetService {

    private final PetRepository petRepository;
    private final PetEditLogRepository petEditLogRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final MediaStorageService mediaStorageService;

    @Transactional(readOnly = true)
    public List<PetDto> getPetsByOwner(UUID ownerId) {
        return petRepository.findByOwnerIdAndIsDeletedFalse(ownerId).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PetDto getPetById(UUID petId, UUID currentUserId) {
        Pet pet = petRepository.findByIdAndIsDeletedFalse(petId)
                .orElseThrow(() -> new IllegalArgumentException("找不到指定的毛孩資料"));

        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new IllegalArgumentException("找不到當前使用者"));
        String currentUserRole = user.getRole();

        // 權限檢查：必須是飼主本人，或者是與該飼主有進行中訂單的保母，或管理員
        if (!pet.getOwnerId().equals(currentUserId) && !"ADMIN".equals(currentUserRole)) {
            if (!"SITTER".equals(currentUserRole) || !isAuthorizedSitter(pet.getOwnerId(), currentUserId)) {
                throw new AccessDeniedException("權限不足，無法檢視此毛孩資料");
            }
        }

        return toDto(pet);
    }

    @Transactional
    public PetDto createPet(UUID ownerId, PetDto dto) {
        Pet pet = Pet.builder()
                .ownerId(ownerId)
                .name(dto.getName())
                .species(dto.getSpecies())
                .gender(dto.getGender())
                .neutered(dto.getNeutered())
                .weight(dto.getWeight())
                .birthYear(dto.getBirthYear())
                .photoUrl(dto.getPhotoUrl())
                .medicalPersonalityNotes(dto.getMedicalPersonalityNotes())
                .environmentalNotes(dto.getEnvironmentalNotes())
                .createdBy(ownerId)
                .updatedBy(ownerId)
                .isDeleted(false)
                .build();

        Pet saved = petRepository.save(pet);
        log.info("Successfully created pet: {} for owner: {}", saved.getId(), ownerId);
        return toDto(saved);
    }

    @Transactional
    public PetDto updatePet(UUID ownerId, UUID petId, PetDto dto) {
        Pet pet = petRepository.findByIdAndIsDeletedFalse(petId)
                .orElseThrow(() -> new IllegalArgumentException("找不到指定的毛孩資料"));

        if (!pet.getOwnerId().equals(ownerId)) {
            throw new AccessDeniedException("權限不足，僅限飼主本人編輯毛孩基本資料");
        }

        // 樂觀鎖防禦
        if (dto.getVersion() != null && !pet.getVersion().equals(dto.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(Pet.class, petId);
        }

        pet.setName(dto.getName());
        pet.setSpecies(dto.getSpecies());
        pet.setGender(dto.getGender());
        pet.setNeutered(dto.getNeutered());
        pet.setWeight(dto.getWeight());
        pet.setBirthYear(dto.getBirthYear());
        if (dto.getPhotoUrl() != null) {
            pet.setPhotoUrl(dto.getPhotoUrl());
        }
        pet.setUpdatedBy(ownerId);

        Pet saved = petRepository.save(pet);
        log.info("Successfully updated pet: {}", saved.getId());
        return toDto(saved);
    }

    @Transactional
    public void deletePet(UUID ownerId, UUID petId) {
        Pet pet = petRepository.findByIdAndIsDeletedFalse(petId)
                .orElseThrow(() -> new IllegalArgumentException("找不到指定的毛孩資料"));

        if (!pet.getOwnerId().equals(ownerId)) {
            throw new AccessDeniedException("權限不足，僅限飼主本人刪除毛孩");
        }

        // 檢查是否有進行中的服務 (未結案訂單)
        List<Order> activeOrders = orderRepository.findByOwnerIdAndStatusNotIn(
                ownerId, List.of("COMPLETED", "CANCELLED")
        );

        for (Order order : activeOrders) {
            if (order.getItems() != null) {
                for (var item : order.getItems()) {
                    if (item.getPetIds() != null && item.getPetIds().contains(petId)) {
                        throw new IllegalArgumentException("此毛孩尚有進行中的服務，無法刪除");
                    }
                }
            }
        }

        pet.setDeleted(true);
        pet.setUpdatedBy(ownerId);
        petRepository.save(pet);
        log.info("Successfully soft deleted pet: {}", petId);
    }

    @Transactional
    public PetDto updatePetNotes(UUID currentUserId, UUID petId, PetNotesDto dto) {
        Pet pet = petRepository.findByIdAndIsDeletedFalse(petId)
                .orElseThrow(() -> new IllegalArgumentException("找不到指定的毛孩資料"));

        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new IllegalArgumentException("找不到當前使用者"));
        String userRole = user.getRole();

        // 權限檢查：必須是飼主本人，或者是與該飼主有進行中訂單的保母，或管理員
        if (!pet.getOwnerId().equals(currentUserId) && !"ADMIN".equals(userRole)) {
            if (!"SITTER".equals(userRole) || !isAuthorizedSitter(pet.getOwnerId(), currentUserId)) {
                throw new AccessDeniedException("權限不足，無法編輯此毛孩的注意事項");
            }
        }

        // 樂觀鎖防禦
        if (dto.getVersion() != null && !pet.getVersion().equals(dto.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(Pet.class, petId);
        }

        String oldMedical = pet.getMedicalPersonalityNotes() != null ? pet.getMedicalPersonalityNotes() : "";
        String newMedical = dto.getMedicalPersonalityNotes() != null ? dto.getMedicalPersonalityNotes() : "";
        String oldEnv = pet.getEnvironmentalNotes() != null ? pet.getEnvironmentalNotes() : "";
        String newEnv = dto.getEnvironmentalNotes() != null ? dto.getEnvironmentalNotes() : "";

        boolean medicalChanged = !oldMedical.equals(newMedical);
        boolean envChanged = !oldEnv.equals(newEnv);

        if (medicalChanged || envChanged) {
            // 寫入編輯紀錄
            Map<String, Object> diffSummary = new HashMap<>();
            diffSummary.put("editorRole", userRole);

            Map<String, Object> changes = new HashMap<>();
            if (medicalChanged) {
                Map<String, String> medDiff = new HashMap<>();
                medDiff.put("before", oldMedical);
                medDiff.put("after", newMedical);
                changes.put("medicalPersonalityNotes", medDiff);
            }
            if (envChanged) {
                Map<String, String> envDiff = new HashMap<>();
                envDiff.put("before", oldEnv);
                envDiff.put("after", newEnv);
                changes.put("environmentalNotes", envDiff);
            }
            diffSummary.put("changes", changes);

            PetEditLog logEntry = PetEditLog.builder()
                    .petId(petId)
                    .editorId(currentUserId)
                    .diffSummary(diffSummary)
                    .isNew(true)
                    .build();

            petEditLogRepository.save(logEntry);

            pet.setMedicalPersonalityNotes(newMedical);
            pet.setEnvironmentalNotes(newEnv);
            pet.setUpdatedBy(currentUserId);
            petRepository.save(pet);
            log.info("Successfully updated notes and wrote audit log for pet: {}", petId);
        }

        return toDto(pet);
    }

    @Transactional(readOnly = true)
    public List<PetEditLog> getEditLogs(UUID petId, UUID currentUserId) {
        Pet pet = petRepository.findByIdAndIsDeletedFalse(petId)
                .orElseThrow(() -> new IllegalArgumentException("找不到指定的毛孩資料"));

        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new IllegalArgumentException("找不到當前使用者"));
        String userRole = user.getRole();

        // 權限檢查：必須是飼主本人，或者是與該飼主有進行中訂單的保母，或管理員
        if (!pet.getOwnerId().equals(currentUserId) && !"ADMIN".equals(userRole)) {
            if (!"SITTER".equals(userRole) || !isAuthorizedSitter(pet.getOwnerId(), currentUserId)) {
                throw new AccessDeniedException("權限不足，無法檢視此毛孩的異動歷程");
            }
        }

        return petEditLogRepository.findByPetIdOrderByCreatedAtDesc(petId);
    }

    @Transactional
    public String uploadAvatar(UUID ownerId, UUID petId, MultipartFile file) {
        Pet pet = petRepository.findByIdAndIsDeletedFalse(petId)
                .orElseThrow(() -> new IllegalArgumentException("找不到指定的毛孩資料"));

        if (!pet.getOwnerId().equals(ownerId)) {
            throw new AccessDeniedException("權限不足，僅限飼主上傳大頭照");
        }

        String photoUrl = mediaStorageService.uploadPetAvatar(ownerId, petId, file);
        pet.setPhotoUrl(photoUrl);
        pet.setUpdatedBy(ownerId);
        petRepository.save(pet);
        log.info("Successfully uploaded pet avatar: {} for pet: {}", photoUrl, petId);
        return photoUrl;
    }

    private boolean isAuthorizedSitter(UUID ownerId, UUID sitterId) {
        // 查詢該 owner 與 sitter 之間是否有未結案的訂單
        List<Order> activeOrders = orderRepository.findByOwnerIdAndStatusNotIn(
                ownerId, List.of("COMPLETED", "CANCELLED")
        );
        return activeOrders.stream().anyMatch(order -> order.getSitter().getId().equals(sitterId));
    }

    private PetDto toDto(Pet pet) {
        return PetDto.builder()
                .id(pet.getId())
                .ownerId(pet.getOwnerId())
                .name(pet.getName())
                .species(pet.getSpecies())
                .gender(pet.getGender())
                .neutered(pet.getNeutered())
                .weight(pet.getWeight())
                .birthYear(pet.getBirthYear())
                .photoUrl(pet.getPhotoUrl())
                .medicalPersonalityNotes(pet.getMedicalPersonalityNotes())
                .environmentalNotes(pet.getEnvironmentalNotes())
                .version(pet.getVersion())
                .build();
    }
}
