import type { ApiError } from './apiError';
import { payloadNumber, payloadString } from './apiError';
import { formatDateTime } from '../utils/datetime';
import { formatMoney } from '../utils/money';

/**
 * 錯誤碼 → 畫面文案（依各 PRD §7 例外表字面）。同一錯誤碼依操作類別（context）取不同 PRD 的字面，
 * 一律依回應 `code` 與 `payload` 決定，不讀後端 `message`（SD-ORDER §9.2、SD-FULFILL §9.3）。
 */
export type ErrorContext =
  | 'generic'
  | 'booking' // PRD-005
  | 'quote' // PRD-006
  | 'decline' // PRD-027 婉拒 / 付款前保母取消
  | 'withdraw' // PRD-026
  | 'payment' // PRD-007
  | 'modification' // PRD-016
  | 'settlement' // PRD-023
  | 'paymentDispute' // PRD-024
  | 'complete' // PRD-009 O11
  | 'serviceDispute' // PRD-009 O13
  | 'visit' // PRD-008
  | 'checklist' // PRD-028
  | 'careLog'; // PRD-022

const BUSY = '系統忙碌，請稍後再試';
const BUSY_ORDER = '訂單處理中，請稍後再試';
const REFRESH = '訂單狀態已變更，請重新整理';

const visitStateText = (err: ApiError): string => {
  const s = payloadString(err, 'currentVisitStatus');
  const source = payloadString(err, 'completionSource');
  switch (s) {
    case 'CANCELLED':
      return '此行程已取消';
    case 'CLOSED_BY_SYSTEM':
      return '此行程已由系統關閉';
    case 'DONE':
      return source === 'SYSTEM' ? '此行程已由系統自動結束' : '此行程已結束';
    case 'IN_PROGRESS':
      return '此行程已開始';
    default:
      return '行程狀態已變更，請重新整理';
  }
};

const deadlineText = (err: ApiError, ctx: ErrorContext): string => {
  const deadline = payloadString(err, 'deadline');
  const at = formatDateTime(payloadString(err, 'deadlineAt'));
  switch (deadline) {
    case 'REVIEW_EXPIRES_AT':
      return '此預約已超過評估期限';
    case 'PAYMENT_DUE_AT':
      return ctx === 'settlement' ? '補款期限已過' : '已超過付款期限';
    case 'MODIFICATION_EXPIRES_AT':
      return '此變更請求已逾期';
    case 'DISPUTE_WINDOW_ENDS_AT':
      return `已超過可回報問題的期限（${at}）`;
    case 'VISIT_ZOMBIE_AT':
      return '此行程已超過可開始期限，系統將自動關閉';
    case 'CARE_LOG_EDIT_ENDS_AT':
      return ctx === 'careLog' ? '已超過日誌送出期限' : '已超過可編輯期限';
    default:
      return '已超過期限';
  }
};

const orderStateText = (err: ApiError, ctx: ErrorContext): string => {
  const current = payloadString(err, 'currentStatus');
  switch (ctx) {
    case 'payment':
      return current === 'DISPUTED' ? '訂單爭議處理中，暫停付款操作' : REFRESH;
    case 'decline':
      return current === 'CONFIRMED' || current === 'IN_PROGRESS'
        ? '訂單已成立，如需取消請申請訂單變更'
        : REFRESH;
    case 'modification':
      return '此訂單目前無法變更';
    case 'settlement':
      return current === 'DISPUTED' ? '訂單爭議處理中' : REFRESH;
    case 'paymentDispute':
      return current === 'DISPUTED' ? '訂單已進入爭議處理' : '此訂單目前無法申報款項爭議';
    case 'complete':
    case 'serviceDispute':
      return '訂單狀態已更新，請重新整理';
    case 'careLog':
      return '訂單已結束，無法編輯日誌';
    default:
      return REFRESH;
  }
};

