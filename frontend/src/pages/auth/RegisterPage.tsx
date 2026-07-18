import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import axiosClient from '../../api/axiosClient';

const RegisterPage: React.FC = () => {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [fullName, setFullName] = useState('');
  const [role, setRole] = useState<'OWNER' | 'SITTER'>('OWNER');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      await axiosClient.post('/auth/register', { email, password, fullName, role });
      setSuccess(true);
    } catch (err: any) {
      setError(err.response?.data?.message || err.message || '註冊失敗，請稍後再試');
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

        {success ? (
          <div style={{ textAlign: 'center', display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            <div style={{ color: 'var(--color-primary)', fontWeight: 600 }} data-testid="register-success-banner">
              ✓ 註冊成功，請重新登入
            </div>
            <Link to="/login" className="btn-primary" style={{ padding: '0.75rem', textAlign: 'center' }}>
              前往登入
            </Link>
          </div>
        ) : (
          <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
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

            {error && <div style={{ color: 'var(--color-error)', fontSize: '0.875rem' }}>{error}</div>}

            <button
              type="submit"
              className="btn-primary"
              disabled={loading}
              data-testid="register-submit-btn"
              style={{ padding: '0.75rem' }}
            >
              {loading ? '註冊中...' : '註冊'}
            </button>

            <Link
              to="/login"
              style={{ fontSize: '0.8rem', textAlign: 'center', color: 'var(--color-on-surface-variant)' }}
            >
              已經有帳號了？返回登入
            </Link>
          </form>
        )}
      </div>
    </div>
  );
};

export default RegisterPage;
