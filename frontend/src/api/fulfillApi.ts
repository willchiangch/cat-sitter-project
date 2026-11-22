import axiosClient from './axiosClient';
import { postIdempotent, sendWithBusyRetry } from './idempotency';

/**
 * 服務履約 API（SD-FULFILL §6.3，F1–F19）。舊 `/api/visits/{visitId}/**` 已刪除，一律以
 * `/api/orders/{orderId}/visits/{visitId}/...` 呼叫。型別對照 backend application/dto/fulfill。
 */

export interface ChecklistItem {
  itemId: string;
  title: string;
  sortOrder: number;
  isCompleted: boolean;
  checkedAt?: string;
  createdSource?: string;
}

export interface VisitCareLogSummary {
  /** OWNER / ADMIN */
  submitted?: boolean;
  /** SITTER：DRAFT / SUBMITTED / 不輸出 */
  status?: string;
  deadlineAt?: string;
  /** SITTER */
  expired?: boolean;
}

export interface VisitRow {
  visitId: string;
  orderItemId: string;
  planId: string;
  serviceDate: string;
  sequenceInDay: number;
  slotsInDay: number;
  name: string;
  customName?: string;
  status: string;
  startedAt?: string;
  completedAt?: string;
  completionSource?: 'SITTER' | 'SYSTEM';
  closedAt?: string;
  cancelledAt?: string;
  zombieAt?: string;
  careLog?: VisitCareLogSummary;
  checklist?: { total: number; completed: number; items: ChecklistItem[] };
}

export interface VisitList {
  orderId: string;
  orderStatus: string;
  visits: VisitRow[];
}

export type VisitAllowedAction =
  | 'START'
  | 'COMPLETE'
  | 'RENAME'
  | 'EDIT_CHECKLIST'
  | 'CHECK_ITEM'
  | 'EDIT_CARE_LOG'
  | 'SUBMIT_CARE_LOG';

export interface VisitDetail extends VisitRow {
  orderId: string;
  orderStatus: string;
  owner?: { userId: string; displayName: string };
  pets?: { petId: string; name: string }[];
  allowedActions: VisitAllowedAction[];
  dateReached: boolean;
  zombieDeadlinePassed: boolean;
}

export interface DashboardCard {
  visitId: string;
  orderId: string;
  serviceDate: string;
  name: string;
  status: string;
  completionSource?: string;
  zombieAt?: string;
  zombieDeadlinePassed: boolean;
  counterpart?: { userId: string; displayName: string };
  pets?: { petId: string; name: string }[];
}

export interface CareLogQuota {
  planTier: string;
  imageLimit: number;
  imageUsed: number;
  videoLimit: number;
  videoUsed: number;
}

export interface MediaItem {
  mediaId: string;
  mediaType: 'IMAGE' | 'VIDEO';
  caption?: string;
  sizeBytes: number;
  durationSeconds?: number;
  uploadedAt: string;
  url?: string;
  urlExpiresAt?: string;
  purged: boolean;
}

export interface CareLogView {
  visitId: string;
  visitName: string;
  serviceDate: string;
  careLogId?: string;
  status?: string;
  text?: string;
  submittedAt?: string;
  deadlineAt?: string;
  expired?: boolean;
  editable?: boolean;
  submittable?: boolean;
  quota?: CareLogQuota;
  media: MediaItem[];
}

export interface MediaUploadRequest {
  file: {
    mediaType: 'IMAGE' | 'VIDEO';
    contentType: string;
    sizeBytes: number;
    sha256: string;
    durationSeconds?: number;
  };
  caption?: string;
}

export interface UploadSlot {
  uploadId: string;
  receivedAt: string;
  expiresAt: string;
}

export interface UploadTarget {
  uploadId: string;
  alreadyCompleted: boolean;
  uploadUrl?: string;
  method?: 'PUT';
  headers?: Record<string, string>;
  urlExpiresAt?: string;
}

export interface MediaCreated {
  mediaId: string;
  mediaType: 'IMAGE' | 'VIDEO';
  caption?: string;
  sizeBytes: number;
  durationSeconds?: number;
  uploadedAt: string;
  careLogId: string;
  quota: CareLogQuota;
}

const v = (orderId: string, visitId: string) =>
  `/orders/${encodeURIComponent(orderId)}/visits/${encodeURIComponent(visitId)}`;

// 行程
export const listVisits = async (orderId: string): Promise<VisitList> =>
  (await axiosClient.get(`/orders/${encodeURIComponent(orderId)}/visits`)).data;

