import axiosClient from './axiosClient';
import type { ServicePlan, ServicePlanSortRequest } from '../types/servicePlan';

export const getSitterPlans = async (): Promise<ServicePlan[]> => {
  const response = await axiosClient.get('/sitter/plans');
  return response.data.data;
};

export const createSitterPlan = async (plan: ServicePlan): Promise<ServicePlan> => {
  const response = await axiosClient.post('/sitter/plans', plan);
  return response.data.data;
};

export const updateSitterPlan = async (planId: string, plan: ServicePlan): Promise<ServicePlan> => {
  const response = await axiosClient.put(`/sitter/plans/${planId}`, plan);
  return response.data.data;
};

export const deleteSitterPlan = async (planId: string): Promise<void> => {
  await axiosClient.delete(`/sitter/plans/${planId}`);
};

export const setSitterPlanActive = async (planId: string, isActive: boolean): Promise<ServicePlan> => {
  const response = await axiosClient.patch(`/sitter/plans/${planId}/active`, { isActive });
  return response.data.data;
};

export const sortSitterPlans = async (request: ServicePlanSortRequest): Promise<void> => {
  await axiosClient.post('/sitter/plans/sort', request);
};

export const getActivePlansForOwner = async (sitterId: string): Promise<ServicePlan[]> => {
  const response = await axiosClient.get(`/sitters/${sitterId}/plans`);
  return response.data.data;
};
