import axiosClient from './axiosClient';

export interface BookingItem {
  planId: string;
  dates: string[];
  timesPerDay: number;
  petIds?: string[];
}

export interface BookingRequest {
  sitterId: string;
  ownerId: string;
  items: BookingItem[];
  idempotencyKey?: string;
}

export interface ModificationItem {
  servicePlanId: string;
  scheduledDate: string; // YYYY-MM-DD
}

export interface ModificationPayloadDto {
  items: ModificationItem[];
  totalDays: number;
  dates: string[]; // YYYY-MM-DD
}

export interface QuoteRequest {
  adjustedAmount: number;
  notes?: string;
  sitterPasswordHash?: string;
}

export interface AdminResolveRequest {
  finalAmount: number;
  receiptUrl?: string;
  reason: string;
  adminPasswordHash?: string;
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
  request: ModificationPayloadDto,
  idempotencyKey: string
): Promise<{ status: string; message: string }> => {
  const response = await axiosClient.post(`/orders/${orderId}/modification/confirm`, request, {
    params: { modRequestId },
    headers: { 'Idempotency-Key': idempotencyKey }
  });
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

// 5. 保母送出報價與調價 (SD-006)
export const sendQuote = async (
  orderId: string,
  sitterId: string,
  request: QuoteRequest
): Promise<{ status: string; message: string }> => {
  const response = await axiosClient.post(`/orders/${orderId}/quote`, request, {
    params: { sitterId }
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
  sitterId: string;
  status: string;
  totalAmount: number;
  adjustmentAmount: number;
  adjustmentReason?: string;
  paymentProofUrl?: string;
  paymentProofLastFive?: string;
  disclaimerAgreed: boolean;
  disclaimerAgreedAt?: string;
  paidAt?: string;
  completedAt?: string;
  payoutAt?: string;
  items: any[];
  sitterPaymentInfo?: BankAccountInfo;
}

// 取得訂單詳情 (SD-007)
export const getOrderDetail = async (orderId: string): Promise<OrderDetailResponseDto> => {
  const response = await axiosClient.get(`/orders/${orderId}`);
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
