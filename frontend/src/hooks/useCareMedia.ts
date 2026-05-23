import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import { getMedia, uploadMedia, deleteMedia } from '../api/careApi';
import type { CareMedia } from '../api/careApi';

export const useCareMediaQuery = (sitterId: string, ownerId: string) => {
  return useQuery<CareMedia[], Error>({
    queryKey: ['care-media', sitterId, ownerId],
    queryFn: async () => {
      try {
        return await getMedia(sitterId, ownerId);
      } catch (error) {
        if (axios.isAxiosError(error) && error.response?.status === 404) {
          // 404 防禦，回傳空陣列 []
          return [];
        }
        throw error;
      }
    },
    enabled: !!sitterId && !!ownerId,
    staleTime: 5 * 60 * 1000
  });
};

export const useUploadMediaMutation = (sitterId: string, ownerId: string) => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      file,
      caption,
      idempotencyKey
    }: {
      file: File;
      caption: string;
      idempotencyKey: string;
    }) => uploadMedia(sitterId, ownerId, file, caption, idempotencyKey),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['care-media', sitterId, ownerId] });
    }
  });
};

export const useDeleteMediaMutation = (sitterId: string, ownerId: string) => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ mediaId, idempotencyKey }: { mediaId: string; idempotencyKey: string }) =>
      deleteMedia(mediaId, idempotencyKey),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['care-media', sitterId, ownerId] });
    }
  });
};
