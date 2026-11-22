package com.petsitter.domain.model.order;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * JSONB 欄位的 Jackson 3 轉換器（專案統一使用 tools.jackson）。Hibernate 7.2 內建 JSON mapper 為 Jackson 2，
 * record 反序列化不可靠，故訂單聚合的 JSONB 欄以文字 + {@code ?::jsonb} 寫入、{@code ::text} 讀出，由本轉換器序列化。
 */
public final class JsonbConverters {

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private JsonbConverters() {
    }

    @Converter
    public static class ModificationContentConverter implements AttributeConverter<ModificationContent, String> {
        @Override
        public String convertToDatabaseColumn(ModificationContent attribute) {
            return attribute == null ? null : MAPPER.writeValueAsString(attribute);
        }

        @Override
        public ModificationContent convertToEntityAttribute(String dbData) {
            return dbData == null ? null : MAPPER.readValue(dbData, ModificationContent.class);
        }
    }

    @Converter
    public static class PayloadConverter implements AttributeConverter<Map<String, Object>, String> {
        @Override
        public String convertToDatabaseColumn(Map<String, Object> attribute) {
            return attribute == null ? null : MAPPER.writeValueAsString(attribute);
        }

        @Override
        public Map<String, Object> convertToEntityAttribute(String dbData) {
            return dbData == null ? null : MAPPER.readValue(dbData, new TypeReference<Map<String, Object>>() {
            });
        }
    }
}
