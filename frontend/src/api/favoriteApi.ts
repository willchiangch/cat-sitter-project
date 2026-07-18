import axiosClient from './axiosClient';

export interface FavoriteSitter {
  sitterId: string;
  displayName?: string;
  avatarUrl?: string;
  tags?: string[];
  removed: boolean;
  hidden: boolean;
}

export interface SitterSearchResult {
  sitterId: string;
  displayName: string;
  email: string;
}

export const getMyFavorites = async (): Promise<FavoriteSitter[]> => {
  const response = await axiosClient.get('/owner/favorites');
  return response.data;
};

export const searchSitterForFavorite = async (query: string): Promise<SitterSearchResult> => {
  const response = await axiosClient.get('/owner/favorites/search', { params: { query } });
  return response.data;
};

export const addFavoriteSitter = async (sitterId: string): Promise<void> => {
  await axiosClient.post(`/owner/favorites/${sitterId}`);
};

export const removeFavoriteSitter = async (sitterId: string): Promise<void> => {
  await axiosClient.delete(`/owner/favorites/${sitterId}`);
};
