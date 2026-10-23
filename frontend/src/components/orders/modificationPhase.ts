import type { ModificationRequestDetailDto } from '../../api/orderApi';

/**
 * 變更協商目前所處階段 (SD-016 §2.2)。保母報價頁與飼主確認頁都依這個階段只顯示「當下可執行」的動作，
 * 已完成的動作（報價、同意變更、確認收到退款）不再顯示按鈕；補款分支不顯示退款區塊。
 * - AWAIT_QUOTE：PENDING_REVIEW，保母可報價或拒絕；飼主只能等待
 * - AWAIT_OWNER_CONFIRM：QUOTED，飼主可同意；保母只能拒絕
 * - SUPPLEMENT：飼主已同意、差額為正，訂單 PENDING_PAYMENT，補款在訂單詳情頁進行
 * - REFUND：飼主已同意、差額為負，訂單 REFUND_VERIFY，保母上傳退款憑證、飼主確認收到退款
 * - CLOSED：其餘（已拒絕、已結清），唯讀
 */
export type ModificationPhase =
  | 'AWAIT_QUOTE'
  | 'AWAIT_OWNER_CONFIRM'
  | 'SUPPLEMENT'
  | 'REFUND'
  | 'CLOSED';

export function resolveModificationPhase(mod: ModificationRequestDetailDto): ModificationPhase {
  if (mod.status === 'PENDING_REVIEW') return 'AWAIT_QUOTE';
  if (mod.status === 'QUOTED') return 'AWAIT_OWNER_CONFIRM';
  if (mod.status === 'M_DONE') {
    if (mod.orderStatus === 'REFUND_VERIFY' && mod.diffAmount < 0) return 'REFUND';
    if (mod.orderStatus === 'PENDING_PAYMENT' && mod.diffAmount > 0) return 'SUPPLEMENT';
  }
  return 'CLOSED';
}

export const formatDiff = (diff: number) =>
  diff < 0
    ? `應退款 $ ${Math.abs(diff).toLocaleString()}`
    : diff > 0
      ? `應補款 $ ${diff.toLocaleString()}`
      : '無差額';
