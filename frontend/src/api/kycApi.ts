import axiosClient from './axiosClient';

export interface KycStatusDto {
  kycStatus: 'UNVERIFIED' | 'PENDING_REVIEW' | 'VERIFIED' | 'REJECTED' | 'SUSPENDED';
  rejectReason: string;
  submittedAt: string;
}

export interface PendingKycRecordDto {
  recordId: string;
  sitterId: string;
  fullName: string;
  email: string;
  kycStatus: string;
  submittedAt: string;
}

export interface PendingKycListResponse {
  content: PendingKycRecordDto[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface KycRecordDetailDto {
  recordId: string;
  sitterId: string;
  fullName: string;
  email: string;
  kycStatus: string;
  submittedAt: string;
  idCardFrontKey: string;
  selfieKey: string;
}

export const getSitterKycStatus = async (): Promise<KycStatusDto> => {
  const response = await axiosClient.get('/sitter/kyc/status');
  return response.data.data;
};

export const submitKyc = async (
  idCardFront: File,
  selfie: File,
  idempotencyKey: string
): Promise<{ recordId: string; status: string }> => {
  const formData = new FormData();
  formData.append('idCardFront', idCardFront);
  formData.append('selfie', selfie);

  const response = await axiosClient.post('/sitter/kyc', formData, {
    headers: {
      'Content-Type': 'multipart/form-data',
      'Idempotency-Key': idempotencyKey
    }
  });
  return response.data.data;
};

export const getPendingKycList = async (page = 0, size = 10): Promise<PendingKycListResponse> => {
  const response = await axiosClient.get('/admin/kyc/pending', {
    params: { page, size }
  });
  return response.data.data;
};

export const getKycDetail = async (recordId: string): Promise<KycRecordDetailDto> => {
  const response = await axiosClient.get(`/admin/kyc/${recordId}`);
  return response.data.data;
};

export const getAdminMediaUrl = async (
  sitterId: string,
  mediaType: 'id-front' | 'selfie'
): Promise<string> => {
  const response = await axiosClient.get(`/admin/kyc/${sitterId}/media/${mediaType}`);
  return response.data.data.signedUrl;
};

export const getSitterMediaUrl = async (mediaType: 'id-front' | 'selfie'): Promise<string> => {
  const response = await axiosClient.get(`/sitter/kyc/media/${mediaType}`);
  return response.data.data.signedUrl;
};

export const reviewKyc = async (
  recordId: string,
  action: 'APPROVE' | 'REJECT',
  rejectReason: string | null,
  idempotencyKey: string
): Promise<void> => {
  await axiosClient.post(
    `/admin/kyc/${recordId}/review`,
    { action, rejectReason },
    {
      headers: {
        'Idempotency-Key': idempotencyKey
      }
    }
  );
};

export const suspendSitter = async (
  sitterId: string,
  reason: string,
  idempotencyKey: string
): Promise<void> => {
  await axiosClient.post(
    `/admin/sitters/${sitterId}/suspend`,
    { reason },
    {
      headers: {
        'Idempotency-Key': idempotencyKey
      }
    }
  );
};

export const unsuspendSitter = async (sitterId: string, idempotencyKey: string): Promise<void> => {
  await axiosClient.post(
    `/admin/sitters/${sitterId}/unsuspend`,
    {},
    {
      headers: {
        'Idempotency-Key': idempotencyKey
      }
    }
  );
};

export const updateSitterOpenStatus = async (isOpen: boolean): Promise<{ isOpen: boolean }> => {
  const response = await axiosClient.put('/sitter/kyc/open', { isOpen });
  return response.data.data;
};

export const getSitterOpenStatus = async (): Promise<{ isOpen: boolean }> => {
  const response = await axiosClient.get('/sitter/kyc/open');
  return response.data.data;
};
