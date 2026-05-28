import axiosClient from './axiosClient';
import type { Pet, PetEditLog } from '../types/pet';

export const getPets = async (): Promise<Pet[]> => {
  const response = await axiosClient.get('/pets');
  return response.data.data;
};

export const getPetById = async (petId: string): Promise<Pet> => {
  const response = await axiosClient.get(`/pets/${petId}`);
  return response.data.data;
};

export const createPet = async (pet: Pet): Promise<Pet> => {
  const response = await axiosClient.post('/pets', pet);
  return response.data.data;
};

export const updatePet = async (petId: string, pet: Pet): Promise<Pet> => {
  const response = await axiosClient.put(`/pets/${petId}`, pet);
  return response.data.data;
};

export const deletePet = async (petId: string): Promise<void> => {
  await axiosClient.delete(`/pets/${petId}`);
};

export const updatePetNotes = async (
  petId: string,
  dto: { medicalPersonalityNotes: string; environmentalNotes: string; version: number }
): Promise<Pet> => {
  const response = await axiosClient.put(`/pets/${petId}/notes`, dto);
  return response.data.data;
};

export const getPetEditLogs = async (petId: string): Promise<PetEditLog[]> => {
  const response = await axiosClient.get(`/pets/${petId}/edit-logs`);
  return response.data.data;
};

export const uploadPetAvatar = async (petId: string, file: File): Promise<string> => {
  const formData = new FormData();
  formData.append('file', file);
  const response = await axiosClient.post(`/pets/${petId}/avatar`, formData, {
    headers: {
      'Content-Type': 'multipart/form-data'
    }
  });
  return response.data.data.photoUrl;
};
