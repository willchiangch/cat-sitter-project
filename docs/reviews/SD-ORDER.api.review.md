# SD-ORDER 審查紀錄（API 與追溯面）

> 範圍：§1 AC 對應、§2.3 / §2.4 介面、§6 API 契約、§7 副作用、§9 前端、§10 測試設計。資料模型、共用機制內部、排程由另一位審查員負責，本文件不深入。
> 基準：PRD-005 / 006 / 007 / 016 / 023 / 024 / 026 / 027（Approved）、PRD-025（只看介面）、DOMAIN-BASELINE 第 2 版、SD-GLOBAL-SPEC、SD-FRONTEND-SPEC §4。
> 唯讀查證：`backend/pom.xml`（Spring Boot 4.0.6）、`SecurityConfig.java`、`InternalSecretFilter.java`、`GlobalExceptionHandler.java`、`application.yml`、`frontend/src/api/axiosClient.ts`、`docs/api/openapi.json`。

---

## 第 1 輪（2026-11-22）

### 結論

**Needs Clarification**：1 項阻擋、8 項重要、11 項建議；新增 CQ-40～CQ-42 待使用者回答。

- AC 點數：以 `^- \[.\] \*\*AC-` 逐張點數，PRD-005 24、PRD-006 20、PRD-007 24、PRD-016 24、PRD-023 22、PRD-024 16、PRD-026 8、PRD-027 18，**合計 156**，與作者宣稱一致。§1 八個子表逐條比對，**156 / 156 皆有列**，無缺號、無重號、無 PRD 不存在的 AC。
- §1 每列引用的端點代號（E1–E31、C1–C10）都在 §2.3.4 / §6.2 / §8.1 存在；測試類別都在 §10.3 存在，且 §10.3「涵蓋 AC」欄反向展開後同樣覆蓋 156 條（PRD-005 AC-2.4、PRD-027 AC-1.5 只由 TS 驗證，§10.3 末段已註明）。
- 錯誤碼：8 張 PRD 例外表的錯誤碼在 §6.5 全部找得到；`DEADLINE_PASSED`（`deadline` + `deadlineAt`）、`ORDER_STATE_CONFLICT`（`currentStatus`）、`PRICE_CHANGED`（目前值）payload 符合 baseline §3.7。各端點的錯誤先後與 PRD 自訂的業務順序（PRD-005 §4.4 步驟 3.8、PRD-006 §4.3 步驟 2.8、PRD-007 §3、PRD-016 BR-28）一致。
- 冪等端點清單（E1、E6–E9、E11、E14–E17、E20–E26、E29）完整，PRD 要求帶 key 的寫入端點都在內。
- 副作用：每個轉移都有 `order_logs` action；`mandatory=true` 的 5 種通知與 baseline CQ-73 一致（收款帳號變更屬 SD-SUPPLY）；收件者與 PRD 逐一相符（O3 / O4 / O7 通知雙方、T4 SITTER 拒絕只通知 OWNER、O14 只通知另一方且不通知 ADMIN）。

主要問題集中在三處：混合角色端點的 `ROLE_MISMATCH` 判定未定義（阻擋）；「DTO 不掛 `@Valid`」的作法本身成立，但周邊（SecurityConfig、全域例外對映、冪等攔截器讀 body、Jackson 數值轉型）沒有一起設計，照現況實作會有數條 AC 不過；§9 前端 testid 沒有跟上 CQ-10 的決定。

### 對作者主張「DTO 不掛 `@Valid`、`Idempotency-Key` 非必填」的查證

