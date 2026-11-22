package com.petsitter.application.service.notify;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Map;

/**
 * 通知範本（SD-ORDER §7.2 類型代碼 → 標題 / 內文）。範本歸 SD-NOTIFY（設計未撰寫），B 階段過渡：
 * PRD 已給字面者照字面；「範本由 SD-NOTIFY 擬定」者暫用簡短中性文案。金額格式依 SD-FRONTEND-SPEC §6.4.1（{@code $ 1,800}）。
 */
public final class NotificationTemplates {

    private NotificationTemplates() {
    }

    public record Rendered(String title, String content) {
    }

    public static Rendered render(String type, Map<String, Object> p) {
        return switch (type) {
            case "NEW_BOOKING_REQUEST" -> r("新預約申請", "您收到一筆新預約申請" + suffix(p, "ownerName", "（", "）"));
            case "QUOTE_SENT" -> r("保母已報價", "保母已報價 " + money(p.get("totalAmount")) + "，請於 " + p.get("dueAt") + " 前完成付款");
            case "QUOTE_SENT_WITH_PAYMENT_REMINDER" -> r("保母已報價（付款提醒）",
                    "保母已報價 " + money(p.get("totalAmount")) + "，請於 " + p.get("dueAt") + " 前完成付款");
            case "BOOKING_DECLINED" -> r("預約已婉拒", "預約已婉拒" + suffix(p, "reason", "，原因：", ""));
            case "BOOKING_DECLINED_SELF" -> r("已婉拒預約", "您已婉拒 " + p.getOrDefault("ownerName", "飼主") + " 的預約");
            case "BOOKING_WITHDRAWN" -> r("飼主已撤回預約", "飼主已撤回預約（" + p.get("firstServiceDate") + "）"
                    + suffix(p, "reason", "，原因：", ""));
            case "BOOKING_WITHDRAWN_SELF" -> r("已撤回預約", "您已撤回預約");
            case "REVIEW_EXPIRED_OWNER" -> r("預約已自動取消", "保母未於期限內回覆，預約已自動取消");
            case "REVIEW_EXPIRED_SITTER" -> r("預約已逾期", "預約已逾期自動取消");
            case "SITTER_CANCELLED" -> r("保母已取消訂單", "保母已取消訂單" + suffix(p, "reason", "，原因：", ""));
            case "SITTER_CANCELLED_SELF" -> r("已取消訂單", "您已取消訂單");
            case "PAYMENT_PROOF_SUBMITTED" -> r("已上傳付款憑證", "對方已上傳" + money(p.get("amount")) + " 的付款憑證，請核對");
            case "ORDER_ACTIVATED" -> r("訂單啟動成功", "訂單啟動成功");
            case "SUPPLEMENT_VERIFIED" -> r("補款已確認", "補款已確認，變更已生效");
            case "REFUND_VERIFIED" -> r("退款已確認", "退款已確認，變更已完成");
            case "PAYMENT_REJECTED" -> r("付款憑證被駁回", "付款憑證被駁回" + suffix(p, "reason", "，原因：", "") + "，請重新上傳");
            case "PAYMENT_REMINDER" -> r("付款提醒", "請於 " + p.get("dueAt") + " 前完成付款");
            case "PAYMENT_EXPIRED" -> r("付款逾期", "付款逾期，訂單已取消");
            case "PAYMENT_ESCALATED" -> r("付款待核對", "付款憑證已逾時未核對，平台已建立待辦");
            case "PAYMENT_VOIDED_BY_ADMIN" -> r("付款已作廢", "平台已作廢此筆付款，款項請由雙方自行處理");
            case "MODIFICATION_REQUESTED" -> r("飼主提出訂單變更", "飼主提出訂單變更");
            case "MODIFICATION_PROPOSED" -> r("保母提出訂單變更", "保母提出訂單變更並已報價");
            case "MODIFICATION_QUOTED" -> r("保母已回覆變更報價", "保母已回覆變更報價");
            case "MODIFICATION_REJECTED_BY_SITTER" -> r("保母已拒絕變更", "保母已拒絕變更" + suffix(p, "reason", "，原因：", ""));
            case "MODIFICATION_REJECTED_BY_OWNER" -> r("飼主已拒絕變更", "飼主已拒絕變更");
            case "MODIFICATION_WITHDRAWN" -> r("變更已撤回", "OWNER".equals(p.get("initiatedBy"))
                    ? "飼主已撤回變更請求" : "保母已撤回變更請求");
            case "MODIFICATION_EXPIRED" -> r("變更請求已逾期", "變更請求已逾期");
            case "MODIFICATION_SETTLED" -> r("變更已生效", "變更已生效");
            case "MODIFICATION_ACCEPTED_SUPPLEMENT" -> r("飼主已同意變更", "飼主已同意變更，待飼主補款 " + money(p.get("amount")));
            case "SUPPLEMENT_REQUIRED" -> r("請完成補款", "請補款 " + money(p.get("amount")) + "，請於 " + p.get("dueAt") + " 前完成");
            case "REFUND_REQUIRED" -> r("請退款", "請退還 " + money(p.get("amount")) + " 並上傳退款憑證");
            case "SUPPLEMENT_EXPIRED" -> r("補款逾期", "補款逾期，新增行程未生效");
            case "SYSTEM_REFUND_REQUIRED" -> r("請退款", "請退還 " + money(p.get("amount")));
            case "ORDER_CANCELLED_BY_MODIFICATION" -> r("訂單已取消", "訂單已取消");
            case "PAYMENT_DISPUTE_OPENED" -> r("訂單已進入爭議處理", "訂單已進入爭議處理");
            case "SERVICE_DISPUTE_OPENED" -> r("訂單進入爭議處理", "飼主回報服務問題，訂單進入爭議處理");
            case "ORDER_MEDIATED" -> r("平台已完成調解", "平台已完成此訂單的調解");
            case "SUPPLEMENT_VOIDED_BY_ADMIN" -> r("補款已作廢", "此筆補款由雙方自行處理");
            case "ORDER_COMPLETED_BY_OWNER" -> r("飼主已確認結案", "飼主已確認結案");
            case "ORDER_AUTO_COMPLETED" -> r("訂單已自動結案", "訂單已自動結案");
            // SD-FULFILL §7.2（PRD-008 4.3 / 4.4、PRD-022 BR-17 字面）
            case "VISIT_CLOSED_BY_SYSTEM_SITTER" -> r("行程已由系統關閉",
                    visitLines(p, v -> v.get("serviceDate") + " " + v.get("name") + " 未於期限內開始，已由系統關閉"));
            case "VISIT_CLOSED_BY_SYSTEM_OWNER" -> r("行程已由系統關閉",
                    visitLines(p, v -> "保母未於期限內開始 " + v.get("serviceDate") + " " + v.get("name") + "，系統已關閉此行程"));
            case "VISIT_AUTO_COMPLETED" -> r("行程已自動結束", p.get("serviceDate") + " " + p.get("name")
                    + " 開始後超過 24 小時未結束，系統已自動結束。請於 " + p.get("careLogDeadlineAt") + " 前送出照護日誌");
            case "CARE_LOG_SUBMITTED" -> r("照護日誌已回報", p.get("serviceDate") + " " + p.get("name")
                    + " 的照護日誌已由保母回報，快來看看毛孩的近況吧！");
            default -> r("訂單通知", String.valueOf(p.getOrDefault("message", "訂單狀態已更新")));
        };
    }

    public static String money(Object amount) {
        if (amount == null) {
            return "$ 0";
        }
        long v = amount instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(amount));
        return "$ " + NumberFormat.getIntegerInstance(Locale.US).format(v);
    }

    @SuppressWarnings("unchecked")
    private static String visitLines(Map<String, Object> p, java.util.function.Function<Map<String, Object>, String> line) {
        Object visits = p.get("visits");
        if (!(visits instanceof java.util.List<?> list)) {
            return "";
        }
        return list.stream().map(v -> line.apply((Map<String, Object>) v)).collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String suffix(Map<String, Object> p, String key, String before, String after) {
        Object v = p.get(key);
        return v == null || String.valueOf(v).isBlank() ? "" : before + v + after;
    }

    private static Rendered r(String title, String content) {
        return new Rendered(title, content);
    }
}
