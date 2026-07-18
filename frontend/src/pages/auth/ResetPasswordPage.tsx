import React, { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import axiosClient from '../../api/axiosClient';

const ResetPasswordPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token') || '';

  const [newPassword, setNewPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    if (!token) {
      setError('重設連結無效，請重新申請忘記密碼');
      return;
    }

    setLoading(true);
    try {
      await axiosClient.post('/auth/reset-password', { token, newPassword });
      setSuccess(true);
    } catch (err: any) {
      setError(err.response?.data?.message || err.message || '重設失敗，請重新申請連結');
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
          🐾 重設密碼
        </h1>

        {success ? (
          <div style={{ textAlign: 'center', display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            <div style={{ color: 'var(--color-primary)', fontWeight: 600 }} data-testid="reset-password-success">
              ✓ 密碼已成功重設，請重新登入
            </div>
            <Link to="/login" className="btn-primary" style={{ padding: '0.75rem', textAlign: 'center' }}>
              前往登入
            </Link>
          </div>
        ) : !token ? (
          <div style={{ textAlign: 'center', display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            <div style={{ color: 'var(--color-error)', fontSize: '0.875rem' }}>
              重設連結無效或已遺失參數，請重新申請。
            </div>
            <Link to="/forgot-password" className="btn-primary" style={{ padding: '0.75rem', textAlign: 'center' }}>
              重新申請
            </Link>
          </div>
        ) : (
          <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
              <label style={{ fontSize: '0.875rem', fontWeight: 600 }}>新密碼</label>
              <input
                type="password"
                required
                minLength={8}
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                data-testid="reset-password-new-password-input"
                style={{ padding: '0.625rem', borderRadius: '6px', border: '1px solid var(--color-outline-variant)' }}
              />
            </div>

            {error && <div style={{ color: 'var(--color-error)', fontSize: '0.875rem' }}>{error}</div>}

            <button
              type="submit"
              className="btn-primary"
              disabled={loading}
              data-testid="reset-password-submit-btn"
              style={{ padding: '0.75rem' }}
            >
              {loading ? '送出中...' : '確認重設密碼'}
            </button>
          </form>
        )}
      </div>
    </div>
  );
};

export default ResetPasswordPage;
