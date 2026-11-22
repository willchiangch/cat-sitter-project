import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query';
import type { QueryClient } from '@tanstack/react-query';
import {
  getAdminOrder,
  getBookingContext,
  getEvaluation,
  getModification,
  getOrderDetail,
  getSitterLedger,
  listAdminTasks,
  listModifications,
  listOwnerOrders,
  listPayments,
  listSitterOrders,
  listSubmissions
} from '../api/orderApi';
import type { OrderListParams } from '../api/orderApi';

/**
 * 訂單 React Query hooks（SD-ORDER §9）。queryKey 一律以 ['order', orderId, ...] 為前綴，
 * 寫入成功後以 invalidateOrder() 讓該訂單所有子查詢與列表一起重抓（不整頁 reload）。
 */
export const orderKeys = {
  detail: (orderId: string) => ['order', orderId] as const,
  evaluation: (orderId: string) => ['order', orderId, 'evaluation'] as const,
  payments: (orderId: string) => ['order', orderId, 'payments'] as const,
  submissions: (orderId: string, paymentId: string) =>
    ['order', orderId, 'payments', paymentId, 'submissions'] as const,
  modifications: (orderId: string) => ['order', orderId, 'modifications'] as const,
  modification: (orderId: string, modificationId: string) =>
    ['order', orderId, 'modification', modificationId] as const,
  visits: (orderId: string) => ['order', orderId, 'visits'] as const,
  admin: (orderId: string) => ['order', orderId, 'admin'] as const,
  lists: ['orders'] as const
};

export const invalidateOrder = (qc: QueryClient, orderId: string) =>
  Promise.all([
    qc.invalidateQueries({ queryKey: ['order', orderId] }),
    qc.invalidateQueries({ queryKey: orderKeys.lists }),
    qc.invalidateQueries({ queryKey: ['visits'] })
  ]);

export const useInvalidateOrder = () => {
  const qc = useQueryClient();
  return (orderId: string) => invalidateOrder(qc, orderId);
};

export const useBookingContextQuery = (sitterId: string | undefined) =>
  useQuery({
    queryKey: ['booking-context', sitterId],
    queryFn: () => getBookingContext(sitterId!),
    enabled: !!sitterId,
    staleTime: 0
  });

export const useOrderListQuery = (
  role: 'owner' | 'sitter',
  params: Omit<OrderListParams, 'page' | 'size'>
) =>
  useInfiniteQuery({
    queryKey: [...orderKeys.lists, role, params],
    queryFn: ({ pageParam }) =>
      (role === 'owner' ? listOwnerOrders : listSitterOrders)({
        ...params,
        page: pageParam,
        size: 20
      }),
    initialPageParam: 0,
    getNextPageParam: (last) =>
      (last.page + 1) * last.size < last.totalElements ? last.page + 1 : undefined,
    staleTime: 30 * 1000
  });

export const useOrderDetailQuery = (orderId: string) =>
  useQuery({
    queryKey: orderKeys.detail(orderId),
    queryFn: () => getOrderDetail(orderId),
    staleTime: 0
  });

export const useEvaluationQuery = (orderId: string, enabled: boolean) =>
  useQuery({
    queryKey: orderKeys.evaluation(orderId),
    queryFn: () => getEvaluation(orderId),
    enabled,
    staleTime: 0
  });

export const usePaymentsQuery = (orderId: string, enabled = true) =>
  useQuery({
    queryKey: orderKeys.payments(orderId),
    queryFn: () => listPayments(orderId),
    enabled,
    staleTime: 0
  });

export const useSubmissionsQuery = (orderId: string, paymentId: string, enabled = true) =>
  useQuery({
    queryKey: orderKeys.submissions(orderId, paymentId),
    queryFn: () => listSubmissions(orderId, paymentId),
    enabled,
    staleTime: 0
  });

export const useModificationsQuery = (orderId: string, enabled = true) =>
  useQuery({
    queryKey: orderKeys.modifications(orderId),
    queryFn: () => listModifications(orderId),
    enabled,
    staleTime: 0
  });

export const useModificationQuery = (orderId: string, modificationId: string) =>
  useQuery({
    queryKey: orderKeys.modification(orderId, modificationId),
    queryFn: () => getModification(orderId, modificationId),
    staleTime: 0
  });

export const useSitterLedgerQuery = (month: string) =>
  useQuery({
    queryKey: [...orderKeys.lists, 'ledger', month],
    queryFn: () => getSitterLedger(month),
    staleTime: 30 * 1000
  });

export const useAdminOrderQuery = (orderId: string) =>
  useQuery({
    queryKey: orderKeys.admin(orderId),
    queryFn: () => getAdminOrder(orderId),
    staleTime: 0
  });

export const useAdminTasksQuery = (status: string, page: number) =>
  useQuery({
    queryKey: ['admin-tasks', status, page],
    queryFn: () => listAdminTasks({ status, page, size: 20 }),
    staleTime: 0
  });
