import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import axiosClient from '../../api/axiosClient';

const ForgotPasswordPage: React.FC = () => {
  const [email, setEmail] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      await axiosClient.post('/auth/forgot-password', { email });
      setSubmitted(true);
    } catch (err: any) {
      setError(err.response?.data?.message || err.message || '請求失敗，請稍後再試');
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
          🐾 忘記密碼
        </h1>

        {submitted ? (
          <div style={{ textAlign: 'center', display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            <div style={{ color: 'var(--color-primary)', fontWeight: 600 }} data-testid="forgot-password-success">
              若此信箱已註冊，重設密碼連結已寄出，請至信箱查收（30 分鐘內有效）。
            </div>
            <Link to="/login" className="btn-primary" style={{ padding: '0.75rem', textAlign: 'center' }}>
              返回登入
            </Link>
          </div>
        ) : (
          <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            <p style={{ fontSize: '0.8rem', color: 'var(--color-on-surface-variant)', margin: 0 }}>
              請輸入註冊時使用的 Email，我們會寄送重設密碼連結給您。
            </p>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
              <label style={{ fontSize: '0.875rem', fontWeight: 600 }}>Email</label>
              <input
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                data-testid="forgot-password-email-input"
                style={{ padding: '0.625rem', borderRadius: '6px', border: '1px solid var(--color-outline-variant)' }}
              />
            </div>

            {error && <div style={{ color: 'var(--color-error)', fontSize: '0.875rem' }}>{error}</div>}

            <button
              type="submit"
              className="btn-primary"
              disabled={loading}
              data-testid="forgot-password-submit-btn"
              style={{ padding: '0.75rem' }}
            >
              {loading ? '送出中...' : '寄送重設連結'}
            </button>

            <Link
              to="/login"
              style={{ fontSize: '0.8rem', textAlign: 'center', color: 'var(--color-on-surface-variant)' }}
            >
              返回登入
            </Link>
          </form>
        )}
      </div>
    </div>
  );
};

export default ForgotPasswordPage;