export const getVisit = async (orderId: string, visitId: string): Promise<VisitDetail> =>
  (await axiosClient.get(v(orderId, visitId))).data;

export const startVisit = (orderId: string, visitId: string, key: string) =>
  postIdempotent<{ visitId: string; status: string; startedAt: string; orderStatus: string }>(
    `${v(orderId, visitId)}/start`,
    key
  );

export const completeVisit = (orderId: string, visitId: string, key: string) =>
  postIdempotent<{
    visitId: string;
    status: string;
    completedAt: string;
    completionSource: string;
    careLogDeadlineAt?: string;
  }>(`${v(orderId, visitId)}/complete`, key);

export const renameVisit = (orderId: string, visitId: string, name: string) =>
  sendWithBusyRetry<{ visitId: string; name: string; customName?: string }>(
    'put',
    `${v(orderId, visitId)}/name`,
    { name }
  );

export const getSitterDashboard = async (): Promise<{
  today: DashboardCard[];
  overdue: DashboardCard[];
}> => (await axiosClient.get('/visits/sitter/dashboard')).data;

export const getOwnerToday = async (): Promise<{ today: DashboardCard[] }> =>
  (await axiosClient.get('/visits/owner/today')).data;

// 檢核項
export const getChecklist = async (
  orderId: string,
  visitId: string
): Promise<{ visitId: string; items: ChecklistItem[] }> =>
  (await axiosClient.get(`${v(orderId, visitId)}/checklist`)).data;

export const addChecklistItem = (orderId: string, visitId: string, title: string, key: string) =>
  postIdempotent<{ itemId: string; title: string; sortOrder: number; isCompleted: boolean }>(
    `${v(orderId, visitId)}/checklist/items`,
    key,
    { title }
  );

export const deleteChecklistItem = (orderId: string, visitId: string, itemId: string) =>
  sendWithBusyRetry<{ visitId: string; items: ChecklistItem[] }>(
    'delete',
    `${v(orderId, visitId)}/checklist/items/${encodeURIComponent(itemId)}`
  );

export const reorderChecklist = (orderId: string, visitId: string, itemIds: string[]) =>
  sendWithBusyRetry<{ visitId: string; items: ChecklistItem[] }>(
    'put',
    `${v(orderId, visitId)}/checklist/order`,
    { itemIds }
  );

export const checkChecklistItem = (
  orderId: string,
  visitId: string,
  itemId: string,
  checked: boolean
) =>
  sendWithBusyRetry<{ itemId: string; isCompleted: boolean; checkedAt?: string }>(
    'put',
    `${v(orderId, visitId)}/checklist/items/${encodeURIComponent(itemId)}/check`,
    { checked }
  );

// 照護日誌
export const getCareLog = async (orderId: string, visitId: string): Promise<CareLogView> =>
  (await axiosClient.get(`${v(orderId, visitId)}/care-log`)).data;

export const saveCareLogDraft = (orderId: string, visitId: string, text: string) =>
  sendWithBusyRetry<{ careLogId: string; status: string; text: string; updatedAt: string }>(
    'put',
    `${v(orderId, visitId)}/care-log/draft`,
    { text }
  );

export const submitCareLog = (orderId: string, visitId: string, text: string, key: string) =>
  postIdempotent<{ careLogId: string; status: string; submittedAt: string }>(
    `${v(orderId, visitId)}/care-log/submit`,
    key,
    { text }
  );

export const requestMediaUpload = (
  orderId: string,
  visitId: string,
  body: MediaUploadRequest,
  key: string
) => postIdempotent<UploadSlot>(`${v(orderId, visitId)}/care-log/media-uploads`, key, body);

export const getUploadTarget = async (
  orderId: string,
  visitId: string,
  uploadId: string
): Promise<UploadTarget> =>
  (
    await axiosClient.get(
      `${v(orderId, visitId)}/care-log/media-uploads/${encodeURIComponent(uploadId)}/target`
    )
  ).data;

export const completeMediaUpload = async (
  orderId: string,
  visitId: string,
  uploadId: string
): Promise<MediaCreated> =>
  (
    await axiosClient.post(
      `${v(orderId, visitId)}/care-log/media-uploads/${encodeURIComponent(uploadId)}/complete`
    )
  ).data;

export const deleteCareLogMedia = (orderId: string, visitId: string, mediaId: string) =>
  sendWithBusyRetry<{ mediaId: string; quota: CareLogQuota }>(
    'delete',
    `${v(orderId, visitId)}/care-log/media/${encodeURIComponent(mediaId)}`
  );
