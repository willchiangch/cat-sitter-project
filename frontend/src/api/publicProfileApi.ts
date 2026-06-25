import axiosClient from './axiosClient';

export interface ServiceArea {
  city: string;
  district: string;
}

export interface PublicProfileResponse {
  gated: boolean;
  sitterId?: string;
  displayName: string;
  avatarUrl: string;
  bio: string;
  tags: string[];
  serviceAreas: ServiceArea[];
  isOpen: boolean;
  kycStatus?: string;
  trustScore?: number;
  version?: number;
  isVisible?: boolean;
}

export interface UpdatePublicProfileRequest {
  displayName: string;
  bio: string;
  isVisible: boolean;
  tags: string[];
  serviceAreas: ServiceArea[];
  version: number;
}

export interface ForbiddenKeyword {
  id: string;
  keyword: string;
  createdBy: string;
  createdAt: string;
}

export interface PaginatedKeywords {
  content: ForbiddenKeyword[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

export const getPublicProfile = async (sitterId: string): Promise<PublicProfileResponse> => {
  const response = await axiosClient.get<PublicProfileResponse>(`/sitter/profile/${sitterId}`);
  return response.data;
};

export const updatePublicProfile = async (request: UpdatePublicProfileRequest): Promise<void> => {
  await axiosClient.put('/sitter/profile', request);
};

export const uploadAvatar = async (file: File): Promise<string> => {
  const formData = new FormData();
  formData.append('file', file);
  const response = await axiosClient.post<{ status: string; avatarUrl: string }>(
    '/sitter/profile/avatar',
    formData,
    {
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    }
  );
  return response.data.avatarUrl;
};

export const getForbiddenKeywords = async (q?: string, page = 0, size = 10): Promise<PaginatedKeywords> => {
  const params = new URLSearchParams();
  if (q) params.append('q', q);
  params.append('page', page.toString());
  params.append('size', size.toString());
  const response = await axiosClient.get<PaginatedKeywords>(`/admin/forbidden-keywords?${params.toString()}`);
  return response.data;
};

export const addForbiddenKeyword = async (keyword: string): Promise<ForbiddenKeyword> => {
  const response = await axiosClient.post<ForbiddenKeyword>('/admin/forbidden-keywords', { keyword });
  return response.data;
};

export const deleteForbiddenKeyword = async (id: string): Promise<void> => {
  await axiosClient.delete(`/admin/forbidden-keywords/${id}`);
};
