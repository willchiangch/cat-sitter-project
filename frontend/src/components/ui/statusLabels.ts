/**
 * 狀態碼 → 中文顯示文字的唯一對照表（SD-FRONTEND-SPEC §6.4）。
 *
 * 畫面上任何狀態文字一律從這裡取，新增狀態碼時只改這一個檔案。查無對照時顯示「未知狀態」而不是英文碼。
 * 2026-11-22：訂單只剩 baseline §3.1 七個主狀態（刪除已廢止的 PAID / MODIFYING / REFUND_VERIFY），
 * 行程移除舊值 CONFIRMED；新增付款、憑證、變更、照護日誌子流程狀態。
 */
const UNKNOWN = '未知狀態';

/** 訂單 orders.status（baseline §3.1） */
export const ORDER_STATUS_LABELS: Record<string, string> = {
  PENDING: '待評估',
  PENDING_PAYMENT: '待付款',
  CONFIRMED: '已確認',
  IN_PROGRESS: '服務進行中',
  DISPUTED: '爭議處理中',
  COMPLETED: '已結案',
  CANCELLED: '已取消'
};

/** 付款 payments.status（baseline §3.3） */
export const PAYMENT_STATUS_LABELS: Record<string, string> = {
  UNPAID: '待付款',
  SUBMITTED: '待核對',
  VERIFIED: '已確認',
  VOID: '已作廢'
};

/** 憑證 payment_submissions.status（baseline §3.3） */
export const SUBMISSION_STATUS_LABELS: Record<string, string> = {
  SUBMITTED: '待核對',
  ACCEPTED: '已確認',
  REJECTED: '已駁回'
};

/** 變更 modification_requests.status（baseline §3.4） */
export const MODIFICATION_STATUS_LABELS: Record<string, string> = {
  REQUESTED: '待保母報價',
  QUOTED: '待飼主回應',
  AWAITING_SUPPLEMENT: '待補款',
  AWAITING_REFUND: '待退款',
  SETTLED: '已生效',
  REJECTED: '已拒絕',
  WITHDRAWN: '已撤回',
  EXPIRED: '已逾期'
};

/** 照護日誌 visit_service_reports.status（PRD-022）；逾期為衍生狀態 */
export const CARE_LOG_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  SUBMITTED: '已送出'
};
export const CARE_LOG_EXPIRED_LABEL = '已逾期，未送出';

/** 行程 visits.status（baseline §6） */
export const VISIT_STATUS_LABELS: Record<string, string> = {
  PENDING: '待執行',
  IN_PROGRESS: '執行中',
  DONE: '已完工',
  CLOSED_BY_SYSTEM: '系統結案',
  CANCELLED: '已取消'
};
/** `DONE` 且完成來源 `SYSTEM` 的加註（PRD-008 BR-17、SD-FULFILL） */
export const VISIT_AUTO_COMPLETED_NOTE = '系統自動結束';

/** 保母 profiles.kyc_status */
export const PROFILE_KYC_STATUS_LABELS: Record<string, string> = {
  UNVERIFIED: '未認證',
  PENDING_REVIEW: '審核中',
  VERIFIED: '已認證',
  REJECTED: '被駁回',
  SUSPENDED: '已停權'
};

/** 單筆 KYC 申請 kyc_records.status（管理端審核） */
export const KYC_RECORD_STATUS_LABELS: Record<string, string> = {
  PENDING: '待審核',
  APPROVED: '已通過',
  REJECTED: '被駁回'
};

/** 變更類型（E17 `type`） */
export const MODIFICATION_TYPE_LABELS: Record<string, string> = {
  CHANGE: '變更行程',
  CANCEL_ALL: '整筆取消'
};

/** 付款用途（E10 `purpose`）；畫面標題用 */
export const PAYMENT_PURPOSE_LABELS: Record<string, string> = {
  INITIAL: '訂單付款',
  SUPPLEMENT: '補款',
  MODIFICATION_REFUND: '退款',
  ADMIN_ADJUSTMENT: '平台調整'
};

/** 發起方 / 操作方 */
export const PARTY_LABELS: Record<string, string> = {
  OWNER: '飼主',
  SITTER: '保母',
  ADMIN: '平台',
  SYSTEM: '系統'
};

