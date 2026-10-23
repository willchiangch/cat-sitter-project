/**
 * 狀態碼 → 中文顯示文字的唯一對照表 (SD-FRONTEND-SPEC §狀態顯示，Backlog 第 3 批 U5)。
 *
 * 過去各頁自己 switch，`StatusBadge` 只認 6 種訂單狀態，其他（IN_PROGRESS、MODIFYING…）直接把英文狀態碼
 * 印在徽章上；行程清單、評估頁的「非待評估」提示也直接印 `visit.status`/`order.status`。
 * 畫面上任何狀態文字一律從這裡取，新增狀態碼時只改這一個檔案。查無對照時顯示「未知狀態」而不是英文碼。
 */
const UNKNOWN = '未知狀態';

/** 訂單 orders.status（後端 Order 狀態機，SD-ERD） */
export const ORDER_STATUS_LABELS: Record<string, string> = {
  PENDING: '待評估',
  PENDING_PAYMENT: '待付款',
  PAID: '已付款',
  CONFIRMED: '已確認',
  IN_PROGRESS: '服務進行中',
  MODIFYING: '變更協商中',
  REFUND_VERIFY: '待確認退款',
  COMPLETED: '已結案',
  CANCELLED: '已取消',
  DISPUTED: '爭議處理中'
};

/** 行程 visits.status */
export const VISIT_STATUS_LABELS: Record<string, string> = {
  PENDING: '待執行',
  CONFIRMED: '待執行',
  IN_PROGRESS: '執行中',
  DONE: '已完工',
  CLOSED_BY_SYSTEM: '系統結案',
  CANCELLED: '已取消'
};

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

const label = (map: Record<string, string>, status: string | null | undefined) =>
  (status && map[status]) || UNKNOWN;

export const orderStatusLabel = (s: string | null | undefined) => label(ORDER_STATUS_LABELS, s);
export const visitStatusLabel = (s: string | null | undefined) => label(VISIT_STATUS_LABELS, s);
export const profileKycStatusLabel = (s: string | null | undefined) =>
  label(PROFILE_KYC_STATUS_LABELS, s);
export const kycRecordStatusLabel = (s: string | null | undefined) =>
  label(KYC_RECORD_STATUS_LABELS, s);