| 項目 | 結果 |
|------|------|
| `@PreAuthorize` 被拒的例外 | Spring Security 6.3 起（本專案 7.0.x）為 `AuthorizationDeniedException`，是 `AccessDeniedException` 的子類；在 controller 方法呼叫時拋出，會進 `@RestControllerAdvice`，`@ExceptionHandler` 取最具體的型別。分開對映 `ROLE_MISMATCH` / `FORBIDDEN` **可行** |
| 冪等回放先於 `ROLE_MISMATCH` | `HandlerInterceptor.preHandle` 在 handler mapping 之後、HandlerAdapter 呼叫 controller proxy 之前執行，method security 在 proxy 內，順序**成立**。前提：`SecurityConfig` 對 `/api/orders/**` 只有 `.authenticated()`，沒有 URL 層角色規則（已確認） |
| 不掛 `@Valid` + header `required=false` | 可讓 400 延後到第 6 步，**成立**。但進方法前仍會回 400 的還有：JSON 型別不符 / enum 值不存在（`HttpMessageNotReadableException`）、`@PathVariable UUID` 格式錯（`MethodArgumentTypeMismatchException`）、缺 `@RequestPart`（`MissingServletRequestPartException`）、缺 body。§6.1 只豁免第一種且未說明欄位型別，見 #I4 |
| 401 | 未帶 token 打 `.anyRequest().authenticated()` 的路徑時，回應由 `ExceptionTranslationFilter` → `authenticationEntryPoint` 直接寫出（現為 `{"error":"UNAUTHORIZED"}`），不經 `GlobalExceptionHandler`；§6.1 G1 寫「`JwtAuthenticationFilter` / `TokenContext`」不正確，見 #I1 |
| 前端讀 `code` 的影響 | `axiosClient.ts` 只看 HTTP 401 做 refresh，不讀錯誤欄位，不受影響。訂單相關頁面讀 `data?.message`（`OwnerOrderDetail` 3 處、`OrderEvalView` 4、`PublicBookingPage` 1、`SitterOrders` 1、`SitterModificationQuote` 1、`OwnerModificationConfirm` 1），只有 `OrderModificationWizard` 讀 `data.error`；新格式保留 `message`，且這些頁面都在本次重寫範圍，影響小。風險在兩種格式並存，見 #S2 |

### 問題清單