const roleMismatchText = (ctx: ErrorContext): string => {
  switch (ctx) {
    case 'booking':
      return '請切換為飼主身分後再預約';
    case 'withdraw':
      return '請切換為飼主身分';
    case 'settlement':
      return '請切換為飼主身分後再操作';
    case 'quote':
    case 'decline':
    case 'visit':
    case 'checklist':
      return '請切換至保母身分';
    case 'modification':
      return '請切換為保母 / 飼主身分後再操作';
    case 'payment':
    case 'careLog':
      return '請切換至正確角色';
    default:
      return '請切換身分後再操作';
  }
};

const forbiddenText = (ctx: ErrorContext): string => {
  switch (ctx) {
    case 'visit':
    case 'checklist':
    case 'careLog':
      return '無權限操作此行程';
    case 'booking':
      return '無權限使用此毛孩';
    default:
      return '無權限操作此訂單';
  }
};

const notFoundText = (ctx: ErrorContext): string => {
  switch (ctx) {
    case 'booking':
      return '找不到此保母';
    case 'visit':
    case 'careLog':
      return '找不到此行程';
    case 'checklist':
      return '找不到此項目，請重新整理';
    default:
      return '找不到此訂單';
  }
};

const validationText = (err: ApiError, ctx: ErrorContext): string => {
  const field = payloadString(err, 'field');
  if (field === 'Idempotency-Key' || field === 'expectedStatus') return '請重新操作';
  if (ctx === 'booking') {
    if (field === 'petIds') return '每個日期群組至少選擇 1 隻毛孩';
    if (field === 'baseAmount') return '單筆預約金額不可超過 $ 999,999，請拆成多筆預約';
    if (payloadString(err, 'questionId')) {
      const max = payloadNumber(err, 'max');
      return max ? `最多 ${max} 字` : '問卷答案格式有誤';
    }
  }
  if (ctx === 'decline' || ctx === 'withdraw') {
    if (field === 'reason') return ctx === 'withdraw' ? '最多 200 字' : '請填寫原因（最多 200 字）';
  }
  if (ctx === 'paymentDispute' && field === 'description') return '請填寫爭議說明（500 字以內）';
  if ((ctx === 'serviceDispute' || ctx === 'complete') && field === 'description')
    return '請填寫問題說明（500 字以內）';
  if (ctx === 'visit' && field === 'name') return '名稱最多 20 字';
  if (ctx === 'checklist') {
    if (field === 'items') return '每個行程最多 20 項';
    return '請輸入 1–50 字';
  }
  if (ctx === 'careLog' && field === 'file') {
    const reason = payloadString(err, 'reason');
    if (reason === 'SHA256_MISMATCH') return '檔案上傳不完整，請重新選擇檔案';
  }
  if (field === 'size' || field === 'body') return '資料過大，請縮小後再試';
  return '輸入資料有誤，請檢查後再送出';
};

const quotaText = (err: ApiError): string => {
  const type = payloadString(err, 'mediaType');
  const limit = payloadNumber(err, 'limit');
  if (limit === 0) return '此訂單方案僅支援文字回報';
  return `此行程${type === 'VIDEO' ? '影片' : '照片'}額度已用完`;
};

