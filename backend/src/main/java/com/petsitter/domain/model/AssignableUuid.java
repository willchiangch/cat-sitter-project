package com.petsitter.domain.model;

import org.hibernate.annotations.IdGeneratorType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * UUID 主鍵：未指定時產生隨機 UUID（同 {@code GenerationType.UUID}）；Service 已指定時沿用。
 * SD-ORDER §8.2（NFR-003 U-8、CQ-48）：{@code payments} 的 UUID 必須在加密收款帳號快照前由 Service 產生
 * （AAD = {@code payments:{id}:payee_account_snapshot}），INSERT 時寫入同一值。
 */
@IdGeneratorType(AssignableUuidGenerator.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface AssignableUuid {
}