| # | 嚴重度 | 位置 | 問題 | 建議 |
|---|-------|------|------|------|
| B1 | 阻擋 | §6.1 G3–G5、§6.2 歸屬檢查欄（E2、E10–E15、E17–E19、E21、E22、E24） | 允許 OWNER 與 SITTER 兩種角色的端點，`@PreAuthorize` 擋不到「當事人用錯角色」。§6.2 寫法是「OWNER 時比 `order.owner`、SITTER 時比 `order.sitter`」，不符一律 403 `FORBIDDEN`。但 PRD-007 AC-2.5 要求「該保母以 OWNER 角色 token 呼叫確認 → 403 `ROLE_MISMATCH`」，PRD-016 AC-1.7 要求「帳號 S（該單保母）以 `currentRole=OWNER` 呼叫保母發起端點 → 403 `ROLE_MISMATCH`」（PRD-016 §3 第 2 點同義）。照 SD 實作兩條 AC 會得到 `FORBIDDEN` | 在 G4 與 G5 之間新增一步並寫進 §6.1：「token `userId` 是此訂單的 owner 或 sitter，但 `currentRole` 不是其當事人角色 → 403 `ROLE_MISMATCH`；不是當事人 → 403 `FORBIDDEN`；當事人且角色正確但方向不符 → 403 `FORBIDDEN`」。此規則與 PRD-007 AC-1.3（保母以 SITTER 上傳 CHARGE → `FORBIDDEN`）、AC-2.5（飼主確認 CHARGE → `FORBIDDEN`）都相容。位置屬判斷順序解讀，列 CQ-40；`OrderIdentitySecurityTest` 補「當事人錯角色」情境 |
| I1 | 重要 | §6.1 G1、§6.2 E31、§8.3 | `SecurityConfig` 的必要修改沒列：(1) `GET /api/sitters/*/booking-context` 不在 `permitAll`，匿名呼叫 E31 會被 `.authenticated()` 擋成 401，PRD-005 AC-1.1 情境 1、AC-1.4、AC-1.5 的匿名檢視全部失敗；(2) `authenticationEntryPoint` 回 `{"error":"UNAUTHORIZED","message":...}`，不是 `401 UNAUTHENTICATED` 也不是 §6.1 格式，8 張 PRD 的 401 斷言都會失敗；(3) `/api/internal/**` 的 `hasRole("INTERNAL")` 拒絕走預設 `AccessDeniedHandler`，回空 body | §8.3 新增 D7：E31 加入 `permitAll`（Optional JWT）；`authenticationEntryPoint` 與 `accessDeniedHandler` 改寫成 §6.1 格式（`code=UNAUTHENTICATED` / `FORBIDDEN`）；G1 拋出位置改為「`ExceptionTranslationFilter` → `authenticationEntryPoint`；permitAll 路徑上的 `TokenContext.getUserId()` → `GlobalExceptionHandler`」。entryPoint 是全域，影響列入跨 SD |
| I2 | 重要 | §6.1 角色列、§6.4 | `GlobalExceptionHandler` 是全域 advice，§6.4 的新對映會套到所有模組，§6.1「其他模組沿用 `AccessDeniedException` → `FORBIDDEN` 不受影響」不成立：(1) 其他模組的 `@PreAuthorize` 拒絕同樣拋 `AuthorizationDeniedException`，會變成 `ROLE_MISMATCH`；(2) `ObjectOptimisticLockingFailureException` → `ORDER_BUSY` 會取代其他模組現行的 `MSG_DATA_CONCURRENCY_CONFLICT`；(3) `MaxUploadSizeExceededException` → `PROOF_FILE_TOO_LARGE` 與 `spring.servlet.multipart.max-file-size=6MB` 會套到 KYC、照護媒體上傳（現在沒有 multipart 設定，Spring 預設單檔 1 MB）；(4) 現有 `IllegalStateException` → 409 `STATE_CONFLICT`，§2.4.1 的 `onVisitLeftPending` 以 `IllegalStateException` 表示程式錯誤，會被回成 409 而不是 500 | 明確選一種並寫入 §6.4：全域採用（`ROLE_MISMATCH` 與錯誤格式本來就是 baseline 跨模組規則）並在 §8.3 列出其他模組受影響的測試；或把訂單專屬對映（`ORDER_BUSY`、`PROOF_FILE_TOO_LARGE`、`STORAGE_UNAVAILABLE`、約束名對映）放進只套訂單 controller 的 advice（`basePackageClasses` + `@Order(Ordered.HIGHEST_PRECEDENCE)`），multipart 上限改在 E11 controller 自行檢查。程式錯誤改用自訂例外。列 CQ-42 |
| I3 | 重要 | §3.6.2 段 0、§11.3「冪等回放查詢位置」 | `IdempotencyInterceptor` 要在 `preHandle` 算 request hash，但 Servlet body 只能讀一次：攔截器讀完後 `@RequestBody` 會讀到空串流；`ContentCachingRequestWrapper` 是「讀過才快取」，也解決不了。另「正規化後的 JSON body」沒定義，同內容但 key 順序或數字寫法不同（`1` / `1.0`）會被判 422 `IDEMPOTENCY_KEY_REUSED` | 補一個只套冪等端點的 filter，先把 body 讀成 byte[]（設上限，例 64 KB）再包成可重讀的 wrapper；multipart 在 `DispatcherServlet.checkMultipart` 已解析，`MultipartFile.getInputStream()` 可重讀，hash 可在攔截器算。正規化定義寫明：解析成樹、物件 key 排序、數字以整數 / 十進位字串統一、去掉不影響語意的空白 |
| I4 | 重要 | §6.1 欄位驗證列、§6.3 E6 / E17 / E20 / E29、§6.4 | (1) Jackson `ACCEPT_FLOAT_AS_INT` 預設啟用（Jackson 2；Jackson 3 預計相同，實作時確認），`100.5` 綁到 `int` / `Integer` 會被截成 100，PRD-006 AC-3.3 情境 3（加價 100.5 → 400）會變 200；PRD-016 BR-10、PRD-024 E29 金額同理。(2) 欄位宣告成 enum（`type`、`expectedStatus`）或 UUID 時，值不合法在進方法前回 400，且 §6.4 沒對映 `MethodArgumentTypeMismatchException`、`MissingServletRequestPartException`，會回 Spring 預設 body，不是 §6.1 格式 | §6.3 的 request 表註明 DTO 型別：金額欄用 `BigDecimal` 或 `JsonNode`，由 `OrderRequestValidator` 判整數與範圍；enum 欄位用 `String`；`@RequestPart` / `@RequestParam` 一律 `required=false`。§6.4 補兩個例外 → 400 `VALIDATION_FAILED`，並寫明「路徑 UUID 格式錯回 400，早於 404，可接受」 |
| I5 | 重要 | §2.3.4 E15 vs §6.2 E15 | §2.3.4 寫 E15 角色「SITTER（僅 CHARGE 收款方）」，§6.2 寫 `OWNER, SITTER`。照 §2.3.4 實作，飼主駁回退款會被 `@PreAuthorize` 擋成 `ROLE_MISMATCH`，PRD-007 AC-2.4（403 `FORBIDDEN`，`DISPUTED` 時仍 `FORBIDDEN`）與 §3 判斷順序失效 | §2.3.4 改為 `OWNER / SITTER`，用途欄註明「只有 CHARGE 收款方能駁回，其餘當事人 403 `FORBIDDEN`」 |
| I6 | 重要 | §9.1 第 6–8 列（`/orders/:orderId/modify`、協商頁、變更歷史）、§12 CQ-10 | CQ-10 標「已決：已補進三張 PRD §9」，PRD-016 / 023 / 024 §9 也確實已有 testid，但 §9.1 三列仍寫「PRD-016 / 023 / 024 未定義（CQ-10）」，前端無從對照。另保母詳情與協商頁缺 PRD-007 退款上傳用的 testid（PRD-007 AC-1.2：保母開啟退款頁應有 `proof-disclaimer-checkbox`、`proof-last5-input`、`submission-history`），協商頁補款區塊沿用 PRD-007 付款區塊 testid（PRD-023 §9）也沒列 | 依 PRD 補：精靈列 `order-modify-type-{type}`、`order-modify-visit-checkbox-{visitId}`、`order-modify-add-item-{index}`、`order-modify-input-reason`、`order-modify-btn-submit`（PRD-016）；協商頁列 `modification-detail-diff`、`modification-detail-expires-at`、`modification-btn-quote`、`modification-btn-reject`、`modification-input-reject-reason`、`modification-btn-withdraw`（PRD-016）、`modification-btn-accept`（PRD-023）、`modification-btn-payment-dispute`、`payment-dispute-input-description`、`payment-dispute-btn-submit`、`payment-dispute-available-at`（PRD-024），以及 PRD-007 的 `payment-*` / `proof-*` / `submission-history` / `btn-verify-payment` / `btn-reject-payment` / `reject-reason-input`；歷史列 `modification-history-item-{modificationId}`、`modification-history-empty`。保母詳情列補 `proof-*` 四個 |
| I7 | 重要 | §6.3 E21、§2.3.4 E21 | PRD 互相矛盾：PRD-016 AC-2.6 / BR-8 / §3 規定 SITTER 對 OWNER 發起的 `QUOTED` 呼叫拒絕 → 409 `MODIFICATION_STATE_CONFLICT`；PRD-023 §3 表與 EX-2 寫「SITTER 角色呼叫同意 / 拒絕 → 403 `ROLE_MISMATCH`」。E21 是 OWNER / SITTER 共用端點，SD 採 PRD-016 的 409，但沒註記偏離 PRD-023 EX-2；之後寫 TS-023 時很可能照 EX-2 斷言 403 | 列 CQ-41。預設採 PRD-016 AC-2.6（409），由主流程把 PRD-023 §3 / EX-2 的 `ROLE_MISMATCH` 限縮為「同意」端點；E21 契約加一句註明 |
| I8 | 重要 | §10.4 | TS 需要的 provision 不足：(1) `sitter-setup.kycVerified` 是布林，造不出 PRD-005 AC-1.2 的 `PENDING_REVIEW` 與 `UNVERIFIED` 兩種情境；缺公開狀態隱藏（AC-1.3）、全域 / 方案黑白名單（AC-1.3–1.5）、ADMIN 停權（AC-1.6 情境 3）、訂閱到期日（AC-1.6 情境 1、2 的跨日邊界）。(2) 飼主端缺毛孩（含已刪除，PRD-005 AC-2.4、PRD-006 AC-1.1 情境 2）、我的最愛已滿 50 位（PRD-005 AC-3.8 情境 4）、Email 偏好開關（多張 PRD 的投遞斷言）。(3) `shift-time` 的 `PAYMENT_DUE_AT` / `SUBMISSION_AT` 沒指定哪一筆 payment / submission，訂單同時有 INITIAL 與 SUPPLEMENT 時無法定位。(4) 沒有把行程造成 `DONE` / `IN_PROGRESS` / `CLOSED_BY_SYSTEM` 的途徑，PRD-016 / 023 / 024 大部分 AC 的前提都需要；SD-FULFILL 未重寫前 E2E 無法造資料 | `sitter-setup` 改收 `kycStatus`（四值）、`profileVisible`、`suspended`、`subscriptionExpiresAt`、`gatekeeper`（全域黑 / 白名單、各方案黑 / 白名單）；新增 `owner-setup`（`pets[]` 含 `deleted`、`favoriteSitterIds[]`、`emailPreferences`）；`shift-time` 加 `paymentId` / `submissionId`；新增行程狀態造資料端點（經 SD-FULFILL Service、取訂單鎖），或在 §12 依賴欄寫明由 SD-FULFILL 提供、提供前哪些 TS 只能跑整合測試 |
| S1 | 建議 | §6.3 全部 response | `application.yml` 設 `spring.jackson.default-property-inclusion: non-null`，SD 標 `\| null` 的欄位（`quote`、`openModification`、`cancellation`、`accountSnapshot`、OWNER 發起時的金額欄、`payment`、E19 的 `rejectReason` 等）實際會被省略，冪等回放 body 也一樣，前端型別若寫 `T \| null` 會拿到 `undefined` | §6.1 加一句「值為 null 的欄位不輸出，前端以缺欄位視為 null」，或訂單 DTO 標 `@JsonInclude(ALWAYS)` |
| S2 | 建議 | §9.2 | 改版後訂單端點回 `{ code }`，其他模組仍回 `{ error }`，mock E2E fixture（例 `dispute-and-completion.spec.ts`）也是 `{ error }`；各頁自己解析容易混用 | 在 `frontend/src/api/` 加共用 `getApiError(e)`（`code ?? error`、`message`、`payload` 型別），§9.2 指定訂單頁一律經它；mock fixture 一併改 |
| S3 | 建議 | §6.3 E11 錯誤欄 | 「payment 為 `ADMIN_ADJUSTMENT` 時不做方向檢查，直接回 409」的「直接」容易讀成跳過 G6；PRD-007 判斷順序是 400 先於 409（AC-1.2 只測有效欄位，不會抓到） | 改寫為「G5 跳過方向檢查；G6、G7 照常；預檢第一步回 409 `PAYMENT_STATE_CONFLICT`」 |
| S4 | 建議 | §10.3 `DisputeFreezeIntegrationTest` | PRD-024 AC-2.1 的「撤回 → 409 `ORDER_STATE_CONFLICT`」只對發起者成立；非發起者撤回依 CQ-76 先得 403 `FORBIDDEN` | 必測情境寫明撤回由發起者呼叫，另加一例非發起者 → 403 |
| S5 | 建議 | §1、§10.3 | 對應有幾處空隙：E31 的五種 `bookability` 只在 `BookingContextIntegrationTest`，但 §1 把 PRD-005 AC-1.2、1.3、1.5–1.8 只對到 `BookingIntegrationTest`；PRD-027 AC-2.4 情境 1（重跑 `processed=0`）不在任何類別的必測；PRD-026 AC-1.7 情境 3（`ORDER_BUSY`）不在 `OrderWithdrawConcurrencyIntegrationTest` 必測 | §1 上述列補 `BookingContextIntegrationTest`；`ReviewExpiryCronIntegrationTest` 補重跑；`OrderWithdrawConcurrencyIntegrationTest` 補鎖逾時 |
| S6 | 建議 | §6.5 #29 | `QUESTIONNAIRE_CHANGED` payload 的 `questions[]` 沒有 `sortOrder`；PRD-005 AC-3.6 情境 3 要重新載入問卷並依 PRD-004 排序 | 補 `sortOrder`，與 E31 `questions[]` 欄位一致 |
| S7 | 建議 | §3.2 建立 SUPPLEMENT、§6.3 E23 | `PayoutAccountQuery.getSnapshot` 回 `Optional`，E23 建補款時若為空（例：保母已註銷）行為未定義 | 寫明處理方式（例：409 `ORDER_STATE_CONFLICT` 或允許無快照並在付款頁提示），或列 CQ |
| S8 | 建議 | §11.2 決策 2 | 決定不保留舊路徑，但沒列清單。`openapi.json` 現有 `/api/orders/booking`、`/{id}/payment-proof(/signed-url)`、`/{id}/modification/*`、`/{id}/modify`、`/{id}/admin-resolve`、`/{id}/reject`、`/{id}/cancel`、`/{id}/confirm`、`/{id}/dispute`、`/{id}/verify-payment`、`/{id}/reject-payment`、`/api/internal/cron/test-data/cleanup-seed-orders`、`/api/internal/maintenance/payment-proofs/revoke-public-acl`；`/{id}/quote`、`/{id}/complete`、`/api/orders/sitter/ledger` 同路徑但 request / response 改變 | §5.3 或 §8.3 加「刪除 / 改語意端點」表；`/api/orders/{orderId}/visits` 註明歸 SD-FULFILL、本次不動 |
| S9 | 建議 | §6.1 multipart 上限 | Tomcat `maxSwallowSize` 預設 2 MB；請求超過上限很多（例 20 MB）時 Tomcat 可能直接斷線，客戶端收不到 422（只影響繞過前端直接打 API） | 註明為已知行為，或 `server.tomcat.max-swallow-size` 調到與 `max-request-size` 相同 |
| S10 | 建議 | §6.3 E17 / E20 錯誤欄 | G6 含「新總額 > 999,999」「同方案同日合計 > 3」「變更後日期總數 > 60」，這些要讀訂單快照與目前行程；G6 在冪等認領與取鎖之前，是鎖外讀值。baseline §3.7 註記「依當下資料重算的上限雖回 400，判斷時點在第 12 步」，PRD-016 BR-28 則明列在第 6 步，兩份 Approved 文件不一致 | 依 PRD-016 BR-28 保留在 G6，但寫明鎖內重驗一次；或請主流程在 baseline 註記補上 PRD-016 的例外 |
| S11 | 建議 | §8.1 C6、§3.6.3 粒度 | C6 寫「`SUPPLEMENT_EXPIRED`（O10 另一列）」，但 §3.6.3 規則是「同交易內**操作者不同**才另寫一列」，C6 與 O10 都是 SYSTEM | 擇一：C6 的 O10 併入 `transitions[]`，或把 §3.6.3 規則改為「O10 一律另寫一列」（PRD-023 AC-3.3 要求 O10 的 log 帶 `modificationId` / `initiatedBy`，另寫一列較直觀） |

