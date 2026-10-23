import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getMyOrdersAsOwner,
  getMyOrdersAsSitter,
  getOrderVisits,
  getSitterLedger,
  verifyPayment,
  rejectPayment,
  getPaymentProofSignedUrl
} from '../api/orderApi';
import type {
  OrderSummaryDto,
  PaymentProofSignedUrl,
  SitterLedgerResponse,
  VisitSummaryDto
} from '../api/orderApi';

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

// 付款憑證簽名網址 (SD-007 §4.7)。簽名 10 分鐘過期且補款時憑證會換一張，不保留快取：
// 每次掛載都重拿，避免先閃出上一張憑證或拿到過期網址
export const usePaymentProofSignedUrlQuery = (orderId: string, enabled: boolean) => {
  return useQuery<PaymentProofSignedUrl, Error>({
    queryKey: ['orders', orderId, 'payment-proof-signed-url'],
    queryFn: () => getPaymentProofSignedUrl(orderId),
    enabled,
    staleTime: 0,
    gcTime: 0,
    refetchOnWindowFocus: false
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
  return useMutation<
    { status: string; message: string },
    Error,
    { orderId: string; rejectReason: string }
  >({
    mutationFn: ({ orderId, rejectReason }) => rejectPayment(orderId, rejectReason),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['orders', 'sitter'] });
    }
  });
};
