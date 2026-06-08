import React, { useState, useEffect } from 'react';
import Card from '../../components/ui/Card';
import { getSitterKycStatus, submitKyc, getSitterMediaUrl } from '../../api/kycApi';
import type { KycStatusDto } from '../../api/kycApi';

const SitterKycSubmit: React.FC = () => {
  const [statusInfo, setStatusInfo] = useState<KycStatusDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [idCardFront, setIdCardFront] = useState<File | null>(null);
  const [selfie, setSelfie] = useState<File | null>(null);
  const [errorMsg, setErrorMsg] = useState('');
  const [successMsg, setSuccessMsg] = useState('');
  const [idempotencyKey, setIdempotencyKey] = useState('');
  const [submittedFrontUrl, setSubmittedFrontUrl] = useState('');
  const [submittedSelfieUrl, setSubmittedSelfieUrl] = useState('');

  useEffect(() => {
    setIdempotencyKey(Math.random().toString(36).substring(2, 15) + Date.now());
    fetchStatus();
  }, []);

  const fetchStatus = async () => {
    setLoading(true);
    try {
      const data = await getSitterKycStatus();
      setStatusInfo(data);

      if (data.kycStatus !== 'UNVERIFIED') {
        try {
          const [frontUrl, selfieUrl] = await Promise.all([
            getSitterMediaUrl('id-front'),
            getSitterMediaUrl('selfie')
          ]);
          setSubmittedFrontUrl(frontUrl);
          setSubmittedSelfieUrl(selfieUrl);
        } catch (mediaErr) {
          console.error('Failed to fetch submitted media URLs:', mediaErr);
        }
      }
    } catch (err: any) {
      console.error(err);
      setErrorMsg('取得實名認證狀態失敗');
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMsg('');
    setSuccessMsg('');

    if (!idCardFront || !selfie) {
      setErrorMsg('請上傳身分證正面照片及半身自拍照');
      return;
    }

    if (idCardFront.size > 5 * 1024 * 1024) {
      setErrorMsg('身分證正面照片超過 5MB 限制');
      return;
    }

    if (selfie.size > 5 * 1024 * 1024) {
      setErrorMsg('自拍照超過 5MB 限制');
      return;
    }

    setSubmitting(true);
    try {
      await submitKyc(idCardFront, selfie, idempotencyKey);
      setSuccessMsg('實名認證資料提交成功，已進入審核程序');
      await fetchStatus();
      setIdempotencyKey(Math.random().toString(36).substring(2, 15) + Date.now());
    } catch (err: any) {
      console.error(err);
      const msg = err.response?.data?.message || '認證提交失敗，請稍後再試';
      setErrorMsg(msg);
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) {
    return (
      <div
        style={{ padding: '3rem', textAlign: 'center', color: 'var(--color-on-surface-variant)' }}
      >
        載入實名認證狀態中...
      </div>
    );
  }

  const kycStatus = statusInfo?.kycStatus || 'UNVERIFIED';
  const rejectReason = statusInfo?.rejectReason || '';
  const submittedAt = statusInfo?.submittedAt || '';

  return (
    <div
      style={{
        maxWidth: '600px',
        margin: '2rem auto',
        padding: '0 1.5rem',
        fontFamily: 'var(--font-sans)'
      }}
    >
      <h2
        style={{
          fontSize: '1.75rem',
          fontWeight: '700',
          marginBottom: '1.5rem',
          color: 'var(--color-on-surface)'
        }}
      >
        保母實名認證 (KYC)
      </h2>

      {/* 狀態卡片 */}
      <Card style={{ marginBottom: '2rem', padding: '1.5rem' }}>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            marginBottom: '1rem'
          }}
        >
          <span
            style={{
              fontSize: '1rem',
              fontWeight: '600',
              color: 'var(--color-on-surface-variant)'
            }}
          >
            當前認證狀態
          </span>
          <span
            style={{
              padding: '6px 12px',
              borderRadius: '9999px',
              fontSize: '0.85rem',
              fontWeight: '700',
              ...getStatusBadgeStyle(kycStatus)
            }}
          >
            {getStatusText(kycStatus)}
          </span>
        </div>

        {kycStatus === 'PENDING_REVIEW' && (
          <p
            style={{
              fontSize: '0.9rem',
              color: 'var(--color-on-surface-variant)',
              lineHeight: '1.5'
            }}
          >
            系統已收到您的認證申請。管理員正在審核您的身份文件，通常需要 1-2
            個工作天。審核期間無法重複提交或修改資料。
            {submittedAt && (
              <span style={{ display: 'block', marginTop: '0.5rem', fontSize: '0.8rem' }}>
                提交時間：{new Date(submittedAt).toLocaleString()}
              </span>
            )}
          </p>
        )}

        {kycStatus === 'VERIFIED' && (
          <p
            style={{
              fontSize: '0.9rem',
              color: 'var(--color-on-surface-variant)',
              lineHeight: '1.5'
            }}
          >
            🎉
            恭喜！您已完成實名認證。您的保母接單資格已生效，您現在可以隨時在設定頁面開啟接單狀態。
          </p>
        )}

        {kycStatus === 'SUSPENDED' && (
          <div
            style={{
              backgroundColor: 'rgba(239, 68, 68, 0.1)',
              border: '1px solid rgba(239, 68, 68, 0.2)',
              borderRadius: '8px',
              padding: '1rem'
            }}
          >
            <p style={{ fontSize: '0.9rem', color: '#f87171', fontWeight: '600', margin: 0 }}>
              您的帳號已被管理員停權暫停接單資格。
            </p>
            <p
              style={{
                fontSize: '0.85rem',
                color: 'var(--color-on-surface-variant)',
                marginTop: '0.5rem',
                marginBottom: 0
              }}
            >
              如有疑問，請聯絡客服支援。在此狀態下，您無法重新提交實名認證。
            </p>
          </div>
        )}

        {kycStatus === 'REJECTED' && (
          <div
            style={{
              backgroundColor: 'rgba(245, 158, 11, 0.1)',
              border: '1px solid rgba(245, 158, 11, 0.2)',
              borderRadius: '8px',
              padding: '1rem',
              marginBottom: '1rem'
            }}
          >
            <p style={{ fontSize: '0.9rem', color: '#fbbf24', fontWeight: '600', margin: 0 }}>
              前次認證申請已被駁回：
            </p>
            <p
              style={{
                fontSize: '0.9rem',
                color: 'var(--color-on-surface)',
                marginTop: '0.25rem',
                fontWeight: '500',
                marginBottom: 0
              }}
            >
              原因：{rejectReason || '未提供具體原因'}
            </p>
          </div>
        )}
      </Card>

      {/* 已提交的認證文件預覽 */}
      {kycStatus !== 'UNVERIFIED' && (submittedFrontUrl || submittedSelfieUrl) && (
        <Card style={{ padding: '1.5rem', marginBottom: '2rem' }}>
          <h3
            style={{
              fontSize: '1.1rem',
              fontWeight: '600',
              marginBottom: '1.25rem',
              color: 'var(--color-on-surface)'
            }}
          >
            已提交的認證文件預覽
          </h3>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
            <div>
              <span
                style={{
                  fontSize: '0.8rem',
                  color: 'var(--color-on-surface-variant)',
                  display: 'block',
                  marginBottom: '0.5rem'
                }}
              >
                身分證正面
              </span>
              {submittedFrontUrl ? (
                <div
                  style={{
                    borderRadius: '6px',
                    overflow: 'hidden',
                    border: '1px solid rgba(255, 255, 255, 0.1)',
                    aspectRatio: '1.58 / 1',
                    backgroundColor: '#111',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center'
                  }}
                >
                  <img
                    src={submittedFrontUrl}
                    alt="已提交身分證正面"
                    style={{ width: '100%', height: '100%', objectFit: 'contain' }}
                  />
                </div>
              ) : (
                <div
                  style={{
                    padding: '1rem',
                    textAlign: 'center',
                    backgroundColor: 'var(--color-surface-low)',
                    borderRadius: '6px',
                    fontSize: '0.8rem'
                  }}
                >
                  載入中...
                </div>
              )}
            </div>
            <div>
              <span
                style={{
                  fontSize: '0.8rem',
                  color: 'var(--color-on-surface-variant)',
                  display: 'block',
                  marginBottom: '0.5rem'
                }}
              >
                半身自拍照
              </span>
              {submittedSelfieUrl ? (
                <div
                  style={{
                    borderRadius: '6px',
                    overflow: 'hidden',
                    border: '1px solid rgba(255, 255, 255, 0.1)',
                    aspectRatio: '1',
                    backgroundColor: '#111',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center'
                  }}
                >
                  <img
                    src={submittedSelfieUrl}
                    alt="已提交自拍照"
                    style={{ width: '100%', height: '100%', objectFit: 'contain' }}
                  />
                </div>
              ) : (
                <div
                  style={{
                    padding: '1rem',
                    textAlign: 'center',
                    backgroundColor: 'var(--color-surface-low)',
                    borderRadius: '6px',
                    fontSize: '0.8rem'
                  }}
                >
                  載入中...
                </div>
              )}
            </div>
          </div>
        </Card>
      )}

      {/* 提交表單 (UNVERIFIED 或 REJECTED 狀態下顯示) */}
      {(kycStatus === 'UNVERIFIED' || kycStatus === 'REJECTED') && (
        <Card style={{ padding: '2rem' }}>
          <h3
            style={{
              fontSize: '1.2rem',
              fontWeight: '600',
              marginBottom: '1.5rem',
              color: 'var(--color-on-surface)'
            }}
          >
            {kycStatus === 'REJECTED' ? '重新提交認證文件' : '填寫實名認證文件'}
          </h3>

          <form
            onSubmit={handleSubmit}
            style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}
          >
            <div>
              <label
                style={{
                  display: 'block',
                  fontSize: '0.9rem',
                  fontWeight: '600',
                  marginBottom: '0.5rem',
                  color: 'var(--color-on-surface)'
                }}
              >
                身分證正面照片 <span style={{ color: 'red' }}>*</span>
              </label>
              <input
                type="file"
                accept="image/*"
                onChange={(e) => setIdCardFront(e.target.files?.[0] || null)}
                style={{
                  width: '100%',
                  padding: '8px',
                  backgroundColor: 'var(--color-surface-low)',
                  border: '1px solid rgba(255, 255, 255, 0.1)',
                  borderRadius: '8px',
                  color: 'var(--color-on-surface)'
                }}
              />
              <p
                style={{
                  fontSize: '0.75rem',
                  color: 'var(--color-on-surface-variant)',
                  marginTop: '0.25rem'
                }}
              >
                請確保身分證文字清晰，無遮擋或反光（限圖片檔案，大小不超過 5MB）
              </p>
            </div>

            <div>
              <label
                style={{
                  display: 'block',
                  fontSize: '0.9rem',
                  fontWeight: '600',
                  marginBottom: '0.5rem',
                  color: 'var(--color-on-surface)'
                }}
              >
                五官清晰半身自拍照 <span style={{ color: 'red' }}>*</span>
              </label>
              <input
                type="file"
                accept="image/*"
                onChange={(e) => setSelfie(e.target.files?.[0] || null)}
                style={{
                  width: '100%',
                  padding: '8px',
                  backgroundColor: 'var(--color-surface-low)',
                  border: '1px solid rgba(255, 255, 255, 0.1)',
                  borderRadius: '8px',
                  color: 'var(--color-on-surface)'
                }}
              />
              <p
                style={{
                  fontSize: '0.75rem',
                  color: 'var(--color-on-surface-variant)',
                  marginTop: '0.25rem'
                }}
              >
                請上傳面部無遮擋的正面自拍照（限圖片檔案，大小不超過 5MB）
              </p>
            </div>

            {errorMsg && (
              <div style={{ color: '#ef4444', fontSize: '0.9rem', fontWeight: '500' }}>
                ⚠️ {errorMsg}
              </div>
            )}

            {successMsg && (
              <div style={{ color: '#10b981', fontSize: '0.9rem', fontWeight: '500' }}>
                ✅ {successMsg}
              </div>
            )}

            <button
              type="submit"
              disabled={submitting}
              className="btn-primary"
              style={{
                padding: '12px',
                fontSize: '1rem',
                fontWeight: '600',
                borderRadius: '8px',
                width: '100%',
                marginTop: '0.5rem'
              }}
            >
              {submitting ? '提交資料中...' : '提交實名認證'}
            </button>
          </form>
        </Card>
      )}
    </div>
  );
};

const getStatusBadgeStyle = (status: string) => {
  switch (status) {
    case 'UNVERIFIED':
      return {
        backgroundColor: 'var(--color-surface-high)',
        color: 'var(--color-on-surface-variant)'
      };
    case 'PENDING_REVIEW':
      return { backgroundColor: 'rgba(59, 130, 246, 0.2)', color: '#60a5fa' };
    case 'VERIFIED':
      return { backgroundColor: 'rgba(16, 185, 129, 0.2)', color: '#34d399' };
    case 'REJECTED':
      return { backgroundColor: 'rgba(245, 158, 11, 0.2)', color: '#fbbf24' };
    case 'SUSPENDED':
      return { backgroundColor: 'rgba(239, 68, 68, 0.2)', color: '#f87171' };
    default:
      return {};
  }
};

const getStatusText = (status: string) => {
  switch (status) {
    case 'UNVERIFIED':
      return '未認證';
    case 'PENDING_REVIEW':
      return '審核中';
    case 'VERIFIED':
      return '已認證';
    case 'REJECTED':
      return '被駁回';
    case 'SUSPENDED':
      return '已停權';
    default:
      return status;
  }
};

export default SitterKycSubmit;
