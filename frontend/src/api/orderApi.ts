import axiosClient from './axiosClient';
import { postIdempotent } from './idempotency';

/**
 * 訂單交易 API（SD-ORDER §6.3，E1–E31）。型別對照後端 DTO（backend application/dto/order）。
 * 回應中值為 null 的欄位不輸出（§6.1），以可選欄位表示。所有寫入端點皆為冪等（`Idempotency-Key`），
 * key 由呼叫端在使用者按下送出時產生並傳入，同一次送出的重試沿用同一把。
 */

export type OrderStatus =
  | 'PENDING'
  | 'PENDING_PAYMENT'
  | 'CONFIRMED'
  | 'IN_PROGRESS'
  | 'DISPUTED'
  | 'COMPLETED'
  | 'CANCELLED';

export type Party = 'OWNER' | 'SITTER' | 'ADMIN' | 'SYSTEM';

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
}

/** E3 / E4 回應：分頁 + `serverMonth`（Asia/Taipei 當下月份 `YYYY-MM`，SD-ORDER E3） */
export interface OrderListResponse extends Page<OrderCardDto> {
  serverMonth: string;
}

// ---------- E31 booking-context ----------
export type Bookability = 'BOOKABLE' | 'SELF' | 'NOT_VERIFIED' | 'UNAVAILABLE' | 'PAUSED';

export interface BookingPlan {
  planId: string;
  title: string;
  unitPrice: number;
  petTypes?: string[];
  bookableFrom?: string;
  bookableTo?: string;
}

export interface BookingQuestion {
  questionId: string;
  text: string;
  type: string;
  options?: string[];
  required: boolean;
  sortOrder: number;
}

export interface BookingQuestionnaire {
  required: boolean;
  exemptReason?: string;
  questions?: BookingQuestion[];
}

export interface BookingContext {
  sitterId: string;
  bookability: Bookability;
  plans?: BookingPlan[];
  questionnaire?: BookingQuestionnaire;
  serverTime: string;
}

// ---------- E1 ----------
export interface CreateOrderGroup {
  planId: string;
  dates: string[];
  dailyCount: number;
  petIds: string[];
}

export interface CreateOrderAnswer {
  questionId: string;
  value: string | string[];
}

export interface CreateOrderRequest {
  sitterId: string;
  groups: CreateOrderGroup[];
  answers?: CreateOrderAnswer[];
  expectedAmount: number;
}

export interface CreateOrderResponse {
  orderId: string;
  status: OrderStatus;
  baseAmount: number;
  reviewExpiresAt: string;
  questionnaireStatus: string;
}

// ---------- E2 ----------
export interface QuoteOriginal {
  baseAmount: number;
  surchargeAmount?: number;
  discountAmount?: number;
  pricingNotes?: string;
  totalAmount?: number;
  quotedAt?: string;
}

export interface QuoteAdjustment {
  modificationId: string;
  kind: 'SETTLED' | 'SUPPLEMENT_EXPIRED' | string;
  type?: string;
  effectiveAt?: string;
  removedVisitDates?: string[];
  addedItems?: { date: string; planTitle: string; dailyCount: number }[];
  totalBefore: number;
  totalAfter: number;
  amountChange: number;
}

export interface OrderQuote {
  original: QuoteOriginal;
  adjustments?: QuoteAdjustment[];
  totalAmount?: number;
  quotedAt?: string;
}

export interface OrderPet {
  petId: string;
  name: string;
  type?: string;
  deleted: boolean;
}

export interface OrderGroup {
  orderItemId: string;
  source?: string;
  planId: string;
  planTitle: string;
  unitPrice?: number;
  bookableFrom?: string;
  bookableTo?: string;
  dates: string[];
  dailyCount: number;
  pets: OrderPet[];
}

export interface OrderVisitItem {
  visitId: string;
  serviceDate: string;
  planId: string;
  status: string;
  completedAt?: string;
  careLogSubmitted: boolean;
}

export type OrderAllowedAction =
  | 'WITHDRAW'
  | 'DECLINE'
  | 'QUOTE'
  | 'SITTER_CANCEL'
  | 'REQUEST_MODIFICATION'
  | 'COMPLETE'
  | 'REPORT_SERVICE_ISSUE'
  | 'PAYMENT_DISPUTE'
  | 'VOID_INITIAL_PAYMENT'
  | 'MEDIATE';

export interface AccountSnapshot {
  bankCode?: string;
  branch?: string;
  accountNumber?: string;
  accountName?: string;
}

