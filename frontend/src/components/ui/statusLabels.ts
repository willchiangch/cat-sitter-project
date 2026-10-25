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

/**
 * 行程清單（訂單詳情 / 保母訂單卡）右側入口按鈕的文案，回 null 代表不顯示入口（驗收 N11）。
 * 過去不分狀態一律顯示「查看日誌」，飼主點「待執行」行程只會看到空日誌。
 * - 保母：待執行 → 打卡；執行中 → 繼續回報 / Check-out；已完工 → 查看或送出日誌（24h 內可補送）；
 *   系統結案（沒打卡、沒有日誌）、已取消 → 不顯示
 * - 飼主：只有已完工的行程可能有已送出的日誌（SD-022 SUBMITTED-only Gate），其餘不顯示
 */
export const sitterVisitActionLabel = (s: string | null | undefined): string | null => {
  switch (s) {
    case 'PENDING':
    case 'CONFIRMED':
      return '打卡 / 回報';
    case 'IN_PROGRESS':
      return '繼續回報';
    case 'DONE':
      return '查看 / 送出日誌';
    default:
      return null;
  }
};

/**
 * 飼主端行程入口（fix-batch12 U5）：只有日誌已正式送出才給「查看日誌」。過去 DONE 一律給，已完工但
 * 保母還沒送出、或逾期被系統關閉的行程點進去只會是「尚無照護回報」空頁（驗收 N11 殘留）。
 */
export const ownerVisitActionLabel = (reportSubmitted: boolean | undefined): string | null =>
  reportSubmitted ? '查看日誌' : null;

/** 飼主端行程已結束但日誌沒有送出時，取代入口按鈕的狀態文字 */
export const ownerVisitReportPendingLabel = (
  status: string | null | undefined,
  reportSubmitted: boolean | undefined
): string | null => {
  if (reportSubmitted) return null;
  if (status === 'DONE') return '日誌尚未送出';
  if (status === 'CLOSED_BY_SYSTEM') return '未送出日誌';
  return null;
};
