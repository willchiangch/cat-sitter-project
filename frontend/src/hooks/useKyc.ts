import { useQuery } from '@tanstack/react-query';
import { getSitterKycStatus } from '../api/kycApi';
import type { KycStatusDto } from '../api/kycApi';

/**
 * 保母 KYC 狀態的共用快取 (Backlog 第 3 批 U3)。AppHeader 的 KYC 橫幅與 /sitter/kyc 頁面讀同一份：
 * 過去兩邊各自打 API，送出 KYC 後頁面徽章已是「審核中」，橫幅仍掛著送出前抓的「尚未完成實名認證」，
 * 要換頁才會更新。SitterKycSubmit 每次取得最新狀態後以 setQueryData 寫回這個 key。
 */
export const KYC_STATUS_QUERY_KEY = ['kyc', 'status'] as const;

export const useSitterKycStatusQuery = (enabled: boolean) =>
  useQuery<KycStatusDto, Error>({
    queryKey: KYC_STATUS_QUERY_KEY,
    queryFn: getSitterKycStatus,
    enabled,
    staleTime: 0,
    retry: false
  });
