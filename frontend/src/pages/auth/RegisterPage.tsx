import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import axiosClient from '../../api/axiosClient';

const ROLE_CLAIM_TO_APP_ROLE: Record<string, string> = {
  OWNER: 'client',
  SITTER: 'sitter',
  ADMIN: 'admin'
};

const decodeJwtRole = (token: string): string | null => {
  try {
    const base64Url = token.split('.')[1];
    const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
    const jsonPayload = decodeURIComponent(
      window
        .atob(base64)
        .split('')
        .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join('')
    );
    return JSON.parse(jsonPayload)?.role || null;
  } catch (e) {
    return null;
  }
};

const OTP_RESEND_COOLDOWN_SECONDS = 60;

const RegisterPage: React.FC = () => {
  const [step, setStep] = useState<'form' | 'otp'>('form');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [fullName, setFullName] = useState('');
  const [role, setRole] = useState<'OWNER' | 'SITTER'>('OWNER');
  const [otpCode, setOtpCode] = useState('');
  const [agreedToTerms, setAgreedToTerms] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [resendCooldown, setResendCooldown] = useState(0);

  useEffect(() => {
    if (resendCooldown <= 0) return;
    const timer = setInterval(() => setResendCooldown((s) => Math.max(0, s - 1)), 1000);
    return () => clearInterval(timer);
  }, [resendCooldown]);

  const handleSubmitForm = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!agreedToTerms) {
      setError('請先閱讀並勾選同意《服務條款》與《隱私權政策》');
      return;
    }
    setError(null);
    setLoading(true);
    try {
      await axiosClient.post('/auth/register', { email, password, fullName, role });
      setStep('otp');
      setResendCooldown(OTP_RESEND_COOLDOWN_SECONDS);
    } catch (err: any) {
      setError(err.response?.data?.message || err.message || '註冊失敗，請稍後再試');
    } finally {
      setLoading(false);
    }
  };

  const handleVerifyOtp = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const { data } = await axiosClient.post('/auth/register/verify-otp', { email, otpCode });
      if (!data.accessToken) {
        throw new Error('驗證失敗，請確認驗證碼');
      }
      localStorage.setItem('accessToken', data.accessToken);
      localStorage.setItem('refreshToken', data.refreshToken || '');
      localStorage.setItem('authMode', 'manual');

      const roleClaim = decodeJwtRole(data.accessToken);
      const appRole = roleClaim ? ROLE_CLAIM_TO_APP_ROLE[roleClaim] : null;
      if (appRole) {
        localStorage.setItem('userRole', appRole);
      }

      window.location.href = '/demo';
    } catch (err: any) {
      setError(err.response?.data?.message || err.message || '驗證碼錯誤，請稍後再試');
    } finally {
      setLoading(false);
    }
  };

  const handleResendOtp = async () => {
    if (resendCooldown > 0) return;
    setError(null);
    setLoading(true);
    try {
      await axiosClient.post('/auth/register/resend-otp', { email });
      setResendCooldown(OTP_RESEND_COOLDOWN_SECONDS);
    } catch (err: any) {
      setError(err.response?.data?.message || err.message || '重新寄送失敗，請稍後再試');
    } finally {
      setLoading(false);
    }
  };

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
          🐾 建立新帳號
        </h1>

        {step === 'form' ? (
          <form onSubmit={handleSubmitForm} style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
              <label style={{ fontSize: '0.875rem', fontWeight: 600 }}>姓名</label>
              <input
                type="text"
                required
                value={fullName}
                onChange={(e) => setFullName(e.target.value)}
                data-testid="register-fullname-input"
                style={{ padding: '0.625rem', borderRadius: '6px', border: '1px solid var(--color-outline-variant)' }}
              />
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
              <label style={{ fontSize: '0.875rem', fontWeight: 600 }}>Email</label>
              <input
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                data-testid="register-email-input"
                style={{ padding: '0.625rem', borderRadius: '6px', border: '1px solid var(--color-outline-variant)' }}
              />
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
              <label style={{ fontSize: '0.875rem', fontWeight: 600 }}>密碼</label>
              <input
                type="password"
                required
                minLength={8}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                data-testid="register-password-input"
                style={{ padding: '0.625rem', borderRadius: '6px', border: '1px solid var(--color-outline-variant)' }}
              />
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
              <label style={{ fontSize: '0.875rem', fontWeight: 600 }}>身份</label>
              <select
                value={role}
                onChange={(e) => setRole(e.target.value as 'OWNER' | 'SITTER')}
                data-testid="register-role-select"
                style={{ padding: '0.625rem', borderRadius: '6px', border: '1px solid var(--color-outline-variant)' }}
              >
                <option value="OWNER">飼主</option>
                <option value="SITTER">保母</option>
              </select>
            </div>

            <label
              style={{
                display: 'flex',
                alignItems: 'flex-start',
                gap: '0.5rem',
                fontSize: '0.8rem',
                color: 'var(--color-on-surface-variant)',
                cursor: 'pointer'
              }}
            >
              <input
                type="checkbox"
                checked={agreedToTerms}
                onChange={(e) => setAgreedToTerms(e.target.checked)}
                data-testid="register-terms-checkbox"
                style={{ marginTop: '0.15rem' }}
              />
              <span>我已閱讀並同意《服務條款》與《隱私權政策》</span>
            </label>

            {error && <div style={{ color: 'var(--color-error)', fontSize: '0.875rem' }}>{error}</div>}

            <button
              type="submit"
              className="btn-primary"
              disabled={loading || !agreedToTerms}
              data-testid="register-submit-btn"
              style={{ padding: '0.75rem' }}
            >
              {loading ? '送出中...' : '註冊'}
            </button>

            <Link
              to="/login"
              style={{ fontSize: '0.8rem', textAlign: 'center', color: 'var(--color-on-surface-variant)' }}
            >
              已經有帳號了？返回登入
            </Link>
          </form>
        ) : (
          <form onSubmit={handleVerifyOtp} style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            <div style={{ fontSize: '0.875rem', color: 'var(--color-on-surface-variant)', textAlign: 'center' }} data-testid="register-otp-sent-banner">
              驗證碼已寄送至 {email}，請於 10 分鐘內完成驗證
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
              <label style={{ fontSize: '0.875rem', fontWeight: 600 }}>驗證碼</label>
              <input
                type="text"
                required
                inputMode="numeric"
                pattern="\d{6}"
                maxLength={6}
                value={otpCode}
                onChange={(e) => setOtpCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                data-testid="register-otp-input"
                style={{
                  padding: '0.625rem',
                  borderRadius: '6px',
                  border: '1px solid var(--color-outline-variant)',
                  letterSpacing: '0.3em',
                  textAlign: 'center'
                }}
              />
            </div>

            {error && <div style={{ color: 'var(--color-error)', fontSize: '0.875rem' }}>{error}</div>}

            <button
              type="submit"
              className="btn-primary"
              disabled={loading || otpCode.length !== 6}
              data-testid="register-otp-submit-btn"
              style={{ padding: '0.75rem' }}
            >
              {loading ? '驗證中...' : '完成驗證並登入'}
            </button>

            <button
              type="button"
              onClick={handleResendOtp}
              disabled={loading || resendCooldown > 0}
              data-testid="register-otp-resend-btn"
              style={{
                padding: '0.5rem',
                background: 'transparent',
                border: 'none',
                color: 'var(--color-on-surface-variant)',
                fontSize: '0.8rem',
                cursor: resendCooldown > 0 ? 'default' : 'pointer'
              }}
            >
              {resendCooldown > 0 ? `重新寄送驗證碼（${resendCooldown}秒）` : '重新寄送驗證碼'}
            </button>
          </form>
        )}
      </div>
    </div>
  );
};

export default RegisterPage;