### 抽查紀錄（每張 PRD ≥ 4 條，含例外與併發）

| PRD AC | 走查結果 |
|--------|---------|
| PRD-005 AC-1.1 | 情境 1 匿名檢視 → E31 被 `SecurityConfig` 擋成 401（#I1）；情境 3 → 401 body 格式不符（#I1） |
| PRD-005 AC-1.8 | 情境 2：E1 業務順序 `SELF_BOOKING_NOT_ALLOWED` 先於 `SITTER_NOT_BOOKABLE` / `SUBSCRIPTION_LOCKED`，E31 `SELF` 先判，通過 |
| PRD-005 AC-2.5 | 情境 4：G5（毛孩 403）先於 G6（`petIds` 空 400），因 DTO 不掛 `@Valid`，通過 |
| PRD-005 AC-3.3 | `PRICE_CHANGED` payload `baseAmount`、400 `field=baseAmount` / `max`，通過 |
| PRD-006 AC-2.6 | 情境 3 E6 vs E8 訂單鎖序列化，後到者 409 `ORDER_STATE_CONFLICT`；情境 4 `ORDER_BUSY` + `Retry-After`，通過 |
| PRD-006 AC-3.3 | 情境 3「加價 100.5」→ Jackson 截斷成 100 回 200（#I4） |
| PRD-006 AC-3.4 | `DEADLINE_PASSED`（`REVIEW_EXPIRES_AT`、`deadlineAt`）先於 `FIRST_VISIT_DATE_PASSED`，邊界 `now == review_expires_at` 回 409，通過 |
| PRD-006 AC-3.7 | `PRICE_CHANGED` payload `baseAmount`、`totalAmount`，通過 |
| PRD-007 AC-1.7 | `SUBMITTED` 且過期 → `PAYMENT_STATE_CONFLICT` 先於 `DEADLINE_PASSED`；`receivedAt` 判斷；鎖後 `VOID` → 409 並刪物件，通過 |
| PRD-007 AC-2.4 | 依 §6.2 通過；依 §2.3.4 失敗（#I5） |
| PRD-007 AC-2.5 | 「保母以 OWNER token 確認 → `ROLE_MISMATCH`」失敗（#B1） |
| PRD-007 AC-3.8 | E14 / E15 vs E16：E16 鎖前授權、鎖後重驗回 `PAYMENT_STATE_CONFLICT`，通過 |
| PRD-016 AC-1.4 | `DISPUTED` + `AWAITING_REFUND` 發起 → `ORDER_STATE_CONFLICT` 先於 `MODIFICATION_IN_PROGRESS`，通過 |
| PRD-016 AC-1.7 | 「S 以 OWNER 呼叫保母發起 → `ROLE_MISMATCH`」失敗（#B1） |
| PRD-016 AC-2.2 | `INVALID_VISIT_DATE`（第 12 步）先於 `PLAN_REQUIRED`（第 13 步），通過 |
| PRD-016 AC-3.6 | E22 vs E20 鎖序列化，後到者 `MODIFICATION_STATE_CONFLICT`，通過 |
| PRD-023 AC-1.4 | `VisitNotPendingException` → 409 `VISIT_ALREADY_STARTED`（payload `visitIds`），整筆不生效，通過 |
| PRD-023 AC-1.7 | E23 為 OWNER 專用，SITTER → `ROLE_MISMATCH`，通過；E21 的 SITTER 情境與 PRD-016 衝突（#I7） |
| PRD-023 AC-1.8 | 回放 + 換 key 409 `MODIFICATION_STATE_CONFLICT`，通過 |
| PRD-023 AC-3.6 | E22 對 SYSTEM 變更 G5 403 先於狀態檢查，通過 |
| PRD-024 AC-1.3 | `disputeAvailableAt` 以目前 submission 起算，通過 |
| PRD-024 AC-1.6 | 雙方同時申報：訂單鎖 + `ux_admin_tasks_open`，後到者 409 `ORDER_STATE_CONFLICT`，通過 |
| PRD-024 AC-2.1 | 撤回情境依呼叫者不同結果不同（#S4） |
| PRD-024 AC-2.4 | `refundDecisions` 缺漏 → 400、整筆不生效；補款作廢通知雙方，通過 |
| PRD-026 AC-1.3 | E8 `PAYMENT_ALREADY_SUBMITTED`，通過 |
| PRD-026 AC-1.5 | E8 不檢查期限；C1 / C3 鎖後重驗略過，通過 |
| PRD-026 AC-1.7 | `expectedStatus` 不符 → `ORDER_STATE_CONFLICT`（`currentStatus`）；E8 vs E7，通過 |
| PRD-026 AC-1.8 | 缺 `expectedStatus` → 400 `field=expectedStatus`（validator 判 null），通過；`expectedStatus` 若宣告為 enum，不合法值會在進方法前回 400（#I4） |
| PRD-027 AC-1.3 | 錯誤順序與 `currentStatus`，通過 |
| PRD-027 AC-2.4 | 情境 2 依 §8.3 D3 改 `InternalSecretFilter` 回 403，通過（現況回 401） |
| PRD-027 AC-3.5 | E9 vs E11：上傳先 → `PAYMENT_ALREADY_SUBMITTED`；取消先 → 上傳鎖後重驗 409 + 刪物件，通過 |

