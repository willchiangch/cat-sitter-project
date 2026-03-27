package com.catsitter.api.service;

import com.catsitter.api.dto.client.ClientProfileResponse;
import com.catsitter.api.dto.client.CreatePetRequest;
import com.catsitter.api.dto.client.PetResponse;
import com.catsitter.api.dto.client.UpdateClientProfileRequest;
import com.catsitter.api.entity.Account;
import com.catsitter.api.entity.Pet;
import com.catsitter.api.entity.Profile;
import com.catsitter.api.entity.enums.RoleType;
import com.catsitter.api.repository.PetRepository;
import com.catsitter.api.repository.ProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ClientPetService {

    private final PetRepository petRepository;
    private final ProfileRepository profileRepository;

    public ClientPetService(PetRepository petRepository, ProfileRepository profileRepository) {
        this.petRepository = petRepository;
        this.profileRepository = profileRepository;
    }

    @Transactional(readOnly = true)
    public ClientProfileResponse getClientProfile(Account account) {
        Profile profile = getProfile(account);
        return mapToProfileResponse(profile);
    }

    @Transactional
    public ClientProfileResponse updateClientProfile(Account account, UpdateClientProfileRequest request) {
        Profile profile = getProfile(account);
        profile.setName(request.name());
        profile.setAvatarUrl(request.avatarUrl());
        profile.setPhone(request.phone());
        profile.setAddress(request.address());
        return mapToProfileResponse(profileRepository.save(profile));
    }

    @Transactional(readOnly = true)
    public List<PetResponse> getClientPets(Account account) {
        Profile profile = getProfile(account);
        return petRepository.findByClientProfileId(profile.getId())
                .stream()
                .map(this::mapToPetResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public PetResponse createPet(Account account, CreatePetRequest request) {
        Profile profile = getProfile(account);
        
        Pet pet = new Pet();
        pet.setClientProfile(profile);
        pet.setName(request.name());
        pet.setSpecies(request.species());
        pet.setGender(request.gender());
        pet.setIsNeutered(request.isNeutered());
        pet.setWeightKg(request.weightKg());
        pet.setAvatarUrl(request.avatarUrl());
        pet.setMedicalNotes(request.medicalNotes());
        pet.setDietaryNotes(request.dietaryNotes());
        pet.setPersonalityNotes(request.personalityNotes());
        pet.setOtherNotes(request.otherNotes());

        return mapToPetResponse(petRepository.save(pet));
    }

    @Transactional(readOnly = true)
    public PetResponse getPet(Account account, UUID petId) {
        Profile profile = getProfile(account);
        Pet pet = petRepository.findById(petId)
                .orElseThrow(() -> new RuntimeException("Pet not found"));
        
        if (!pet.getClientProfile().getId().equals(profile.getId())) {
            throw new RuntimeException("Unauthorized to access this pet");
        }
        
        return mapToPetResponse(pet);
    }

    @Transactional
    public PetResponse updatePet(Account account, UUID petId, CreatePetRequest request) {
        Profile profile = getProfile(account);
        Pet pet = petRepository.findById(petId)
                .orElseThrow(() -> new RuntimeException("Pet not found"));

        if (!pet.getClientProfile().getId().equals(profile.getId())) {
            throw new RuntimeException("Unauthorized to update this pet");
        }

        pet.setName(request.name());
        pet.setSpecies(request.species());
        pet.setGender(request.gender());
        pet.setIsNeutered(request.isNeutered());
        pet.setWeightKg(request.weightKg());
        pet.setAvatarUrl(request.avatarUrl());
        pet.setMedicalNotes(request.medicalNotes());
        pet.setDietaryNotes(request.dietaryNotes());
        pet.setPersonalityNotes(request.personalityNotes());
        pet.setOtherNotes(request.otherNotes());

        return mapToPetResponse(petRepository.save(pet));
    }

    @Transactional
    public void deletePet(Account account, UUID petId) {
        Profile profile = getProfile(account);
        Pet pet = petRepository.findById(petId)
                .orElseThrow(() -> new RuntimeException("Pet not found"));

        if (!pet.getClientProfile().getId().equals(profile.getId())) {
            throw new RuntimeException("Unauthorized to delete this pet");
        }

        petRepository.delete(pet);
    }

    private Profile getProfile(Account account) {
        return profileRepository.findByAccountIdAndRoleType(account.getId(), RoleType.CLIENT)
                .orElseThrow(() -> new RuntimeException("Client profile not found"));
    }

    private ClientProfileResponse mapToProfileResponse(Profile profile) {
        return new ClientProfileResponse(
                profile.getId(),
                profile.getName(),
                profile.getAvatarUrl(),
                profile.getPhone(),
                profile.getAddress()
        );
    }

    private PetResponse mapToPetResponse(Pet pet) {
        return new PetResponse(
                pet.getId(),
                pet.getName(),
                pet.getSpecies(),
                pet.getGender(),
                pet.getIsNeutered(),
                pet.getWeightKg(),
                pet.getAvatarUrl(),
                pet.getMedicalNotes(),
                pet.getDietaryNotes(),
                pet.getPersonalityNotes(),
                pet.getOtherNotes()
        );
    }
}
