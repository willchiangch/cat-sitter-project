import axiosClient from './axiosClient';

export interface BookingItem {
  planId: string;
  dates: string[];
  timesPerDay: number;
  petIds?: string[];
}

export interface BookingQuestionnaireAnswer {
  questionId?: string;
  answerValues: string[];
}

export interface BookingRequest {
  sitterId: string;
  ownerId: string;
  items: BookingItem[];
  answers?: BookingQuestionnaireAnswer[];
  expectedTotalAmount?: number;
  idempotencyKey?: string;
}

// 對齊後端 OrderItem 欄位，發起變更時的 items 必須是完整的 OrderItem 形狀，
// 否則後端 Jackson 反序列化會靜默丟棄未知欄位，訂單內容會被清空
export interface OrderItemDto {
  category: string;
  serviceName: string;
  unitPrice: number;
  quantity: number;
  planId: string;
  dates: string[];
  timesPerDay: number;
  petIds?: string[];
}

export interface ModificationPayloadDto {
  items: OrderItemDto[];
  totalDays: number;
  dates: string[]; // YYYY-MM-DD
}

export interface ModificationRequestDetailDto {
  id: string;
  orderId: string;
  status: string;
  requestedBy: string;
  diffAmount: number;
  newTotalAmount: number;
  currentOrderTotalAmount: number;
  orderVersion: number;
  dates: string[];
  refundProofUrl?: string;
}

export interface ModificationQuoteRequest {
  newTotalAmount: number;
  version: number;
}

export interface AdminResolveRequest {
  finalAmount: number;
  receiptUrl?: string;
  reason: string;
  adminPassword: string;
}

// 1. 飼主送出預約申請
export const createBooking = async (
  request: BookingRequest,
  idempotencyKey: string
): Promise<{ orderId: string }> => {
  const response = await axiosClient.post('/orders/booking', request, {
    headers: { 'Idempotency-Key': idempotencyKey }
  });
  return response.data;
};

// 2. 發起變更請求 (SD-016)
export const modifyOrder = async (
  orderId: string,
  requestedBy: 'OWNER' | 'SITTER',
  request: ModificationPayloadDto,
  idempotencyKey: string
): Promise<{ status: string; message: string }> => {
  const response = await axiosClient.post(`/orders/${orderId}/modify`, request, {
    params: { requestedBy },
    headers: { 'Idempotency-Key': idempotencyKey }
  });
  return response.data;
};

// 3. 確認同意變更 (SD-016)
export const confirmModification = async (
  orderId: string,
  modRequestId: string,
  request: { agreedDiffAmount: number; version: number },
  idempotencyKey: string
): Promise<{ status: string; message: string }> => {
  const response = await axiosClient.post(`/orders/${orderId}/modification/confirm`, request, {
    params: { modRequestId },
    headers: { 'Idempotency-Key': idempotencyKey }
  });
  return response.data;
};

// 3b. 查詢訂單目前進行中的變更請求 (PRD-016)
export const getActiveModificationRequest = async (
  orderId: string
): Promise<ModificationRequestDetailDto> => {
  const response = await axiosClient.get(`/orders/${orderId}/modification`);
  return response.data;
};

// 3c. 保母審核變更並提供差額報價 (PRD-016 主流程 B)
export const quoteModification = async (
  orderId: string,
  modRequestId: string,
  request: ModificationQuoteRequest,
  idempotencyKey: string
): Promise<{ status: string; message: string }> => {
  const response = await axiosClient.post(`/orders/${orderId}/modification/quote`, request, {
    params: { modRequestId },
    headers: { 'Idempotency-Key': idempotencyKey }
  });
  return response.data;
};

// 3d. 保母拒絕變更請求 (PRD-016 主流程 B.3)
export const rejectModification = async (
  orderId: string,
  modRequestId: string,
  idempotencyKey: string
): Promise<{ status: string; message: string }> => {
  const response = await axiosClient.post(
    `/orders/${orderId}/modification/reject`,
    {},
    { params: { modRequestId }, headers: { 'Idempotency-Key': idempotencyKey } }
  );
  return response.data;
};

// 4. 保母確認接單
export const confirmOrder = async (
  orderId: string,
  sitterId: string
): Promise<{ status: string; message: string }> => {
  const response = await axiosClient.post(`/orders/${orderId}/confirm`, null, {
    params: { sitterId }
  });
  return response.data;
};

// 5-1. 保母報價評估頁 (OrderEvalView) 用的真實請求格式，對應後端 QuoteRequest DTO
export interface SendQuoteRequest {
  adjustmentAmount: number;
  expectedTotalAmount: number;
  adjustmentReason?: string;
  version: number;
}

export const sendOrderQuote = async (
  orderId: string,
  sitterId: string,
  request: SendQuoteRequest,
  idempotencyKey: string
): Promise<{ status: string; message: string }> => {
  const response = await axiosClient.post(`/orders/${orderId}/quote`, request, {
    params: { sitterId },
    headers: { 'Idempotency-Key': idempotencyKey }
  });
  return response.data;
};

// 5-2. 保母拒絕接單 (PRD-006 AC-4 / SD-006 §2.3)
export interface RejectOrderRequest {
  rejectReason?: string;
  version: number;
}

export const rejectOrder = async (
  orderId: string,
  sitterId: string,
  request: RejectOrderRequest,
  idempotencyKey: string
): Promise<{ status: string; message: string }> => {
  const response = await axiosClient.post(`/orders/${orderId}/reject`, request, {
    params: { sitterId },
    headers: { 'Idempotency-Key': idempotencyKey }
  });
  return response.data;
};