### 待使用者回答

| CQ | 問題 | 建議預設值 |
|----|------|-----------|
| CQ-40 | 混合角色端點（E2、E10–E15、E17–E19、E21、E22、E24）中，「呼叫者是訂單當事人但 `currentRole` 不是其當事人角色」要在哪一步回 `ROLE_MISMATCH`？（PRD-007 AC-2.5、PRD-016 AC-1.7 要求 `ROLE_MISMATCH`，但要先載入訂單才能判斷，晚於 baseline 第 4 步 404） | 放在 G4（404）之後、G5（`FORBIDDEN`）之前：當事人錯角色 → 403 `ROLE_MISMATCH`；非當事人 → 403 `FORBIDDEN`。不存在的訂單一律 404。ADMIN 呼叫寫入端點仍由 `@PreAuthorize` 在 G3 擋 |
| CQ-41 | SITTER 對 OWNER 發起的 `QUOTED` 變更呼叫拒絕（E21）：PRD-016 AC-2.6 要 409 `MODIFICATION_STATE_CONFLICT`，PRD-023 §3 / EX-2 寫 403 `ROLE_MISMATCH`，以哪個為準？ | 409 `MODIFICATION_STATE_CONFLICT`（PRD-016 AC-2.6，E21 是雙方共用端點）；由主流程把 PRD-023 §3 表與 EX-2 的 `ROLE_MISMATCH` 限縮為「同意」端點（E23） |
| CQ-42 | `GlobalExceptionHandler` 的新對映與 §6.1 錯誤格式要全域生效，還是只套訂單 controller？（全域會讓其他模組的 `@PreAuthorize` 拒絕改回 `ROLE_MISMATCH`、樂觀鎖改回 `ORDER_BUSY`、所有上傳超限改回 `PROOF_FILE_TOO_LARGE`） | `ROLE_MISMATCH`、401 `UNAUTHENTICATED` 與 §6.1 錯誤格式全域採用（baseline §3.7 本就是跨模組規則），其他模組受影響的測試列入 §8.3；`ORDER_BUSY`（`@Version`）、`PROOF_FILE_TOO_LARGE`、`STORAGE_UNAVAILABLE`、約束名對映只套訂單 controller（獨立 advice + `basePackageClasses` + 最高優先序），multipart 上限在 E11 自行檢查 |

