# 後端專案進場 Review 報告

## 🔴 Critical (阻斷性問題)

### 1. Flyway 自動配置未觸發 — 根因已確認

**現象**：Spring Boot 成功啟動，但 Flyway 完全沒有執行遷移，資料庫為空。

**根因**：Spring Boot 4.x 對自動配置模組進行了**大規模拆分 (Modularization)**。在 3.x 時代，只要 classpath 上有 `flyway-core`，`FlywayAutoConfiguration` 就會自動觸發。但在 4.x 中，這個行為改為 **Opt-in 模式**，必須明確引入 `spring-boot-starter-flyway`。

**修正**：`pom.xml` 中將 `flyway-core` 替換為 `spring-boot-starter-flyway`。

```diff
- <dependency>
-     <groupId>org.flywaydb</groupId>
-     <artifactId>flyway-core</artifactId>
- </dependency>
+ <dependency>
+     <groupId>org.springframework.boot</groupId>
+     <artifactId>spring-boot-starter-flyway</artifactId>
+ </dependency>
```

> [!IMPORTANT]
> `flyway-database-postgresql` 仍然需要保留，因為它是 Flyway 11.x 對 PostgreSQL 的方言支援模組。

---

### 2. SD-GLOBAL-SPEC 版本號不一致

**現象**：`SD-GLOBAL-SPEC.md` 第 12 行仍記載 `Spring Boot 4.1.x`，但 `pom.xml` 實際使用 `4.0.6`。

**修正**：將 `SD-GLOBAL-SPEC.md` 中的版本描述更新為 `4.0.x`。

---

## 🟡 Warning (需修正但不阻斷)

### 3. `PetSitterApplication.java` 殘留除錯代碼

啟動類別中遺留了大量的 Bean 列印除錯邏輯（`CommandLineRunner`），必須在驗證完成後清除。

### 4. `application-local.yml` 的 `ddl-auto: none` 與冗餘配置

- `ddl-auto` 被暫時改為 `none`，Flyway 修復後應改回 `validate`。
- `flyway.url/user/password` 與 `datasource` 完全重複。修復 starter 後，Flyway 會自動使用 `datasource` 的連線資訊，這三行應移除。
- `logging.level.org.flywaydb: DEBUG` 是除錯用途，驗證後應移除。

### 5. Entity 與 SQL 的欄位對齊問題

| 欄位 | SQL (`orders` 表) | Java (`Order.java`) | 問題 |
|:---|:---|:---|:---|
| `created_by` | ✅ 有 (L48) | ❌ 缺少 | Entity 遺漏，Hibernate `validate` 會過但語義不完整 |
| `updated_by` | ✅ 有 (L49) | ❌ 缺少 | 同上 |

**建議**：在 `BaseEntity` 中補上 `createdBy` 和 `updatedBy`，或者從 SQL 中移除（如果決定不在 Entity 層追蹤操作者）。

### 6. JSONB 欄位型別過於寬鬆

`Order.items`、`OrderSnapshot.snapshotData`、`OrderLog.payload`、`ModificationRequest.payload` 全部宣告為 `Object`。

**風險**：`Object` 型別在序列化/反序列化時無法保證結構一致性，且 IDE 無法提供任何型別提示。

**建議**：至少改為 `Map<String, Object>`，未來可進一步定義 DTO。

```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(columnDefinition = "jsonb", nullable = false)
private Map<String, Object> items;
```

---

## 🟢 Info (建議優化)

### 7. `Order.java` 的 `import java.util.Map` 已宣告但未使用

第 9 行 `import java.util.Map` 已經存在，但 `items` 實際宣告為 `Object`，造成 unused import。

### 8. `BaseEntity` 缺少 `@Builder` 的繼承支援

所有子類別都用了 `@Builder`，但 `BaseEntity` 的欄位（`id`, `version`, `createdAt` 等）不會出現在 Builder 中。雖然這些欄位通常由框架自動填充，但如果未來需要在測試中建構完整物件，可能需要考慮 `@SuperBuilder`。

### 9. `application.yml` 的 `jackson.default-property-inclusion: non_null`

在 Spring Boot 4.x (Jackson 3.x) 中，部分 Jackson 配置的 key 名稱可能已變動（如同先前 `write_dates_as_timestamps` 的問題）。建議啟動後驗證此配置是否生效。

---

## 修正優先順序

```
1. 🔴 pom.xml: flyway-core → spring-boot-starter-flyway
2. 🔴 SD-GLOBAL-SPEC: 4.1.x → 4.0.x
3. 🟡 PetSitterApplication.java: 移除除錯代碼
4. 🟡 application-local.yml: 還原 validate + 清除冗餘
5. 🟡 Entity 欄位對齊 (created_by/updated_by)
6. 🟡 JSONB 型別 Object → Map<String, Object>
```
