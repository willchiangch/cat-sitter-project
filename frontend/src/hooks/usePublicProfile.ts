import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getPublicProfile,
  updatePublicProfile,
  uploadAvatar,
  getForbiddenKeywords,
  addForbiddenKeyword,
  deleteForbiddenKeyword
} from '../api/publicProfileApi';
import type {
  PublicProfileResponse,
  UpdatePublicProfileRequest,
  PaginatedKeywords,
  ForbiddenKeyword
} from '../api/publicProfileApi';

export const usePublicProfileQuery = (sitterId: string, type: 'public' | 'edit' = 'public') => {
  return useQuery<PublicProfileResponse, Error>({
    queryKey: ['public-profile', sitterId, type],
    queryFn: () => getPublicProfile(sitterId),
    enabled: !!sitterId,
    retry: false,
    staleTime: 0,
    gcTime: 0
  });
};

export const useUpdateProfileMutation = (sitterId: string) => {
  const queryClient = useQueryClient();
  return useMutation<void, Error, UpdatePublicProfileRequest>({
    mutationFn: updatePublicProfile,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['public-profile', sitterId] });
    }
  });
};

export const useUploadAvatarMutation = (sitterId: string) => {
  const queryClient = useQueryClient();
  return useMutation<string, Error, File>({
    mutationFn: uploadAvatar,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['public-profile', sitterId] });
    }
  });
};

export const useForbiddenKeywordsQuery = (q?: string, page = 0, size = 10) => {
  return useQuery<PaginatedKeywords, Error>({
    queryKey: ['forbidden-keywords', q, page, size],
    queryFn: () => getForbiddenKeywords(q, page, size)
  });
};

export const useAddForbiddenKeywordMutation = () => {
  const queryClient = useQueryClient();
  return useMutation<ForbiddenKeyword, Error, string>({
    mutationFn: (keyword: string) => addForbiddenKeyword(keyword),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['forbidden-keywords'] });
    }
  });
};

export const useDeleteForbiddenKeywordMutation = () => {
  const queryClient = useQueryClient();
  return useMutation<void, Error, string>({
    mutationFn: deleteForbiddenKeyword,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['forbidden-keywords'] });
    }
  });
};