export type PaymentAllowedAction = 'UPLOAD' | 'VERIFY' | 'REJECT';

export interface PaymentView {
  paymentId: string;
  direction: 'CHARGE' | 'REFUND';
  purpose: string;
  method?: string;
  amount: number;
  status: 'UNPAID' | 'SUBMITTED' | 'VERIFIED' | 'VOID' | string;
  dueAt?: string;
  escalateAt?: string;
  accountSnapshot?: AccountSnapshot;
  currentSubmissionId?: string;
  createdAt?: string;
  verifiedAt?: string;
  voidedAt?: string;
  allowedActions?: PaymentAllowedAction[];
}

export interface OpenModification {
  modificationId: string;
  status: string;
  initiatedBy: Party;
}

export interface Cancellation {
  cancelledBy?: Party;
  reasonCode?: string;
  reason?: string;
  cancelledAt?: string;
}

export interface CompletionHint {
  code:
    | 'MODIFICATION_IN_PROGRESS'
    | 'VISITS_NOT_FINISHED'
    | 'CARE_LOG_EXPIRED'
    | 'CARE_LOG_PENDING';
  autoCompleteAt?: string;
}

export interface ModificationSummary {
  modificationId: string;
  type: string;
  status: string;
  initiatedBy: Party;
  reason?: string;
  newTotalAmount?: number;
  createdAt: string;
  endedAt?: string;
}

export interface AdminTaskRef {
  taskId: string;
  type: string;
  status: string;
  resolution?: string;
  createdAt: string;
}

export interface OrderLogItem {
  actionType: string;
  operatorRole?: string;
  operatorUserId?: string;
  payload?: unknown;
  createdAt: string;
}

export interface OrderDetail {
  orderId: string;
  status: OrderStatus;
  role: 'OWNER' | 'SITTER' | 'ADMIN';
  counterpart?: { userId: string; displayName: string };
  firstServiceDate?: string;
  lastServiceDate?: string;
  reviewExpiresAt?: string;
  baseAmount: number;
  quote?: OrderQuote;
  sitterTier?: string;
  modificationPricingMode?: 'SYSTEM_ONLY' | 'CUSTOM_ALLOWED';
  groups?: OrderGroup[];
  visits?: OrderVisitItem[];
  questionnaireStatus?: string;
  questionnaireExemptReason?: string;
  payments?: PaymentView[];
  openModification?: OpenModification;
  cancellation?: Cancellation;
  completedAt?: string;
  disputeWindowEndsAt?: string;
  netPaid?: number;
  serverTime: string;
  completionHint?: CompletionHint;
  allowedActions?: OrderAllowedAction[];
  // E27 only
  modifications?: ModificationSummary[];
  adminTasks?: AdminTaskRef[];
  logs?: OrderLogItem[];
}

// ---------- E3 / E4 ----------
export type OrderTab = 'EVALUATING' | 'ACTIVE' | 'HISTORY';

export interface OrderCardDto {
  orderId: string;
  status: OrderStatus;
  counterpartName?: string;
  firstServiceDate?: string;
  lastServiceDate?: string;
  baseAmount: number;
  totalAmount?: number;
  hasOpenModification: boolean;
  initialPaymentStatus?: string;
  createdAt: string;
  terminalAt?: string;
  netPaid?: number;
}

export interface OrderListParams {
  tab: OrderTab;
  status?: 'COMPLETED' | 'CANCELLED';
  month?: string;
  page?: number;
  size?: number;
}

// ---------- E5 ----------
export interface EvaluationAnswer {
  questionId: string;
  questionText: string;
  questionType: string;
  answer?: string | string[] | null;
}

export interface EvaluationPrecheck {
  subscriptionLocked: boolean;
  quotaReached: boolean;
  payoutAccountMissing: boolean;
  reviewExpired: boolean;
  planAllowsAdjustment: boolean;
}

export interface Evaluation {
  orderId: string;
  status: OrderStatus;
  reviewExpiresAt?: string;
  baseAmount: number;
  owner?: { displayName?: string };
  isRegularOwner: boolean;
  groups?: OrderGroup[];
  questionnaire?: { status?: string; exemptReason?: string; answers?: EvaluationAnswer[] };
  precheck: EvaluationPrecheck;
  serverTime: string;
}

// ---------- E6–E9 ----------
export interface QuoteRequest {
  expectedAmount: number;
  surchargeAmount?: number;
  discountAmount?: number;
  pricingNotes?: string;
}

