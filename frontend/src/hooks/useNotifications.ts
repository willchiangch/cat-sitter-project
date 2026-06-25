import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getNotifications,
  getUnreadCount,
  markAsRead,
  markAllAsRead,
  getPreferences,
  updatePreference
} from '../api/notificationApi';
import type { NotificationListResponse, PreferenceDto } from '../api/notificationApi';

// 查詢通知清單 hook
export const useNotificationsQuery = (page = 0, size = 20, isRead?: boolean, role?: string) => {
  return useQuery<NotificationListResponse, Error>({
    queryKey: ['notifications', page, size, isRead, role],
    queryFn: () => getNotifications(page, size, isRead, role),
    staleTime: 30000, // 30秒快取
    refetchOnWindowFocus: true
  });
};

// 查詢未讀數 hook (小鈴鐺 Polling 核心)
export const useUnreadCountQuery = (role?: string) => {
  return useQuery<number, Error>({
    queryKey: ['unread-count', role],
    queryFn: () => getUnreadCount(role),
    staleTime: 30000, // 30秒內視為新鮮，不重複拉取
    refetchOnWindowFocus: true, // 視窗聚焦時自動獲取最新
    refetchInterval: 60000, // 背景每 60 秒 Polling 一次
    refetchIntervalInBackground: false // 當 App 縮到背景時停止 polling 節省資源與電力
  });
};

// 標示單則已讀 mutation
export const useMarkAsReadMutation = (role?: string) => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => markAsRead(id),
    onSuccess: () => {
      // 標示已讀成功後，使通知清單與未讀數 Cache 失效，強制重新整理
      queryClient.invalidateQueries({ queryKey: ['notifications'] });
      queryClient.invalidateQueries({ queryKey: ['unread-count', role] });
    }
  });
};

// 一鍵全部標示已讀 mutation
export const useMarkAllAsReadMutation = (role?: string) => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => markAllAsRead(role),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['notifications'] });
      queryClient.invalidateQueries({ queryKey: ['unread-count', role] });
    }
  });
};

// 查詢偏好設定 hook
export const usePreferencesQuery = () => {
  return useQuery<PreferenceDto[], Error>({
    queryKey: ['notification-preferences'],
    queryFn: getPreferences,
    staleTime: 5 * 60 * 1000 // 偏好設定變動頻率極低，快取 5 分鐘
  });
};

// 更新偏好設定 mutation
export const useUpdatePreferenceMutation = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      category,
      enableInApp,
      enableEmail
    }: {
      category: string;
      enableInApp: boolean;
      enableEmail: boolean;
    }) => updatePreference(category, enableInApp, enableEmail),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['notification-preferences'] });
    }
  });
};
