import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getCareLog, getOwnerToday, getSitterDashboard, getVisit } from '../api/fulfillApi';
import { orderKeys } from './useOrders';

/** 履約 React Query hooks（SD-FULFILL §9）；key 以 ['order', orderId, ...] 為前綴，與訂單共用失效範圍 */
export const visitKeys = {
  detail: (orderId: string, visitId: string) => ['order', orderId, 'visit', visitId] as const,
  careLog: (orderId: string, visitId: string) =>
    ['order', orderId, 'visit', visitId, 'care-log'] as const,
  sitterDashboard: ['visits', 'sitter-dashboard'] as const,
  ownerToday: ['visits', 'owner-today'] as const
};

export const useVisitQuery = (orderId: string, visitId: string) =>
  useQuery({
    queryKey: visitKeys.detail(orderId, visitId),
    queryFn: () => getVisit(orderId, visitId),
    staleTime: 0
  });

export const useCareLogQuery = (orderId: string, visitId: string, enabled = true) =>
  useQuery({
    queryKey: visitKeys.careLog(orderId, visitId),
    queryFn: () => getCareLog(orderId, visitId),
    enabled,
    staleTime: 0
  });

export const useSitterDashboardQuery = () =>
  useQuery({ queryKey: visitKeys.sitterDashboard, queryFn: getSitterDashboard, staleTime: 0 });

export const useOwnerTodayQuery = () =>
  useQuery({ queryKey: visitKeys.ownerToday, queryFn: getOwnerToday, staleTime: 0 });

/** 行程寫入後：該行程、訂單詳情 / 行程列表、行程頁一起重抓 */
export const useInvalidateVisit = () => {
  const qc = useQueryClient();
  return (orderId: string, visitId: string) =>
    Promise.all([
      qc.invalidateQueries({ queryKey: visitKeys.detail(orderId, visitId), exact: true }),
      qc.invalidateQueries({ queryKey: orderKeys.detail(orderId), exact: true }),
      qc.invalidateQueries({ queryKey: orderKeys.visits(orderId) }),
      qc.invalidateQueries({ queryKey: ['visits'] }),
      qc.invalidateQueries({ queryKey: orderKeys.lists })
    ]);
};