export interface QuoteResponse {
  orderId: string;
  status: OrderStatus;
  baseAmount: number;
  surchargeAmount: number;
  discountAmount: number;
  totalAmount: number;
  quotedAt: string;
  payment?: { paymentId: string; amount: number; dueAt?: string; escalateAt?: string };
}

export interface CancelledResponse {
  orderId: string;
  status: OrderStatus;
  cancelledBy: Party;
}

// ---------- E11–E15 ----------
export interface SubmissionItem {
  submissionId: string;
  status: 'SUBMITTED' | 'ACCEPTED' | 'REJECTED' | string;
  lastFive: string;
  submittedAt: string;
  disclaimerAgreedAt?: string;
  reviewedAt?: string;
  verifiedAt?: string;
  rejectReason?: string;
  voided: boolean;
}

export interface SubmittedResponse {
  submissionId: string;
  paymentId: string;
  paymentStatus: string;
  submittedAt: string;
}

export interface ProofUrl {
  url: string;
  expiresAt: string;
}

export interface VerifiedResponse {
  paymentId: string;
  paymentStatus: string;
  orderStatus: OrderStatus;
  modification?: { modificationId: string; status: string };
}

export interface RejectedResponse {
  paymentId: string;
  paymentStatus: string;
  submissionId: string;
  submissionStatus: string;
}

// ---------- E17–E24 ----------
export interface ModificationAddItem {
  date: string;
  planId: string;
  dailyCount: number;
  petIds: string[];
}

export interface InitiateModificationRequest {
  type: 'CHANGE' | 'CANCEL_ALL';
  reason: string;
  removeVisitIds?: string[];
  addItems?: ModificationAddItem[];
  surchargeAmount?: number;
  discountAmount?: number;
  adjustmentReason?: string;
}

export interface InitiatedModification {
  modificationId: string;
  status: string;
  initiatedBy: Party;
  expiresAt?: string;
  systemAmount?: number;
  newTotalAmount?: number;
  netPaid?: number;
  diff?: number;
}

export interface QuoteModificationRequest {
  removeVisitIds?: string[];
  addItems?: ModificationAddItem[];
  surchargeAmount?: number;
  discountAmount?: number;
  adjustmentReason?: string;
}

// ---------- E32 / E33 變更金額試算（唯讀，SD-ORDER §6.3） ----------
/** E32 body 同 E17；OWNER 呼叫時 `reason` 不驗證（CQ-51），可省略 */
export type PreviewModificationRequest = Omit<InitiateModificationRequest, 'reason'> & {
  reason?: string;
};

export interface ModificationPreview {
  type: 'CHANGE' | 'CANCEL_ALL';
  removeVisitIds: string[];
  breakdown: {
    kept: {
      visitId: string;
      planId: string;
      serviceDate: string;
      status: string;
      unitPrice: number;
    }[];
    added: {
      index: number;
      date: string;
      planId: string;
      dailyCount: number;
      unitPrice: number;
      subtotal: number;
    }[];
  };
  systemAmount: number;
  surchargeAmount: number;
  discountAmount: number;
  newTotalAmount: number;
  currentTotalAmount: number;
  netPaid: number;
  diff: number;
  /** 只在 SITTER 呼叫時回傳 */
  pricingMode?: 'SYSTEM_ONLY' | 'CUSTOM_ALLOWED';
  computedAt: string;
}

export interface ModificationContent {
  removeVisitIds?: string[];
  addItems?: ModificationAddItem[];
}

export type ModificationAllowedAction =
  | 'QUOTE'
  | 'REJECT'
  | 'WITHDRAW'
  | 'ACCEPT'
  | 'PAYMENT_DISPUTE';

export interface ModificationPaymentRef {
  paymentId: string;
  direction: 'CHARGE' | 'REFUND';
  purpose: string;
  amount: number;
  status?: string;
  dueAt?: string;
}

export interface ModificationDetail {
  modificationId: string;
  type: 'CHANGE' | 'CANCEL_ALL' | string;
  status: string;
  initiatedBy: Party;
  reason?: string;
  requestedContent?: ModificationContent;
  quotedContent?: ModificationContent;
  systemAmount?: number;
  surchargeAmount?: number;
  discountAmount?: number;
  adjustmentReason?: string;
  newTotalAmount?: number;
  netPaid?: number;
  diff?: number;
  expiresAt?: string;
  endedAt?: string;
  rejectReason?: string;
  sourceModificationId?: string;
  payment?: ModificationPaymentRef;
  disputeAvailableAt?: string;
  allowedActions?: ModificationAllowedAction[];
}

