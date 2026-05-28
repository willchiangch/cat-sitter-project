import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getPets,
  getPetById,
  createPet,
  updatePet,
  deletePet,
  updatePetNotes,
  getPetEditLogs,
  uploadPetAvatar
} from '../api/petApi';
import type { Pet, PetEditLog } from '../types/pet';

export const usePetsQuery = () => {
  return useQuery<Pet[], Error>({
    queryKey: ['pets'],
    queryFn: getPets,
    staleTime: 5 * 60 * 1000
  });
};

export const usePetQuery = (petId: string) => {
  return useQuery<Pet, Error>({
    queryKey: ['pet', petId],
    queryFn: () => getPetById(petId),
    staleTime: 5 * 60 * 1000,
    enabled: !!petId
  });
};

export const useCreatePetMutation = () => {
  const queryClient = useQueryClient();
  return useMutation<Pet, Error, Pet>({
    mutationFn: createPet,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['pets'] });
    }
  });
};

export const useUpdatePetMutation = () => {
  const queryClient = useQueryClient();
  return useMutation<Pet, Error, { petId: string; pet: Pet }>({
    mutationFn: ({ petId, pet }) => updatePet(petId, pet),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['pets'] });
    }
  });
};

export const useDeletePetMutation = () => {
  const queryClient = useQueryClient();
  return useMutation<void, Error, string>({
    mutationFn: deletePet,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['pets'] });
    }
  });
};

export const useUpdatePetNotesMutation = () => {
  const queryClient = useQueryClient();
  return useMutation<
    Pet,
    Error,
    {
      petId: string;
      dto: { medicalPersonalityNotes: string; environmentalNotes: string; version: number };
    }
  >({
    mutationFn: ({ petId, dto }) => updatePetNotes(petId, dto),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['pets'] });
      if (data.id) {
        queryClient.invalidateQueries({ queryKey: ['pet', data.id] });
        queryClient.invalidateQueries({ queryKey: ['pet-edit-logs', data.id] });
      }
    }
  });
};

export const usePetEditLogsQuery = (petId: string) => {
  return useQuery<PetEditLog[], Error>({
    queryKey: ['pet-edit-logs', petId],
    queryFn: () => getPetEditLogs(petId),
    staleTime: 0,
    enabled: !!petId
  });
};

export const useUploadAvatarMutation = () => {
  const queryClient = useQueryClient();
  return useMutation<string, Error, { petId: string; file: File }>({
    mutationFn: ({ petId, file }) => uploadPetAvatar(petId, file),
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({ queryKey: ['pets'] });
      queryClient.invalidateQueries({ queryKey: ['pet', variables.petId] });
    }
  });
};
