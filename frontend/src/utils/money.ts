/**
 * 全站金額顯示的唯一出口（驗收 N15）：`$ 1,200`、負數 `-$ 600`（fix-batch13：負號放在幣別符號前，
 * 與 en-US 慣例 / Intl.NumberFormat 的 `-$600.00` 一致；過去是 `$ -600`，TS-JOURNEY-04 寫的 `-$600` 與畫面不符）。
 * 過去各頁自己寫 `$ {x.toLocaleString()}`，有的直接 `$ {plan.price}`（方案卡「$ 1200」沒有千分位）、
 * 公開頁又是 `NT$ 600`。千分位固定用 en-US：無參數的 toLocaleString() 會跟著瀏覽器語系
 * （de-DE 會變成 1.200），同一個金額在不同使用者畫面上長得不一樣。
 * `$` 與數字之間用不斷行空白 U+00A0（fix-batch12 U2）：一般空白在窄欄會斷成「$ / 1,200」。
 */
export const MONEY_SEPARATOR = '\u00A0';

export const formatMoney = (amount: number | null | undefined): string => {
  const value = amount ?? 0;
  const sign = value < 0 ? '-' : '';
  return `${sign}$${MONEY_SEPARATOR}${Math.abs(value).toLocaleString('en-US')}`;
};
