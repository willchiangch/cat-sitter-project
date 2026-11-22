/**
 * 通知類型（後端 `notifications.type`，SD-ORDER §7.2 / SD-FULFILL §7.2）→ 前端顯示用標籤。
 * 通知標題與內文由後端範本產生（NotificationTemplates），前端不重組文案；本表只在列表回應帶 `type` 時
 * 顯示類型標籤（目前 GET /api/notifications 尚未輸出 `type`，未輸出時不顯示，依 linkUrl 導頁）。
 *
 * SD-FULFILL 新增四種（皆 ORDER_AFFAIR、可關閉 Email）：
 * - VISIT_CLOSED_BY_SYSTEM_SITTER → `/sitter/orders/{orderId}`
 * - VISIT_CLOSED_BY_SYSTEM_OWNER → `/owner/orders/{orderId}`
 * - VISIT_AUTO_COMPLETED（保母）→ `/sitter/orders/{orderId}/visits/{visitId}`
 * - CARE_LOG_SUBMITTED（飼主）→ `/orders/{orderId}/visits/{visitId}/care-log`
 */
export const NOTIFICATION_TYPE_LABELS: Record<string, string> = {
  VISIT_CLOSED_BY_SYSTEM_SITTER: '行程已由系統關閉',
  VISIT_CLOSED_BY_SYSTEM_OWNER: '行程已由系統關閉',
  VISIT_AUTO_COMPLETED: '行程已自動結束',
  CARE_LOG_SUBMITTED: '照護日誌已回報'
};

export const notificationTypeLabel = (type: string | null | undefined): string | null =>
  (type && NOTIFICATION_TYPE_LABELS[type]) || null;