export const errorMessage = (err: ApiError, ctx: ErrorContext = 'generic'): string => {
  switch (err.code) {
    case 'NETWORK_ERROR':
      return '網路連線異常，請稍後再試';
    case 'RATE_LIMITED':
      return `操作過於頻繁，請於 ${err.retryAfterSec ?? 60} 秒後再試`;
    case 'UNAUTHENTICATED':
      return '登入已失效，請重新登入';
    case 'ROLE_MISMATCH':
      return roleMismatchText(ctx);
    case 'FORBIDDEN':
      return forbiddenText(ctx);
    case 'NOT_FOUND':
      return notFoundText(ctx);
    case 'VALIDATION_FAILED':
      return validationText(err, ctx);
    case 'ORDER_BUSY':
      return ctx === 'modification' ||
        ctx === 'settlement' ||
        ctx === 'paymentDispute' ||
        ctx === 'complete' ||
        ctx === 'serviceDispute'
        ? BUSY_ORDER
        : BUSY;
    case 'IDEMPOTENCY_IN_PROGRESS':
      return '處理中，請稍候';
    case 'IDEMPOTENCY_KEY_REUSED':
      return ctx === 'booking' || ctx === 'payment' || ctx === 'modification'
        ? '請重新送出'
        : '請重新操作';
    case 'INTERNAL_ERROR':
      return '系統發生錯誤，請稍後再試';
    case 'STORAGE_UNAVAILABLE':
      if (ctx === 'careLog') return '儲存服務暫時無法使用，請稍候重試';
      return ctx === 'payment' ? '上傳失敗，請重試' : '圖片暫時無法載入';
    case 'ORDER_STATE_CONFLICT':
      return orderStateText(err, ctx);
    case 'DEADLINE_PASSED':
      return deadlineText(err, ctx);
    // PRD-005
    case 'SELF_BOOKING_NOT_ALLOWED':
      return '這是您自己的預約頁，無法預約';
    case 'SITTER_NOT_BOOKABLE':
      return payloadString(err, 'reason') === 'NOT_VERIFIED'
        ? '此保母尚未完成實名認證，暫不開放預約'
        : '保母目前不開放預約';
    case 'SUBSCRIPTION_LOCKED':
      return ctx === 'quote'
        ? '您的訂閱已到期或遭停權，暫時無法接新訂單'
        : '保母目前暫停接受新預約';
    case 'MONTHLY_QUOTA_REACHED':
      return ctx === 'quote' ? '本月接單額度已滿' : '保母目前暫停接受新預約';
    case 'PLAN_NOT_BOOKABLE':
      return '此方案目前無法預約，請重新整理後再選擇';
    case 'INVALID_VISIT_DATE': {
      if (ctx === 'modification') return '新增日期需為明天以後，且在方案開放日期內';
      const dates = err.payload.dates;
      const list = Array.isArray(dates) ? dates.join('、') : '';
      return `所選日期 ${list} 無法預約`;
    }
    case 'PRICE_CHANGED': {
      const base = payloadNumber(err, 'baseAmount');
      return ctx === 'quote'
        ? `訂單金額已更新為 ${formatMoney(base)}，請確認後再送出`
        : '方案價格已更新，請確認新金額後再送出';
    }
    case 'QUESTIONNAIRE_CHANGED':
      return '保母的問卷已更新，請確認後再送出';
    case 'PENDING_ORDER_LIMIT_REACHED':
      return `您已有 ${payloadNumber(err, 'limit') ?? 3} 筆預約等待此保母回覆，請待保母回覆後再送出`;
    // PRD-006
    case 'PLAN_REQUIRED':
      return ctx === 'modification' ? '自訂變更金額需專業版以上' : '手動調價為專業版功能';
    case 'INVALID_QUOTE_AMOUNT':
      return '報價總額至少需 $ 1';
    case 'FIRST_VISIT_DATE_PASSED':
      return '首個行程日期已過，無法報價，請婉拒或請飼主重新預約';
    case 'PAYOUT_ACCOUNT_REQUIRED':
      return '請先設定收款帳號';
    // PRD-007 / 026 / 027
    case 'PROOF_FILE_TYPE_UNSUPPORTED':
      return '僅支援 JPG、PNG、WebP';
    case 'PROOF_FILE_TOO_LARGE':
      return '憑證圖片需小於 5MB';
    case 'PAYMENT_STATE_CONFLICT': {
      if (ctx === 'paymentDispute') {
        const at = payloadString(err, 'disputeAvailableAt');
        return at
          ? `補款憑證上傳滿 72 小時仍未核對才可申報（可申報時間：${formatDateTime(at)}）`
          : '此訂單目前無法申報款項爭議';
      }
      if (ctx === 'settlement') return '保母尚未上傳退款憑證';
      if (ctx === 'withdraw' || ctx === 'decline') return REFRESH;
      return '付款狀態已變更，請重新整理';
    }
    case 'PAYMENT_ALREADY_SUBMITTED':
      return ctx === 'withdraw'
        ? '已上傳付款憑證，無法撤回；如需取消請與保母聯繫'
        : ctx === 'decline'
          ? '飼主已上傳付款憑證，請先核對或駁回'
          : REFRESH;
    // PRD-016 / 023 / 024
    case 'MODIFICATION_IN_PROGRESS':
      return ctx === 'complete' || ctx === 'serviceDispute'
        ? '訂單變更處理中，完成後才能操作'
        : '此訂單已有進行中的變更請求';
    case 'MODIFICATION_STATE_CONFLICT':
      return ctx === 'paymentDispute'
        ? '此訂單目前無法申報款項爭議'
        : '此變更請求狀態已更新，請重新整理';
    case 'MODIFICATION_NOT_ALLOWED':
      return '所有行程皆已開始，無法再申請變更';
    case 'VISIT_ALREADY_STARTED':
      return ctx === 'settlement' ? '部分行程已開始，請重新協商' : '部分行程已開始，無法取消';
    case 'MODIFICATION_EMPTY':
      return '請至少選擇一個要減少或新增的行程';
    case 'PLAN_NOT_IN_ORDER':
      return '新增行程只能選擇本訂單已有的方案';
    case 'INVALID_MODIFICATION_AMOUNT':
      return '新總額不可小於 0';
    case 'INVALID_ADJUSTMENT_AMOUNT':
      return `調整金額不可超過 ${formatMoney(payloadNumber(err, 'maxAmount'))}`;
    // PRD-009
    case 'VISITS_NOT_FINISHED':
      return ctx === 'serviceDispute'
        ? '所有行程結束後才能回報問題'
        : '所有行程完成且保母送出照護日誌後，即可確認結案';
    // PRD-008 / 028 / 022
    case 'VISIT_STATE_CONFLICT':
      if (ctx === 'checklist') {
        return payloadString(err, 'currentVisitStatus') === 'PENDING'
          ? '開始行程後才能打勾'
          : '此行程已結束，無法修改';
      }
      if (ctx === 'careLog') return '行程結束後才能送出';
      return visitStateText(err);
    case 'VISIT_DATE_NOT_REACHED':
      return `尚未到服務日期（${payloadString(err, 'serviceDate') ?? ''}），無法開始行程`;
    case 'CARE_LOG_STATE_CONFLICT':
      return '日誌已送出，無法再修改';
    case 'CARE_LOG_UPLOAD_EXPIRED':
      return '上傳逾時，請重新上傳';
    case 'CARE_LOG_CONTENT_EMPTY':
      return '請填寫日誌內容後再送出';
    case 'CARE_LOG_MEDIA_TYPE_UNSUPPORTED':
      return '照片僅支援 JPG、PNG、WebP；影片僅支援 MP4、MOV';
    case 'CARE_LOG_MEDIA_TOO_LARGE':
      return payloadString(err, 'mediaType') === 'VIDEO' ||
        (payloadNumber(err, 'maxBytes') ?? 0) > 2 * 1024 * 1024
        ? '影片需小於 50MB'
        : '照片需小於 1MB';
    case 'CARE_LOG_VIDEO_DURATION_INVALID':
      return '影片長度需為 15–30 秒';
    case 'CARE_LOG_MEDIA_QUOTA_EXCEEDED':
      return quotaText(err);
    default:
      if (err.status !== null && err.status >= 500) return '系統發生錯誤，請稍後再試';
      return '操作失敗，請稍後再試';
  }
};
