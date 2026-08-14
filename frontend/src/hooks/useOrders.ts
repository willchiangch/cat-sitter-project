import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getMyOrdersAsOwner,
  getMyOrdersAsSitter,
  getOrderVisits,
  getSitterLedger,
  verifyPayment,
  rejectPayment
} from '../api/orderApi';
import type { OrderSummaryDto, SitterLedgerResponse, VisitSummaryDto } from '../api/orderApi';

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

// 訂單底下的行程清單，供 CONFIRMED/IN_PROGRESS 訂單串接打卡/日誌回報頁面 (SD-008)
export const useOrderVisitsQuery = (orderId: string, enabled: boolean) => {
  return useQuery<VisitSummaryDto[], Error>({
    queryKey: ['orders', orderId, 'visits'],
    queryFn: () => getOrderVisits(orderId),
    enabled,
    staleTime: 30 * 1000
  });
};

export const useSitterLedgerQuery = (month: string) => {
  return useQuery<SitterLedgerResponse, Error>({
    queryKey: ['orders', 'sitter', 'ledger', month],
    queryFn: () => getSitterLedger(month),
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

export const useRejectPaymentMutation = () => {
  const queryClient = useQueryClient();
  return useMutation<{ status: string; message: string }, Error, { orderId: string; rejectReason: string }>({
    mutationFn: ({ orderId, rejectReason }) => rejectPayment(orderId, rejectReason),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['orders', 'sitter'] });
    }
  });
};
