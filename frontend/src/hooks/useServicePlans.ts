import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getSitterPlans,
  createSitterPlan,
  updateSitterPlan,
  deleteSitterPlan,
  sortSitterPlans,
  getActivePlansForOwner
} from '../api/servicePlanApi';
import type { ServicePlan, ServicePlanSortRequest } from '../types/servicePlan';

export const useSitterPlansQuery = () => {
  return useQuery<ServicePlan[], Error>({
    queryKey: ['sitter-plans'],
    queryFn: getSitterPlans,
    staleTime: 5 * 60 * 1000
  });
};

export const useSitterActivePlansQuery = (sitterId: string) => {
  return useQuery<ServicePlan[], Error>({
    queryKey: ['sitter-active-plans', sitterId],
    queryFn: () => getActivePlansForOwner(sitterId),
    staleTime: 5 * 60 * 1000,
    enabled: !!sitterId
  });
};

export const useCreatePlanMutation = () => {
  const queryClient = useQueryClient();
  return useMutation<ServicePlan, Error, ServicePlan>({
    mutationFn: createSitterPlan,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['sitter-plans'] });
    }
  });
};

export const useUpdatePlanMutation = () => {
  const queryClient = useQueryClient();
  return useMutation<ServicePlan, Error, { planId: string; plan: ServicePlan }>({
    mutationFn: ({ planId, plan }) => updateSitterPlan(planId, plan),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['sitter-plans'] });
      if (data.sitterId) {
        queryClient.invalidateQueries({ queryKey: ['sitter-active-plans', data.sitterId] });
      }
    }
  });
};

export const useDeletePlanMutation = () => {
  const queryClient = useQueryClient();
  return useMutation<void, Error, string>({
    mutationFn: deleteSitterPlan,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['sitter-plans'] });
      queryClient.invalidateQueries({ queryKey: ['sitter-active-plans'] });
    }
  });
};

export const useSortPlansMutation = () => {
  const queryClient = useQueryClient();
  return useMutation<void, Error, ServicePlanSortRequest>({
    mutationFn: sortSitterPlans,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['sitter-plans'] });
      queryClient.invalidateQueries({ queryKey: ['sitter-active-plans'] });
    }
  });
};
