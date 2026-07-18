import React, { useState } from 'react';
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

const LoginPage: React.FC = () => {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const { data } = await axiosClient.post('/auth/login', { email, password });
      if (!data.accessToken) {
        throw new Error('登入失敗，請確認帳號密碼');
      }
      localStorage.setItem('accessToken', data.accessToken);
      localStorage.setItem('refreshToken', data.refreshToken || '');
      localStorage.setItem('authMode', 'manual');

      const roleClaim = decodeJwtRole(data.accessToken);
      const appRole = roleClaim ? ROLE_CLAIM_TO_APP_ROLE[roleClaim] : null;
      if (appRole) {
        localStorage.setItem('userRole', appRole);
      }

      // 用整頁重新整理讓 RoleContext 重新從 localStorage 初始化，
      // 避免它 mount 時的自動登入 useEffect 用種子帳號蓋掉這次真實登入
      window.location.href = '/demo';
    } catch (err: any) {
      setError(err.response?.data?.message || err.message || '登入失敗，請稍後再試');
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
      <form
        onSubmit={handleSubmit}
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
          disabled={loading}
          data-testid="login-submit-btn"
          style={{ padding: '0.75rem' }}
        >
          {loading ? '登入中...' : '登入'}
        </button>

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
    </div>
  );
};

export default LoginPage;