// 6. 飼主手動結案 (SD-009)
export const completeOrder = async (
  orderId: string,
  ownerId: string
): Promise<{ status: string; message: string }> => {
  const response = await axiosClient.post(`/orders/${orderId}/complete`, null, {
    params: { ownerId }
  });
  return response.data;
};

// 7. 飼主回報爭議 (SD-009)
export const disputeOrder = async (
  orderId: string,
  ownerId: string,
  category: string,
  description: string
): Promise<{ status: string; message: string }> => {
  const response = await axiosClient.post(
    `/orders/${orderId}/dispute`,
    { category, description },
    {
      params: { ownerId }
    }
  );
  return response.data;
};

// 8. 管理員強制結案 (Admin Resolve - SD-009)
export const resolveDisputedOrder = async (
  orderId: string,
  request: AdminResolveRequest
): Promise<{ status: string; message: string }> => {
  const response = await axiosClient.post(`/orders/${orderId}/admin-resolve`, request);
  return response.data;
};

// 9. 保母上傳退款憑證 (SD-016)
export const uploadRefundProof = async (
  orderId: string,
  sitterId: string,
  refundProofUrl: string
): Promise<{ status: string; message: string }> => {
  const response = await axiosClient.post(
    `/orders/${orderId}/modification/refund-proof`,
    { refundProofUrl },
    {
      params: { sitterId }
    }
  );
  return response.data;
};

// 10. 飼主確認收到退款 (SD-016)
export const confirmRefund = async (
  orderId: string,
  ownerId: string
): Promise<{ status: string; message: string }> => {
  const response = await axiosClient.post(`/orders/${orderId}/modification/refund-confirm`, null, {
    params: { ownerId }
  });
  return response.data;
};

// --- SD-007 Offline Payment Types & APIs ---

export interface BankAccountInfo {
  bankCode?: string;
  bankBranch?: string;
  bankAccount?: string;
  bankPayeeName?: string;
}

export interface OrderDetailResponseDto {
  id: string;
  ownerId: string;
  ownerName?: string;
  sitterId: string;
  status: string;
  totalAmount: number;
  version?: number;
  adjustmentAmount: number;
  adjustmentReason?: string;
  paymentProofUrl?: string;
  paymentProofLastFive?: string;
  disclaimerAgreed: boolean;
  disclaimerAgreedAt?: string;
  paidAt?: string;
  completedAt?: string;
  payoutAt?: string;
  items: OrderItemDto[];
  sitterPaymentInfo?: BankAccountInfo;
}

// 取得訂單詳情 (SD-007)
export const getOrderDetail = async (orderId: string): Promise<OrderDetailResponseDto> => {
  const response = await axiosClient.get(`/orders/${orderId}`);
  return response.data;
};

export interface OrderSummaryDto {
  id: string;
  ownerId: string;
  ownerName: string;
  sitterId: string;
  sitterName: string;
  status: string;
  totalAmount: number;
  paymentProofUrl?: string;
  paymentProofLastFive?: string;
  scheduledDatesLabel: string;
}

// 飼主查詢自己的訂單清單
export const getMyOrdersAsOwner = async (): Promise<OrderSummaryDto[]> => {
  const response = await axiosClient.get('/orders/owner');
  return response.data;
};

// 保母查詢自己的訂單清單
export const getMyOrdersAsSitter = async (): Promise<OrderSummaryDto[]> => {
  const response = await axiosClient.get('/orders/sitter');
  return response.data;
};

export interface OrderLedgerEntryDto {
  orderId: string;
  ownerName: string;
  totalAmount: number;
  paidAt?: string;
  completedAt?: string;
  payoutAt?: string;
}

export interface SitterLedgerResponse {
  yearMonth: string;
  totalRevenue: number;
  entries: OrderLedgerEntryDto[];
}

// 保母帳務總覽 (PRD-009 主流程 C)，month 格式為 YYYY-MM，不帶則預設當月
export const getSitterLedger = async (month?: string): Promise<SitterLedgerResponse> => {
  const response = await axiosClient.get('/orders/sitter/ledger', { params: month ? { month } : {} });
  return response.data;
};

// 提交付款憑證 (SD-007)
export const submitPaymentProof = async (
  orderId: string,
  lastFive: string,
  disclaimerAgreed: boolean,
  file: File,
  idempotencyKey: string
): Promise<{ status: string; message: string }> => {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('lastFive', lastFive);
  formData.append('disclaimerAgreed', String(disclaimerAgreed));

  const response = await axiosClient.post(`/orders/${orderId}/payment-proof`, formData, {
    headers: {
      'Content-Type': 'multipart/form-data',
      'Idempotency-Key': idempotencyKey
    }
  });
  return response.data;
};

// 保母確認入帳 (SD-007)
export const verifyPayment = async (
  orderId: string
): Promise<{ status: string; message: string }> => {
  const response = await axiosClient.post(`/orders/${orderId}/verify-payment`);
  return response.data;
};

// 保母駁回付款憑證 (SD-007)
export const rejectPayment = async (
  orderId: string,
  rejectReason: string
): Promise<{ status: string; message: string }> => {
  const response = await axiosClient.post(`/orders/${orderId}/reject-payment`, { rejectReason });
  return response.data;
};

// 保母取得個人收款資訊 (SD-007)
export const getSitterPaymentInfo = async (): Promise<BankAccountInfo> => {
  const response = await axiosClient.get('/sitter/payment-info');
  return response.data;
};

// 保母更新個人收款資訊 (SD-007)
export const updateSitterPaymentInfo = async (
  info: BankAccountInfo
): Promise<{ status: string; message: string }> => {
  const response = await axiosClient.put('/sitter/payment-info', info);
  return response.data;
};