### 跨 SD 衝突 / 依賴

- **SD-GLOBAL-SPEC**：§3.4 規定歸屬不符拋 `AccessDeniedException` → 403；本 SD 改用 `OrderException(FORBIDDEN)` 並新增 `ROLE_MISMATCH` 對映，加上新錯誤格式 `{ code, message, payload, traceId, timestamp }` 與 `authenticationEntryPoint` 改寫（#I1、#I2），都需回寫 GLOBAL。`@RequirePlan` 偏離已在 §11.2 決策 5 自行揭露。
- **SD-FULFILL**：PRD-023 AC-1.5、PRD-024 AC-2.2 的併發 / 凍結測試需要 SD-FULFILL 的「開始行程」經 `OrderLockService`；E2E 造行程狀態也依賴 SD-FULFILL（#I8）。
- **SD-SUPPLY / SD-CARE / SD-IDENTITY / SD-TRUST / SD-BILLING**：§10.4 的 provision 要透過它們的 Service 寫入門禁名單、公開狀態、KYC 狀態、停權、訂閱到期、毛孩、我的最愛（#I8），各 SD 需提供可呼叫的寫入方法。
- **PRD-023 ↔ PRD-016**：E21 SITTER 拒絕 `QUOTED` 的錯誤碼互相矛盾（CQ-41）。
- **DOMAIN-BASELINE §3.7 註記 ↔ PRD-016 BR-28**：依當下資料重算的 400 判斷時點不一致（#S10）。