export interface EndedModification {
  modificationId: string;
  status: string;
  endedAt: string;
}

export interface AcceptedModification {
  modificationId: string;
  status: string;
  netPaid: number;
  diff: number;
  payment?: ModificationPaymentRef;
  orderStatus: OrderStatus;
  totalAmount?: number;
}

export interface DisputedResponse {
  orderId: string;
  status: OrderStatus;
  adminTaskId: string;
}

export interface CompletedResponse {
  orderId: string;
  status: OrderStatus;
  completedAt: string;
}

// ---------- E30 ----------
export interface LedgerOrder {
  orderId: string;
  status: OrderStatus;
  ownerDisplayName?: string;
  firstServiceDate?: string;
  lastServiceDate?: string;
  terminalAt?: string;
  netPaid: number;
}

export interface Ledger {
  month: string;
  serverMonth: string;
  totalIncome: number;
  orders: LedgerOrder[];
}

// ---------- E16 / E27–E29 ----------
export interface AdminTaskItem {
  taskId: string;
  type: string;
  orderId: string;
  paymentId?: string;
  status: string;
  createdAt: string;
}

export interface MediationRequest {
  refundDecisions?: { paymentId: string; exported: boolean }[];
  adjustment?: { amount: number; basis: string };
}

// ======================= 呼叫 =======================
const o = (orderId: string) => `/orders/${encodeURIComponent(orderId)}`;

export const getBookingContext = async (sitterId: string): Promise<BookingContext> =>
  (await axiosClient.get(`/sitters/${encodeURIComponent(sitterId)}/booking-context`)).data;

export const createOrder = (body: CreateOrderRequest, key: string) =>
  postIdempotent<CreateOrderResponse>('/orders', key, body);

export const getOrderDetail = async (orderId: string): Promise<OrderDetail> =>
  (await axiosClient.get(o(orderId))).data;

export const listOwnerOrders = async (params: OrderListParams): Promise<OrderListResponse> =>
  (await axiosClient.get('/orders/owner', { params })).data;

export const listSitterOrders = async (params: OrderListParams): Promise<OrderListResponse> =>
  (await axiosClient.get('/orders/sitter', { params })).data;

export const getEvaluation = async (orderId: string): Promise<Evaluation> =>
  (await axiosClient.get(`${o(orderId)}/evaluation`)).data;

export const quoteOrder = (orderId: string, body: QuoteRequest, key: string) =>
  postIdempotent<QuoteResponse>(`${o(orderId)}/quote`, key, body);

export const declineOrder = (orderId: string, reason: string, key: string) =>
  postIdempotent<CancelledResponse>(`${o(orderId)}/decline`, key, { reason });

export const withdrawOrder = (
  orderId: string,
  body: { expectedStatus: OrderStatus; reason?: string },
  key: string
) => postIdempotent<CancelledResponse>(`${o(orderId)}/withdraw`, key, body);

export const sitterCancelOrder = (orderId: string, reason: string, key: string) =>
  postIdempotent<CancelledResponse>(`${o(orderId)}/sitter-cancel`, key, { reason });

// 付款
export const listPayments = async (orderId: string): Promise<PaymentView[]> =>
  (await axiosClient.get(`${o(orderId)}/payments`)).data;

export const submitPaymentProof = (
  orderId: string,
  paymentId: string,
  body: { lastFive: string; file: Blob; fileName: string },
  key: string
) => {
  const form = new FormData();
  form.append('disclaimerAgreed', 'true');
  form.append('lastFive', body.lastFive);
  form.append('file', body.file, body.fileName);
  return postIdempotent<SubmittedResponse>(
    `${o(orderId)}/payments/${encodeURIComponent(paymentId)}/submissions`,
    key,
    form
  );
};

export const listSubmissions = async (
  orderId: string,
  paymentId: string
): Promise<SubmissionItem[]> =>
  (await axiosClient.get(`${o(orderId)}/payments/${encodeURIComponent(paymentId)}/submissions`))
    .data;

export const getProofUrl = async (
  orderId: string,
  paymentId: string,
  submissionId: string
): Promise<ProofUrl> =>
  (
    await axiosClient.get(
      `${o(orderId)}/payments/${encodeURIComponent(paymentId)}/submissions/${encodeURIComponent(
        submissionId
      )}/proof-url`
    )
  ).data;

