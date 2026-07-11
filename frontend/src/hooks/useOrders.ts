import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getMyOrdersAsOwner,
  getMyOrdersAsSitter,
  verifyPayment,
  rejectPayment,
  confirmOrder
} from '../api/orderApi';
import type { OrderSummaryDto } from '../api/orderApi';

export const useOwnerOrdersQuery = () => {
  return useQuery<OrderSummaryDto[], Error>({
    queryKey: ['orders', 'owner'],
    queryFn: getMyOrdersAsOwner,
    staleTime: 60 * 1000
  });
};

export const useSitterOrdersQuery = () => {
  return useQuery<OrderSummaryDto[], Error>({
    queryKey: ['orders', 'sitter'],
    queryFn: getMyOrdersAsSitter,
    staleTime: 60 * 1000
  });
};

export const useVerifyPaymentMutation = () => {
  const queryClient = useQueryClient();
  return useMutation<{ status: string; message: string }, Error, string>({
    mutationFn: verifyPayment,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['orders', 'sitter'] });
    }
  });
};

export const useConfirmOrderMutation = () => {
  const queryClient = useQueryClient();
  return useMutation<{ status: string; message: string }, Error, { orderId: string; sitterId: string }>({
    mutationFn: ({ orderId, sitterId }) => confirmOrder(orderId, sitterId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['orders', 'sitter'] });
    }
  });
};

export const useRejectPaymentMutation = () => {
  const queryClient = useQueryClient();
  return useMutation<{ status: string; message: string }, Error, { orderId: string; rejectReason: string }>({
    mutationFn: ({ orderId, rejectReason }) => rejectPayment(orderId, rejectReason),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['orders', 'sitter'] });
    }
  });
};
