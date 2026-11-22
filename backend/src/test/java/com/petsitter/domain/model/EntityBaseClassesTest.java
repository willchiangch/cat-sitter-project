package com.petsitter.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** SD-GLOBAL §2.5：重建模組實體基底的欄位契約（B 階段實體繼承後由 ddl-auto: validate 對表驗證）。 */
@DisplayName("SD-GLOBAL §2.5 AuditedEntity / VersionedEntity")
class EntityBaseClassesTest {

    private static List<String> columns(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .map(f -> f.getAnnotation(Column.class) != null ? f.getAnnotation(Column.class).name() : f.getName())
                .toList();
    }

    @Test
    @DisplayName("AuditedEntity：id（UUID）、created_at（@CreatedDate）、updated_at（@LastModifiedDate）；無 is_deleted / @CreatedBy / @Version")
    void auditedEntity() throws Exception {
        assertThat(AuditedEntity.class.isAnnotationPresent(MappedSuperclass.class)).isTrue();
        assertThat(columns(AuditedEntity.class)).containsExactlyInAnyOrder("id", "created_at", "updated_at");
        Field id = AuditedEntity.class.getDeclaredField("id");
        assertThat(id.getType()).isEqualTo(UUID.class);
        assertThat(id.isAnnotationPresent(Id.class)).isTrue();
        assertThat(AuditedEntity.class.getDeclaredField("createdAt").isAnnotationPresent(CreatedDate.class)).isTrue();
        assertThat(AuditedEntity.class.getDeclaredField("createdAt").getAnnotation(Column.class).updatable()).isFalse();
        assertThat(AuditedEntity.class.getDeclaredField("updatedAt").isAnnotationPresent(LastModifiedDate.class)).isTrue();
        for (Field f : AuditedEntity.class.getDeclaredFields()) {
            assertThat(f.isAnnotationPresent(CreatedBy.class)).isFalse();
            assertThat(f.isAnnotationPresent(LastModifiedBy.class)).isFalse();
            assertThat(f.isAnnotationPresent(Version.class)).isFalse();
        }
    }

    @Test
    @DisplayName("VersionedEntity extends AuditedEntity，只多 @Version version，且沒有 setter（不接受客戶端 version）")
    void versionedEntity() throws Exception {
        assertThat(VersionedEntity.class.getSuperclass()).isEqualTo(AuditedEntity.class);
        assertThat(columns(VersionedEntity.class)).containsExactly("version");
        assertThat(VersionedEntity.class.getDeclaredField("version").isAnnotationPresent(Version.class)).isTrue();
        assertThat(Arrays.stream(VersionedEntity.class.getMethods()).map(java.lang.reflect.Method::getName))
                .doesNotContain("setVersion");
        assertThat(Arrays.stream(AuditedEntity.class.getMethods()).map(java.lang.reflect.Method::getName))
                .doesNotContain("setId", "setCreatedAt", "setUpdatedAt");
    }
}
