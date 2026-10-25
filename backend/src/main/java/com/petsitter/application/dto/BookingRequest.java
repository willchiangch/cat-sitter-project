package com.petsitter.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingRequest {
    // 不收 ownerId（fix-batch11 I2，BOLA）：飼主身分由 OrderController 從 token 取得後傳入 createBooking。
    // 舊前端 body 仍會帶 ownerId，Jackson 忽略未知欄位，不會報錯也不會被使用
    private UUID sitterId;
    private List<BookingItemRequest> items;
    private List<QuestionnaireAnswerRequest> answers;
    // Zero-Trust Pricing (SD-005)：前端試算金額，後端重新計算後比對，防止方案價格
    // 在使用者填表期間異動卻無感送出舊報價
    private Integer expectedTotalAmount;
    private String idempotencyKey;
}
