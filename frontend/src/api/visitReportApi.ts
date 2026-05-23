import axiosClient from './axiosClient';

export interface ReportMedia {
  mediaId: string;
  mediaUrl: string;
  mediaType: 'IMAGE' | 'VIDEO';
  caption: string;
  version: number;
}

export interface VisitServiceReportDto {
  reportId: string;
  visitId: string;
  status: 'DRAFT' | 'SUBMITTED';
  content: string;
  submittedAt: string | null;
  media: ReportMedia[];
  isEditable: boolean;
  version: number;
}

export const getReport = async (visitId: string): Promise<VisitServiceReportDto> => {
  const response = await axiosClient.get(`/visits/${visitId}/report`);
  return response.data.data;
};

export const saveDraft = async (
  visitId: string,
  content: string,
  version: number,
  idempotencyKey?: string
): Promise<VisitServiceReportDto> => {
  const headers: Record<string, string> = {};
  if (idempotencyKey) {
    headers['Idempotency-Key'] = idempotencyKey;
  }
  const response = await axiosClient.put(
    `/visits/${visitId}/report`,
    { content, version },
    { headers }
  );
  return response.data.data;
};

export const uploadReportMedia = async (
  visitId: string,
  file: File,
  mediaType: 'IMAGE' | 'VIDEO',
  caption: string,
  idempotencyKey: string
): Promise<{ mediaId: string; mediaUrl: string }> => {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('mediaType', mediaType);
  formData.append('caption', caption);

  const response = await axiosClient.post(`/visits/${visitId}/media`, formData, {
    headers: {
      'Idempotency-Key': idempotencyKey
    }
  });
  return response.data.data;
};

export const deleteReportMedia = async (
  mediaId: string,
  version: number,
  idempotencyKey?: string
): Promise<void> => {
  const headers: Record<string, string> = {};
  if (idempotencyKey) {
    headers['Idempotency-Key'] = idempotencyKey;
  }
  await axiosClient.delete(`/visits/media/${mediaId}`, {
    data: { version },
    headers
  });
};

export const submitReport = async (
  visitId: string,
  idempotencyKey: string
): Promise<void> => {
  await axiosClient.post(
    `/visits/${visitId}/report/submit`,
    {},
    {
      headers: {
        'Idempotency-Key': idempotencyKey
      }
    }
  );
};
