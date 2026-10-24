/**
 * 全站金額顯示的唯一出口（驗收 N15）：`$ 1,200`、負數 `$ -600`。
 * 過去各頁自己寫 `$ {x.toLocaleString()}`，有的直接 `$ {plan.price}`（方案卡「$ 1200」沒有千分位）、
 * 公開頁又是 `NT$ 600`。千分位固定用 en-US：無參數的 toLocaleString() 會跟著瀏覽器語系
 * （de-DE 會變成 1.200），同一個金額在不同使用者畫面上長得不一樣。
 */
export const formatMoney = (amount: number | null | undefined): string =>
  `$ ${(amount ?? 0).toLocaleString('en-US')}`;