export const verifyPayment = (
  orderId: string,
  paymentId: string,
  submissionId: string,
  key: string
) =>
  postIdempotent<VerifiedResponse>(
    `${o(orderId)}/payments/${encodeURIComponent(paymentId)}/verify`,
    key,
    { submissionId }
  );

export const rejectPayment = (
  orderId: string,
  paymentId: string,
  body: { submissionId: string; reason: string },
  key: string
) =>
  postIdempotent<RejectedResponse>(
    `${o(orderId)}/payments/${encodeURIComponent(paymentId)}/reject`,
    key,
    body
  );

// 變更
const m = (orderId: string, modificationId: string) =>
  `${o(orderId)}/modifications/${encodeURIComponent(modificationId)}`;

export const initiateModification = (
  orderId: string,
  body: InitiateModificationRequest,
  key: string
) => postIdempotent<InitiatedModification>(`${o(orderId)}/modifications`, key, body);

export const listModifications = async (orderId: string): Promise<ModificationSummary[]> =>
  (await axiosClient.get(`${o(orderId)}/modifications`)).data;

export const getModification = async (
  orderId: string,
  modificationId: string
): Promise<ModificationDetail> => (await axiosClient.get(m(orderId, modificationId))).data;

export const quoteModification = (
  orderId: string,
  modificationId: string,
  body: QuoteModificationRequest,
  key: string
) => postIdempotent<InitiatedModification>(`${m(orderId, modificationId)}/quote`, key, body);

/** E32 變更精靈試算（不冪等、不取鎖） */
export const previewModification = async (
  orderId: string,
  body: PreviewModificationRequest,
  signal?: AbortSignal
): Promise<ModificationPreview> =>
  (await axiosClient.post(`${o(orderId)}/modifications/preview`, body, { signal })).data;

/** E33 保母報價表單試算（不冪等、不取鎖） */
export const previewModificationQuote = async (
  orderId: string,
  modificationId: string,
  body: QuoteModificationRequest,
  signal?: AbortSignal
): Promise<ModificationPreview> =>
  (await axiosClient.post(`${m(orderId, modificationId)}/quote/preview`, body, { signal })).data;

export const rejectModification = (
  orderId: string,
  modificationId: string,
  reason: string | undefined,
  key: string
) =>
  postIdempotent<EndedModification>(
    `${m(orderId, modificationId)}/reject`,
    key,
    reason === undefined ? {} : { reason }
  );

export const withdrawModification = (orderId: string, modificationId: string, key: string) =>
  postIdempotent<EndedModification>(`${m(orderId, modificationId)}/withdraw`, key);

export const acceptModification = (orderId: string, modificationId: string, key: string) =>
  postIdempotent<AcceptedModification>(`${m(orderId, modificationId)}/accept`, key);

// 爭議 / 結案
export const openPaymentDispute = (orderId: string, description: string, key: string) =>
  postIdempotent<DisputedResponse>(`${o(orderId)}/payment-disputes`, key, { description });

export const completeOrder = (orderId: string, key: string) =>
  postIdempotent<CompletedResponse>(`${o(orderId)}/complete`, key);

export const openServiceDispute = (orderId: string, description: string, key: string) =>
  postIdempotent<DisputedResponse>(`${o(orderId)}/service-disputes`, key, { description });

// 帳務
export const getSitterLedger = async (month: string): Promise<Ledger> =>
  (await axiosClient.get('/orders/sitter/ledger', { params: { month } })).data;

// ADMIN
export const getAdminOrder = async (orderId: string): Promise<OrderDetail> =>
  (await axiosClient.get(`/admin/orders/${encodeURIComponent(orderId)}`)).data;

export const listAdminTasks = async (params: {
  status?: string;
  type?: string;
  page?: number;
  size?: number;
}): Promise<Page<AdminTaskItem>> => (await axiosClient.get('/admin/tasks', { params })).data;

export const adminVoidPayment = (orderId: string, paymentId: string, reason: string, key: string) =>
  postIdempotent<{
    orderId: string;
    status: OrderStatus;
    cancelledBy: Party;
    paymentStatus: string;
  }>(
    `/admin/orders/${encodeURIComponent(orderId)}/payments/${encodeURIComponent(paymentId)}/void`,
    key,
    { reason }
  );

export const adminMediate = (orderId: string, body: MediationRequest, key: string) =>
  postIdempotent<{ orderId: string; status: OrderStatus; adjustmentPaymentIds: string[] }>(
    `/admin/orders/${encodeURIComponent(orderId)}/mediation`,
    key,
    body
  );