/** 取消原因（E2 `cancellation.reasonCode`，SD-ORDER §6.3） */
export const CANCEL_REASON_LABELS: Record<string, string> = {
  OWNER_WITHDRAWN: '飼主已撤回預約',
  SITTER_DECLINED: '保母已婉拒此預約',
  REVIEW_EXPIRED: '保母未於期限內回覆，預約已自動取消',
  SITTER_CANCELLED: '保母已取消訂單',
  PAYMENT_EXPIRED: '付款逾期，訂單已取消',
  ADMIN_VOIDED: '平台已作廢付款，訂單已取消',
  MODIFICATION: '訂單已因變更取消',
  ADMIN_MEDIATED: '平台調解後取消'
};

/** ADMIN 待辦類型（E28 `type`） */
export const ADMIN_TASK_TYPE_LABELS: Record<string, string> = {
  INITIAL_PAYMENT_UNVERIFIED: '初次付款逾時未核對',
  MODIFICATION_PAYMENT_DISPUTE: '變更款項爭議',
  SERVICE_DISPUTE: '服務爭議'
};
export const ADMIN_TASK_STATUS_LABELS: Record<string, string> = {
  OPEN: '待處理',
  RESOLVED: '已處理'
};

const label = (map: Record<string, string>, status: string | null | undefined) =>
  (status && map[status]) || UNKNOWN;

export const orderStatusLabel = (s: string | null | undefined) => label(ORDER_STATUS_LABELS, s);
export const paymentStatusLabel = (s: string | null | undefined) => label(PAYMENT_STATUS_LABELS, s);
export const submissionStatusLabel = (s: string | null | undefined) =>
  label(SUBMISSION_STATUS_LABELS, s);
export const modificationStatusLabel = (s: string | null | undefined) =>
  label(MODIFICATION_STATUS_LABELS, s);
export const careLogStatusLabel = (s: string | null | undefined) =>
  label(CARE_LOG_STATUS_LABELS, s);
export const visitStatusLabel = (s: string | null | undefined) => label(VISIT_STATUS_LABELS, s);
export const profileKycStatusLabel = (s: string | null | undefined) =>
  label(PROFILE_KYC_STATUS_LABELS, s);
export const kycRecordStatusLabel = (s: string | null | undefined) =>
  label(KYC_RECORD_STATUS_LABELS, s);
export const modificationTypeLabel = (s: string | null | undefined) =>
  label(MODIFICATION_TYPE_LABELS, s);
export const paymentPurposeLabel = (s: string | null | undefined) =>
  label(PAYMENT_PURPOSE_LABELS, s);
export const partyLabel = (s: string | null | undefined) => label(PARTY_LABELS, s);
export const cancelReasonLabel = (s: string | null | undefined) => label(CANCEL_REASON_LABELS, s);
export const adminTaskTypeLabel = (s: string | null | undefined) =>
  label(ADMIN_TASK_TYPE_LABELS, s);
export const adminTaskStatusLabel = (s: string | null | undefined) =>
  label(ADMIN_TASK_STATUS_LABELS, s);

/** 行程狀態完整文字：`DONE` + `SYSTEM` 另加「系統自動結束」 */
export const visitStatusFullLabel = (
  status: string | null | undefined,
  completionSource?: string | null
): string =>
  status === 'DONE' && completionSource === 'SYSTEM'
    ? `${visitStatusLabel(status)}（${VISIT_AUTO_COMPLETED_NOTE}）`
    : visitStatusLabel(status);

/**
 * @deprecated 舊行程入口文案，SD-FULFILL 頁面改寫後移除（保母改進服務面板、飼主依 careLog.submitted）。
 */
export const sitterVisitActionLabel = (s: string | null | undefined): string | null => {
  switch (s) {
    case 'PENDING':
      return '打卡 / 回報';
    case 'IN_PROGRESS':
      return '繼續回報';
    case 'DONE':
      return '查看 / 送出日誌';
    default:
      return null;
  }
};

/** @deprecated 同上 */
export const ownerVisitActionLabel = (reportSubmitted: boolean | undefined): string | null =>
  reportSubmitted ? '查看日誌' : null;

/** @deprecated 同上 */
export const ownerVisitReportPendingLabel = (
  status: string | null | undefined,
  reportSubmitted: boolean | undefined
): string | null => {
  if (reportSubmitted) return null;
  if (status === 'DONE') return '日誌尚未送出';
  if (status === 'CLOSED_BY_SYSTEM') return '未送出日誌';
  return null;
};
