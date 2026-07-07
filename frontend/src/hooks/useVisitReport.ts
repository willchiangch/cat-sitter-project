import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import * as api from '../api/visitReportApi';
import type { VisitServiceReportDto } from '../api/visitReportApi';

export const useVisitReportQuery = (visitId: string) => {
  return useQuery({
    queryKey: ['visit-report', visitId],
    staleTime: 5 * 60 * 1000,
    queryFn: async () => {
      try {
        return await api.getReport(visitId);
      } catch (error: any) {
        // 捕捉 404 (尚未建立或飼主端隔離)
        if (error.response && error.response.status === 404) {
          const fallback: VisitServiceReportDto = {
            reportId: '',
            visitId,
            status: 'DRAFT',
            content: '',
            submittedAt: null,
            media: [],
            isEditable: true,
            version: 0,
            visitStatus: 'PENDING',
            isPurged: false
          };
          return fallback;
        }
        throw error;
      }
    }
  });
};

export const useSaveDraftMutation = (visitId: string) => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      content,
      version,
      idempotencyKey
    }: {
      content: string;
      version: number;
      idempotencyKey?: string;
    }) => api.saveDraft(visitId, content, version, idempotencyKey),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['visit-report', visitId] });
    }
  });
};

export const useUploadReportMediaMutation = (visitId: string) => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      file,
      mediaType,
      caption,
      idempotencyKey
    }: {
      file: File;
      mediaType: 'IMAGE' | 'VIDEO';
      caption: string;
      idempotencyKey: string;
    }) => api.uploadReportMedia(visitId, file, mediaType, caption, idempotencyKey),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['visit-report', visitId] });
    }
  });
};

export const useDeleteReportMediaMutation = (visitId: string) => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      mediaId,
      version,
      idempotencyKey
    }: {
      mediaId: string;
      version: number;
      idempotencyKey?: string;
    }) => api.deleteReportMedia(mediaId, version, idempotencyKey),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['visit-report', visitId] });
    }
  });
};

export const useSubmitReportMutation = (visitId: string) => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (idempotencyKey: string) => api.submitReport(visitId, idempotencyKey),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['visit-report', visitId] });
    }
  });
};

export const useStartVisitMutation = (visitId: string) => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (idempotencyKey: string) => api.startVisit(visitId, idempotencyKey),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['visit-report', visitId] });
    }
  });
};

export const useEndVisitMutation = (visitId: string) => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (idempotencyKey: string) => api.endVisit(visitId, idempotencyKey),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['visit-report', visitId] });
    }
  });
};
