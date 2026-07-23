import React, { useEffect, useState } from 'react';
import axiosClient from '../../api/axiosClient';
import {
  deleteBiometricCredential,
  isWebAuthnSupported,
  listBiometricCredentials,
  registerBiometricCredential,
  type WebAuthnCredentialSummary
} from '../../api/webauthnApi';

export const AccountSettings: React.FC = () => {
  const [showConfirm, setShowConfirm] = useState(false);
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [logoutAllLoading, setLogoutAllLoading] = useState(false);
  const [logoutAllError, setLogoutAllError] = useState<string | null>(null);

  const webAuthnSupported = isWebAuthnSupported();
  const [credentials, setCredentials] = useState<WebAuthnCredentialSummary[]>([]);
  const [credentialsLoading, setCredentialsLoading] = useState(false);
  const [credentialsError, setCredentialsError] = useState<string | null>(null);
  const [registering, setRegistering] = useState(false);

  const refreshCredentials = async () => {
    if (!webAuthnSupported) return;
    setCredentialsLoading(true);
    try {
      const data = await listBiometricCredentials();
      setCredentials(data);
    } catch (err: any) {
      setCredentialsError(err.response?.data?.message || err.message || '無法載入生物辨識裝置清單');
    } finally {
      setCredentialsLoading(false);
    }
  };

  useEffect(() => {
    refreshCredentials();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleRegisterBiometric = async () => {
    setCredentialsError(null);
    setRegistering(true);
    try {
      await registerBiometricCredential();
      await refreshCredentials();
    } catch (err: any) {
      setCredentialsError(err.response?.data?.message || err.message || '生物辨識裝置註冊失敗，請稍後再試');
    } finally {
      setRegistering(false);
    }
  };

  const handleDeleteBiometric = async (id: string) => {
    setCredentialsError(null);
    try {
      await deleteBiometricCredential(id);
      await refreshCredentials();
    } catch (err: any) {
      setCredentialsError(err.response?.data?.message || err.message || '移除失敗，請稍後再試');
    }
  };

  const handleLogoutAllDevices = async () => {
    setLogoutAllError(null);
    setLogoutAllLoading(true);
    try {
      await axiosClient.post('/auth/logout-all-devices');

      localStorage.removeItem('accessToken');
      localStorage.removeItem('refreshToken');
      localStorage.removeItem('userRole');
      localStorage.removeItem('authMode');

      window.location.href = '/login';
    } catch (err: any) {
      setLogoutAllError(err.response?.data?.message || err.message || '登出失敗，請稍後再試');
      setLogoutAllLoading(false);
    }
  };

  const handleDeactivate = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      await axiosClient.post('/auth/deactivate', { password });

      localStorage.removeItem('accessToken');
      localStorage.removeItem('refreshToken');
      localStorage.removeItem('userRole');
      localStorage.removeItem('authMode');

      window.location.href = '/login';
    } catch (err: any) {
      const status = err.response?.status;
      if (status === 403) {
        setError('密碼錯誤，請重新輸入');
      } else if (status === 409) {
        setError(err.response?.data?.message || '您尚有未結案的訂單，請先完成或取消後再嘗試註銷帳號');
      } else {
        setError(err.response?.data?.message || err.message || '註銷失敗，請稍後再試');
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ padding: '1.5rem 0', display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
      <div>
        <h2 style={{ margin: 0, fontSize: '1.75rem', color: 'var(--color-on-surface)' }}>帳號設定</h2>
        <p style={{ margin: '0.25rem 0 0 0', fontSize: '0.875rem', color: 'var(--color-on-surface-variant)' }}>
          管理您的帳號安全與生命週期相關設定。
        </p>
      </div>

      <div
        className="card-layered"
        style={{
          display: 'flex',
          flexDirection: 'column',
          gap: '1rem',
          padding: '1.5rem',
          border: '1px solid var(--color-outline-variant)'
        }}
      >
        <div>
          <h4 style={{ margin: 0, fontSize: '1.1rem', color: 'var(--color-on-surface)' }}>登出所有裝置</h4>
          <p style={{ margin: '0.5rem 0 0 0', fontSize: '0.875rem', color: 'var(--color-on-surface-variant)', lineHeight: 1.6 }}>
            若您懷疑帳號在其他裝置被登入，可立即撤銷登入狀態，所有裝置皆需重新輸入密碼才能再次登入。
          </p>
        </div>

        {logoutAllError && (
          <div data-testid="account-settings-logout-all-error" style={{ color: 'var(--color-error)', fontSize: '0.875rem' }}>
            {logoutAllError}
          </div>
        )}

        <button
          type="button"
          onClick={handleLogoutAllDevices}
          disabled={logoutAllLoading}
          data-testid="account-settings-logout-all-btn"
          style={{
            alignSelf: 'flex-start',
            padding: '0.625rem 1.25rem',
            borderRadius: '6px',
            border: '1px solid var(--color-outline-variant)',
            background: 'transparent',
            cursor: 'pointer'
          }}
        >
          {logoutAllLoading ? '處理中...' : '登出所有裝置'}
        </button>
      </div>

      {webAuthnSupported && (
        <div
          className="card-layered"
          style={{
            display: 'flex',
            flexDirection: 'column',
            gap: '1rem',
            padding: '1.5rem',
            border: '1px solid var(--color-outline-variant)'
          }}
        >
          <div>
            <h4 style={{ margin: 0, fontSize: '1.1rem', color: 'var(--color-on-surface)' }}>生物辨識登入</h4>
            <p style={{ margin: '0.5rem 0 0 0', fontSize: '0.875rem', color: 'var(--color-on-surface-variant)', lineHeight: 1.6 }}>
              開啟後可在此裝置用 FaceID/指紋等生物辨識快速登入，無需輸入密碼。憑證綁定裝置，若想在其他裝置也使用，需個別新增。
            </p>
          </div>

          {credentialsError && (
            <div data-testid="account-settings-webauthn-error" style={{ color: 'var(--color-error)', fontSize: '0.875rem' }}>
              {credentialsError}
            </div>
          )}

          {credentialsLoading ? (
            <div style={{ fontSize: '0.875rem', color: 'var(--color-on-surface-variant)' }}>載入中...</div>
          ) : credentials.length > 0 ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
              {credentials.map((credential) => (
                <div
                  key={credential.id}
                  data-testid={`account-settings-webauthn-credential-${credential.id}`}
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    padding: '0.625rem 0.75rem',
                    borderRadius: '6px',
                    border: '1px solid var(--color-outline-variant)',
                    fontSize: '0.8rem'
                  }}
                >
                  <span>
                    註冊於 {new Date(credential.createdAt).toLocaleDateString()}
                    {credential.lastUsedAt ? `，最近使用 ${new Date(credential.lastUsedAt).toLocaleDateString()}` : '（尚未使用過）'}
                  </span>
                  <button
                    type="button"
                    onClick={() => handleDeleteBiometric(credential.id)}
                    data-testid={`account-settings-webauthn-remove-${credential.id}`}
                    style={{
                      padding: '0.375rem 0.75rem',
                      borderRadius: '6px',
                      border: '1px solid var(--color-error)',
                      color: 'var(--color-error)',
                      background: 'transparent',
                      cursor: 'pointer',
                      fontSize: '0.75rem'
                    }}
                  >
                    移除
                  </button>
                </div>
              ))}
            </div>
          ) : (
            <div style={{ fontSize: '0.875rem', color: 'var(--color-on-surface-variant)' }}>此裝置尚未開啟生物辨識登入。</div>
          )}

          <button
            type="button"
            onClick={handleRegisterBiometric}
            disabled={registering}
            data-testid="account-settings-webauthn-register-btn"
            style={{
              alignSelf: 'flex-start',
              padding: '0.625rem 1.25rem',
              borderRadius: '6px',
              border: '1px solid var(--color-outline-variant)',
              background: 'transparent',
              cursor: 'pointer'
            }}
          >
            {registering ? '註冊中...' : '🔐 開啟此裝置的生物辨識'}
          </button>
        </div>
      )}

      <div
        className="card-layered"
        style={{
          display: 'flex',
          flexDirection: 'column',
          gap: '1rem',
          padding: '1.5rem',
          border: '1px dashed var(--color-error)'
        }}
      >
        <div>
          <h4 style={{ margin: 0, fontSize: '1.1rem', color: 'var(--color-on-surface)' }}>註銷帳號</h4>
          <p style={{ margin: '0.5rem 0 0 0', fontSize: '0.875rem', color: 'var(--color-on-surface-variant)', lineHeight: 1.6 }}>
            註銷後您將無法再登入，公開檔案將立即失效；您涉及的信任圈與我的最愛關聯會一併移除。
            訂單、財務等紀錄基於法規需求將保留於後端資料庫，不會對外顯示。
            若您名下尚有未結案的訂單，請先完成或取消後再嘗試註銷。
          </p>
        </div>

        {!showConfirm ? (
          <button
            type="button"
            onClick={() => setShowConfirm(true)}
            data-testid="account-settings-deactivate-btn"
            style={{
              alignSelf: 'flex-start',
              padding: '0.625rem 1.25rem',
              borderRadius: '6px',
              border: '1px solid var(--color-error)',
              color: 'var(--color-error)',
              background: 'transparent',
              cursor: 'pointer'
            }}
          >
            我要註銷帳號
          </button>
        ) : (
          <form onSubmit={handleDeactivate} style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
              <label style={{ fontSize: '0.875rem', fontWeight: 600 }}>請重新輸入密碼以確認註銷</label>
              <input
                type="password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                data-testid="account-settings-deactivate-password-input"
                style={{ padding: '0.625rem', borderRadius: '6px', border: '1px solid var(--color-outline-variant)' }}
              />
            </div>

            {error && (
              <div data-testid="account-settings-deactivate-error" style={{ color: 'var(--color-error)', fontSize: '0.875rem' }}>
                {error}
              </div>
            )}

            <div style={{ display: 'flex', gap: '0.75rem' }}>
              <button
                type="submit"
                disabled={loading}
                data-testid="account-settings-deactivate-confirm-btn"
                style={{
                  padding: '0.625rem 1.25rem',
                  borderRadius: '6px',
                  border: 'none',
                  color: 'var(--color-on-error)',
                  background: 'var(--color-error)',
                  cursor: 'pointer'
                }}
              >
                {loading ? '處理中...' : '確認註銷'}
              </button>
              <button
                type="button"
                onClick={() => {
                  setShowConfirm(false);
                  setPassword('');
                  setError(null);
                }}
                disabled={loading}
                data-testid="account-settings-deactivate-cancel-btn"
                style={{
                  padding: '0.625rem 1.25rem',
                  borderRadius: '6px',
                  border: '1px solid var(--color-outline-variant)',
                  background: 'transparent',
                  cursor: 'pointer'
                }}
              >
                取消
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
};

export default AccountSettings;
