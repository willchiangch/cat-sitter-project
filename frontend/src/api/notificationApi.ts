import axiosClient from './axiosClient';

export interface NotificationDto {
  id: string;
  title: string;
  content: string;
  category: 'ORDER_AFFAIR' | 'ACCOUNT_AUTH' | 'SUBSCRIPTION_MAINTENANCE' | 'SERVICE_RECORD';
  isRead: boolean;
  createdAt: string;
  linkUrl: string;
  roleTarget: 'SITTER' | 'OWNER' | 'ALL';
  /** 通知類型代碼（SD-ORDER / SD-FULFILL §7.2，SD-GLOBAL-SPEC §4.4）；舊通知為 null → 不輸出 */
  type?: string;
}

export interface NotificationListResponse {
  content: NotificationDto[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface PreferenceDto {
  category: string;
  enableInApp: boolean;
  enableEmail: boolean;
}

export const getNotifications = async (
  page = 0,
  size = 20,
  isRead?: boolean,
  role?: string
): Promise<NotificationListResponse> => {
  const params: Record<string, string | number | boolean> = { page, size };
  if (isRead !== undefined) params.isRead = isRead;
  if (role !== undefined) params.role = role;

  const response = await axiosClient.get('/notifications', { params });
  return response.data.data;
};

export const getUnreadCount = async (role?: string): Promise<number> => {
  const params: Record<string, string> = {};
  if (role !== undefined) params.role = role;

  const response = await axiosClient.get('/notifications/unread-count', { params });
  return response.data.data;
};

export const markAsRead = async (id: string): Promise<void> => {
  await axiosClient.post(`/notifications/${id}/read`);
};

export const markAllAsRead = async (role?: string): Promise<void> => {
  const params: Record<string, string> = {};
  if (role !== undefined) params.role = role;
  await axiosClient.post('/notifications/read-all', null, { params });
};

export const getPreferences = async (): Promise<PreferenceDto[]> => {
  const response = await axiosClient.get('/notifications/preferences');
  return response.data.data;
};

export const updatePreference = async (
  category: string,
  enableInApp: boolean,
  enableEmail: boolean
): Promise<void> => {
  await axiosClient.put('/notifications/preferences', {
    category,
    enableInApp,
    enableEmail
  });
};
