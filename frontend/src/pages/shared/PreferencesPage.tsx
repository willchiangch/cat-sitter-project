import React from 'react';
import { useRole } from '../../contexts/RoleContext';
import { usePreferencesQuery, useUpdatePreferenceMutation } from '../../hooks/useNotifications';

export const PreferencesPage: React.FC = () => {
  const { currentRole } = useRole();
  const { data: preferences, isLoading, isError } = usePreferencesQuery();
  const updatePreferenceMutation = useUpdatePreferenceMutation();

  const handleToggle = (category: string, enableInApp: boolean, enableEmail: boolean) => {
    // 帳號認證為核心功能，禁止關閉，前端防呆
    if (category === 'ACCOUNT_AUTH') return;

    updatePreferenceMutation.mutate({
      category,
      enableInApp,
      enableEmail
    });
  };

  const getCategoryLabel = (category: string) => {
    switch (category) {
      case 'ORDER_AFFAIR':
        return '訂單交易通知';
      case 'ACCOUNT_AUTH':
        return '帳號與認證安全通知';
      case 'SUBSCRIPTION_MAINTENANCE':
        return '系統維護與訂閱通知';
      case 'SERVICE_RECORD':
        return '照護日誌與媒體通知';
      default:
        return category;
    }
  };

  const getCategoryDesc = (category: string) => {
    switch (category) {
      case 'ORDER_AFFAIR':
        return '包含訂單報價、付款確認、取消退款等與交易直接相關的通知。';
      case 'ACCOUNT_AUTH':
        return '包含密碼變更、登入安全性警告、實名認證 (KYC) 審核結果與停權警告等。';
      case 'SUBSCRIPTION_MAINTENANCE':
        return '包含平台系統維護公告、重要更新通知，及個人訂閱服務狀態。';
      case 'SERVICE_RECORD':
        return '包含保母 Check-in/Check-out、服務中文字日誌、照片或影片上傳更新。';
      default:
        return '';
    }
  };

  return (
    <div style={{ padding: '1.5rem 0', display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
      {/* 標題區 */}
      <div>
        <h2 style={{ margin: 0, fontSize: '1.75rem', color: 'var(--color-on-surface)' }}>通知偏好設定</h2>
        <p style={{ margin: '0.25rem 0 0 0', fontSize: '0.875rem', color: 'var(--color-on-surface-variant)' }}>
          個人化配置您的訊息中心（In-App）與電子郵件（Email）通知開關。
        </p>
      </div>

      {isLoading ? (
        <div style={{ textAlign: 'center', padding: '3rem', color: 'var(--color-on-surface-variant)' }}>
          載入偏好設定中...
        </div>
      ) : isError ? (
        <div style={{ textAlign: 'center', padding: '3rem', color: 'var(--color-error)' }}>
          載入偏好設定失敗，請稍後再試。
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem' }}>
          {preferences?.map((pref) => {
            const isAccountAuth = pref.category === 'ACCOUNT_AUTH';

            return (
              <div
                key={pref.category}
                className="card-layered"
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  gap: '1rem',
                  padding: '1.5rem',
                  border: isAccountAuth ? '1px dashed var(--color-primary)' : '1px solid var(--color-outline-variant)'
                }}
              >
                {/* 說明區 */}
                <div>
                  <h4 style={{ margin: 0, fontSize: '1.1rem', color: 'var(--color-on-surface)' }}>
                    {getCategoryLabel(pref.category)}
                  </h4>
                  <p style={{ margin: '0.25rem 0 0 0', fontSize: '0.875rem', color: 'var(--color-on-surface-variant)', lineHeight: 1.4 }}>
                    {getCategoryDesc(pref.category)}
                  </p>
                </div>

                {/* 切換開關區 */}
                <div style={{ display: 'flex', gap: '2rem', marginTop: '0.5rem', flexWrap: 'wrap' }}>
                  {/* In-App 開關 */}
                  <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                    <label style={{ fontSize: '0.9rem', color: 'var(--color-on-surface)' }}>
                      站內訊息中心 (In-App)
                    </label>
                    <input
                      type="checkbox"
                      checked={pref.enableInApp}
                      disabled={isAccountAuth || updatePreferenceMutation.isPending}
                      onChange={(e) => handleToggle(pref.category, e.target.checked, pref.enableEmail)}
                      data-testid={`${currentRole}-preferences-toggle-${pref.category.toLowerCase()}-inapp`}
                      style={{
                        width: '40px',
                        height: '20px',
                        cursor: isAccountAuth ? 'not-allowed' : 'pointer'
                      }}
                    />
                  </div>

                  {/* Email 開關 */}
                  <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                    <label style={{ fontSize: '0.9rem', color: 'var(--color-on-surface)' }}>
                      電子郵件 (Email)
                    </label>
                    <input
                      type="checkbox"
                      checked={pref.enableEmail}
                      disabled={isAccountAuth || updatePreferenceMutation.isPending}
                      onChange={(e) => handleToggle(pref.category, pref.enableInApp, e.target.checked)}
                      data-testid={`${currentRole}-preferences-toggle-${pref.category.toLowerCase()}-email`}
                      style={{
                        width: '40px',
                        height: '20px',
                        cursor: isAccountAuth ? 'not-allowed' : 'pointer'
                      }}
                    />
                  </div>
                </div>

                {/* 帳號安全核心通知警語 */}
                {isAccountAuth && (
                  <div
                    data-testid={`${currentRole}-preferences-tip-account-auth`}
                    style={{
                      fontSize: '0.75rem',
                      color: 'var(--color-primary)',
                      backgroundColor: 'var(--color-on-primary)',
                      padding: '8px 12px',
                      borderRadius: '8px',
                      marginTop: '0.25rem',
                      fontWeight: 600
                    }}
                  >
                    💡 帳號安全與認證為系統核心功能，無法關閉，以確保您的個人與財產交易安全。
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};
export default PreferencesPage;
