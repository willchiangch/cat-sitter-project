import axiosClient from './axiosClient';

export interface TrustRelationship {
  relationshipId: string;
  sitterId: string;
  displayName: string;
  email: string;
  status: 'PENDING' | 'ACCEPTED';
}

export interface SitterSearchResult {
  sitterId: string;
  displayName: string;
  email: string;
}

export interface ReferralCandidate {
  sitterId: string;
  displayName: string;
  available: boolean;
}

export interface CreateReferralRequest {
  orderId?: string;
  ownerId?: string;
  recommendedSitterIds: string[];
  message?: string;
}

export const getMyTrustCircle = async (): Promise<TrustRelationship[]> => {
  const response = await axiosClient.get('/sitter/trust-circle');
  return response.data;
};

export const getIncomingTrustRequests = async (): Promise<TrustRelationship[]> => {
  const response = await axiosClient.get('/sitter/trust-circle/requests/incoming');
  return response.data;
};

export const getOutgoingTrustRequests = async (): Promise<TrustRelationship[]> => {
  const response = await axiosClient.get('/sitter/trust-circle/requests/outgoing');
  return response.data;
};

export const searchSitterForTrustCircle = async (query: string): Promise<SitterSearchResult> => {
  const response = await axiosClient.get('/sitter/trust-circle/search', { params: { query } });
  return response.data;
};

export const sendTrustRequest = async (targetId: string): Promise<void> => {
  await axiosClient.post(`/sitter/trust-circle/requests/${targetId}`);
};

export const respondToTrustRequest = async (relationshipId: string, accept: boolean): Promise<void> => {
  await axiosClient.post(`/sitter/trust-circle/requests/${relationshipId}/respond`, { accept });
};

export const removeTrustRelationship = async (relationshipId: string): Promise<void> => {
  await axiosClient.delete(`/sitter/trust-circle/${relationshipId}`);
};

export const getReferralCandidates = async (ownerId: string): Promise<ReferralCandidate[]> => {
  const response = await axiosClient.get('/sitter/trust-circle/referral-candidates', { params: { ownerId } });
  return response.data;
};

export const createReferral = async (request: CreateReferralRequest): Promise<void> => {
  await axiosClient.post('/sitter/trust-circle/referrals', request);
};
