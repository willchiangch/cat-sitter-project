import axiosClient from './axiosClient';

/** 保母收款帳號（SD-SUPPLY / PRD-025，尚未重建，沿用舊端點） */
export interface BankAccountInfo {
  bankCode?: string;
  bankBranch?: string;
  bankAccount?: string;
  bankPayeeName?: string;
}

export const getSitterPaymentInfo = async (): Promise<BankAccountInfo> => {
  const response = await axiosClient.get('/sitter/payment-info');
  return response.data;
};

export const updateSitterPaymentInfo = async (
  info: BankAccountInfo
): Promise<{ status: string; message: string }> => {
  const response = await axiosClient.put('/sitter/payment-info', info);
  return response.data;
};
