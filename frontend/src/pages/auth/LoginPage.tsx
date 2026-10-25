import React, { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import axiosClient from '../../api/axiosClient';
import { applyLoginRole } from '../../contexts/loginRole';
import { isWebAuthnSupported, loginWithBiometricCredential } from '../../api/webauthnApi';

declare global {
  interface Window {
    google?: any;
    __handleGoogleCredential?: (response: { credential: string }) => void;
  }
}

const GOOGLE_CLIENT_ID =
  '1020176535925-78mcs53q25ku3s4ob6j0kpvp6tpu32cu.apps.googleusercontent.com';
const GOOGLE_SCRIPT_ID = 'google-identity-services';

// 只允許同源相對路徑（單一 / 開頭、非 //），避免 redirect 參數被用來做開放重導向
const getSafeRedirectTarget = (): string => {
  const redirect = new URLSearchParams(window.location.search).get('redirect');
  return redirect && /^\/(?!\/)/.test(redirect) ? redirect : '/demo';
};

const applyAuthResponseAndRedirect = (data: {
  accessToken?: string;
  refreshToken?: string;
  role?: string;
}) => {
  if (!data.accessToken) {
    throw new Error('登入失敗，請確認帳號密碼');
  }
  localStorage.setItem('accessToken', data.accessToken);
  localStorage.setItem('refreshToken', data.refreshToken || '');
  localStorage.setItem('authMode', 'manual');

  applyLoginRole(data);

  // 用整頁重新整理讓 RoleContext 重新從 localStorage 初始化，
  // 避免它 mount 時的自動登入 useEffect 用種子帳號蓋掉這次真實登入
  window.location.href = getSafeRedirectTarget();
};

const LoginPage: React.FC = () => {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [biometricLoading, setBiometricLoading] = useState(false);
  const webAuthnSupported = isWebAuthnSupported();

  const [googleRoleSelectVisible, setGoogleRoleSelectVisible] = useState(false);
  const [googleEmail, setGoogleEmail] = useState('');
  const [googleFullName, setGoogleFullName] = useState('');
  const [googleRole, setGoogleRole] = useState<'OWNER' | 'SITTER'>('OWNER');
  const [googleLoading, setGoogleLoading] = useState(false);
  const [googleError, setGoogleError] = useState<string | null>(null);
  const pendingIdTokenRef = useRef<string>('');
  const googleButtonRef = useRef<HTMLDivElement>(null);
  // 任一種登入（帳密 / 生物辨識 / Google）送出中就擋掉其他登入請求。後端一個帳號只留一組 refresh token
  // （SD-000 單一 session，後登入者覆蓋），雙擊會送兩次 login：先回來的那組 token 寫進 localStorage
  // 後，又被第二次登入在後端覆蓋掉，之後 refresh 必定失敗。用 ref 而非 state 判斷，同一個 tick 內的
  // 第二次點擊（state 還沒 re-render 成 disabled）也擋得住
  const loginInFlightRef = useRef(false);
  const busy = loading || biometricLoading || googleLoading;

  /**
   * 包住一次登入請求。成功並導頁時刻意不解除 busy：window.location.href 換頁前按鈕若恢復可點，
   * 使用者再按一次就會送出第二次登入、蓋掉剛寫入的 refresh token。
   * attempt() 回傳 true 代表已導頁；false（例如 Google 要先選角色）才解除。
   */
  const runLogin = async (
    setBusy: (v: boolean) => void,
    setErr: (v: string | null) => void,
    fallbackMessage: string,
    attempt: () => Promise<boolean>
  ) => {
    if (loginInFlightRef.current) return;
    loginInFlightRef.current = true;
    setErr(null);
    setBusy(true);
    let redirected = false;
    try {
      redirected = await attempt();
    } catch (err: any) {
      setErr(err.response?.data?.message || err.message || fallbackMessage);
    } finally {
      if (!redirected) {
        loginInFlightRef.current = false;
        setBusy(false);
      }
    }
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    return runLogin(setLoading, setError, '登入失敗，請稍後再試', async () => {
      const { data } = await axiosClient.post('/auth/login', { email, password });
      applyAuthResponseAndRedirect(data);
      return true;
    });
  };

  const handleBiometricLogin = () => {
    if (!email) {
      setError('請先輸入 Email 再使用生物辨識登入');
      return;
    }
    return runLogin(setBiometricLoading, setError, '生物辨識登入失敗，請稍後再試', async () => {
      const data = await loginWithBiometricCredential(email);
      applyAuthResponseAndRedirect(data);
      return true;
    });
  };

  const handleGoogleCredential = (response: { credential: string }) =>
    runLogin(setGoogleLoading, setGoogleError, 'Google 登入失敗，請稍後再試', async () => {
      const { data } = await axiosClient.post('/auth/google', { idToken: response.credential });
      if (data.status === 'NEEDS_ROLE_SELECTION') {
        pendingIdTokenRef.current = response.credential;
        setGoogleEmail(data.email);
        setGoogleFullName(data.fullName);
        setGoogleRoleSelectVisible(true);
        return false;
      }
      applyAuthResponseAndRedirect(data);
      return true;
    });

  const handleGoogleRoleConfirm = (e: React.FormEvent) => {
    e.preventDefault();
    return runLogin(setGoogleLoading, setGoogleError, 'Google 登入失敗，請稍後再試', async () => {
      const { data } = await axiosClient.post('/auth/google', {
        idToken: pendingIdTokenRef.current,
        role: googleRole
      });
      applyAuthResponseAndRedirect(data);
      return true;
    });
  };

  useEffect(() => {
    // 供 E2E 測試在不依賴真實 Google 彈窗的情況下模擬「收到 Google credential」事件；
    // 對正式環境無副作用（多一個不會被外部呼叫的全域函式）
    window.__handleGoogleCredential = handleGoogleCredential;

    const initializeGoogleSignIn = () => {
      if (!window.google?.accounts?.id || !googleButtonRef.current) return;
      window.google.accounts.id.initialize({
        client_id: GOOGLE_CLIENT_ID,
        callback: handleGoogleCredential
      });
      window.google.accounts.id.renderButton(googleButtonRef.current, {
        theme: 'outline',
        size: 'large',
        width: 320
      });
    };

    if (window.google?.accounts?.id) {
      initializeGoogleSignIn();
      return;
    }

    let script = document.getElementById(GOOGLE_SCRIPT_ID) as HTMLScriptElement | null;
    if (!script) {
      script = document.createElement('script');
      script.id = GOOGLE_SCRIPT_ID;
      script.src = 'https://accounts.google.com/gsi/client';
      script.async = true;
      script.defer = true;
      document.head.appendChild(script);
    }
    script.addEventListener('load', initializeGoogleSignIn);
    return () => script?.removeEventListener('load', initializeGoogleSignIn);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '1.5rem'
      }}
    >
      <div
        className="card-layered"
        style={{
          width: '100%',
          maxWidth: '360px',
          padding: '2rem',
          display: 'flex',
          flexDirection: 'column',
          gap: '1rem'
        }}
      >
        <h1
          style={{
            margin: 0,
            fontSize: '1.5rem',
            fontFamily: 'var(--font-display)',
            color: 'var(--color-primary)',
            textAlign: 'center'
          }}
        >
          🐾 WhiskerWatch 登入
        </h1>

        {googleRoleSelectVisible ? (
          <form
            onSubmit={handleGoogleRoleConfirm}
            style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}
          >
            <div
              style={{
                fontSize: '0.875rem',
                color: 'var(--color-on-surface-variant)',
                textAlign: 'center'
              }}
              data-testid="google-role-select-banner"
            >
              歡迎 {googleFullName || googleEmail}，首次使用 Google 登入，請選擇身份以建立帳號
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
              <label style={{ fontSize: '0.875rem', fontWeight: 600 }}>身份</label>
              <select
                value={googleRole}
                onChange={(e) => setGoogleRole(e.target.value as 'OWNER' | 'SITTER')}
                data-testid="google-role-select-input"
                style={{
                  padding: '0.625rem',
                  borderRadius: '6px',
                  border: '1px solid var(--color-outline-variant)'
                }}
              >
                <option value="OWNER">飼主</option>
                <option value="SITTER">保母</option>
              </select>
            </div>

            {googleError && (
              <div style={{ color: 'var(--color-error)', fontSize: '0.875rem' }}>{googleError}</div>
            )}

            <button
              type="submit"
              className="btn-primary"
              disabled={googleLoading}
              data-testid="google-role-select-confirm-btn"
              style={{ padding: '0.75rem' }}
            >
              {googleLoading ? '處理中...' : '確認並登入'}
            </button>
          </form>
        ) : (
          <>
            <form
              onSubmit={handleSubmit}
              style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}
            >
              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                <label style={{ fontSize: '0.875rem', fontWeight: 600 }}>Email</label>
                <input
                  type="email"
                  required
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  data-testid="login-email-input"
                  style={{
                    padding: '0.625rem',
                    borderRadius: '6px',
                    border: '1px solid var(--color-outline-variant)'
                  }}
                />
              </div>

              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                <label style={{ fontSize: '0.875rem', fontWeight: 600 }}>密碼</label>
                <input
                  type="password"
                  required
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  data-testid="login-password-input"
                  style={{
                    padding: '0.625rem',
                    borderRadius: '6px',
                    border: '1px solid var(--color-outline-variant)'
                  }}
                />
              </div>

              {error && (
                <div style={{ color: 'var(--color-error)', fontSize: '0.875rem' }}>{error}</div>
              )}

              <button
                type="submit"
                className="btn-primary"
                disabled={busy}
                data-testid="login-submit-btn"
                style={{ padding: '0.75rem' }}
              >
                {loading ? '登入中...' : '登入'}
              </button>

              {webAuthnSupported && (
                <button
                  type="button"
                  onClick={handleBiometricLogin}
                  disabled={busy}
                  data-testid="login-biometric-btn"
                  style={{
                    padding: '0.625rem',
                    borderRadius: '6px',
                    border: '1px solid var(--color-outline-variant)',
                    background: 'transparent',
                    cursor: 'pointer'
                  }}
                >
                  {biometricLoading ? '驗證中...' : '🔐 使用生物辨識登入'}
                </button>
              )}

              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  fontSize: '0.8rem',
                  color: 'var(--color-on-surface-variant)'
                }}
              >
                <Link to="/register" data-testid="login-register-link">
                  建立新帳號
                </Link>
                <Link to="/forgot-password" data-testid="login-forgot-password-link">
                  忘記密碼？
                </Link>
              </div>
            </form>

            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '0.75rem',
                color: 'var(--color-on-surface-variant)',
                fontSize: '0.75rem'
              }}
            >
              <div style={{ flex: 1, height: '1px', background: 'var(--color-outline-variant)' }} />
              或
              <div style={{ flex: 1, height: '1px', background: 'var(--color-outline-variant)' }} />
            </div>

            {googleError && (
              <div style={{ color: 'var(--color-error)', fontSize: '0.875rem' }}>{googleError}</div>
            )}
            {/* Google 按鈕是 GSI 注入的 iframe，無法 disabled；送出中改用 pointer-events 擋點擊，
                handleGoogleCredential 本身也有 loginInFlightRef 擋重入 */}
            <div
              ref={googleButtonRef}
              data-testid="google-signin-button"
              aria-disabled={busy}
              style={{
                display: 'flex',
                justifyContent: 'center',
                pointerEvents: busy ? 'none' : 'auto',
                opacity: busy ? 0.5 : 1
              }}
            />
            {googleLoading && (
              <div
                data-testid="google-signin-loading"
                style={{ fontSize: '0.875rem', textAlign: 'center' }}
              >
                Google 登入中...
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
};

export default LoginPage;
