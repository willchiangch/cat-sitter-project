import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import Card from '../../components/ui/Card';
import { getKycDetail, getAdminMediaUrl, reviewKyc } from '../../api/kycApi';
import type { KycRecordDetailDto } from '../../api/kycApi';
import { useRole } from '../../contexts/RoleContext';
import { kycRecordStatusLabel } from '../../components/ui/statusLabels';

interface AdminKycDetailProps {
  kycRecordId: string;
}

const AdminKycDetail: React.FC<AdminKycDetailProps> = ({ kycRecordId }) => {
  const { currentRole } = useRole();
  const navigate = useNavigate();
  const [detail, setDetail] = useState<KycRecordDetailDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [errorMsg, setErrorMsg] = useState('');
  const [successMsg, setSuccessMsg] = useState('');
  const [idCardFrontUrl, setIdCardFrontUrl] = useState('');
  const [selfieUrl, setSelfieUrl] = useState('');

  const [rejectReason, setRejectReason] = useState('');
  const [actionLoading, setActionLoading] = useState(false);
  const [showRejectInput, setShowRejectInput] = useState(false);
  const [idempotencyKey, setIdempotencyKey] = useState('');
  // 審核送出成功後到導回清單前的 1.5 秒：徽章立即反映結果、按鈕鎖住 (Backlog 第 3 批 U2)
  const [reviewed, setReviewed] = useState(false);

  useEffect(() => {
    setIdempotencyKey('review-' + kycRecordId + '-' + Date.now());
    fetchDetail();
  }, [kycRecordId]);

  const fetchDetail = async () => {
    setLoading(true);
    setErrorMsg('');
    try {
      const data = await getKycDetail(kycRecordId);
      setDetail(data);

      const [frontUrl, backUrl] = await Promise.all([
        getAdminMediaUrl(data.sitterId, 'id-front'),
        getAdminMediaUrl(data.sitterId, 'selfie')
      ]);
      setIdCardFrontUrl(frontUrl);
      setSelfieUrl(backUrl);
    } catch (err: any) {
      console.error(err);
      setErrorMsg('取得認證紀錄詳情失敗');
    } finally {
      setLoading(false);
    }
  };

  const handleReview = async (action: 'APPROVE' | 'REJECT') => {
    setErrorMsg('');
    setSuccessMsg('');
    if (action === 'REJECT' && !rejectReason.trim()) {
      setErrorMsg('請輸入駁回原因');
      return;
    }

    setActionLoading(true);
    try {
      await reviewKyc(
        kycRecordId,
        action,
        action === 'REJECT' ? rejectReason : null,
        idempotencyKey
      );
      setSuccessMsg(action === 'APPROVE' ? '實名認證審核已通過！' : '實名認證已被駁回。');
      // 過去只顯示成功訊息，徽章要等導回清單前都還是 PENDING，看起來像沒生效
      setDetail((prev) =>
        prev ? { ...prev, kycStatus: action === 'APPROVE' ? 'APPROVED' : 'REJECTED' } : prev
      );
      setReviewed(true);
      setTimeout(() => {
        navigate('/admin/kyc');
      }, 1500);
    } catch (err: any) {
      console.error(err);
      const msg = err.response?.data?.message || '審核提交失敗';
      setErrorMsg(msg);
      setIdempotencyKey('review-' + kycRecordId + '-' + Date.now());
    } finally {
      setActionLoading(false);
    }
  };

  if (loading) {
    return (
      <div
        style={{ padding: '3rem', textAlign: 'center', color: 'var(--color-on-surface-variant)' }}
      >
        載入 KYC 申請詳情中...
      </div>
    );
  }

  if (!detail) {
    return (
      <div
        style={{ padding: '3rem', textAlign: 'center', color: 'var(--color-on-surface-variant)' }}
      >
        找不到該筆審查紀錄
      </div>
    );
  }

  return (
    <div
      style={{
        // <main> 已有 1.5rem 左右內距，這裡不再疊加，否則照片只剩約 240px 寬
        padding: '1.5rem 0',
        fontFamily: 'var(--font-sans)',
        maxWidth: '1080px',
        margin: '0 auto'
      }}
    >
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          flexWrap: 'wrap',
          gap: '0.75rem',
          marginBottom: '1.5rem'
        }}
      >
        <h2
          style={{
            fontSize: '1.75rem',
            fontWeight: '700',
            color: 'var(--color-on-surface)',
            margin: 0
          }}
        >
          管理端 - 實名認證審查
        </h2>
        <button
          className="btn-secondary"
          onClick={() => navigate('/admin/kyc')}
          style={{ padding: '8px 16px', fontSize: '0.9rem' }}
        >
          返回待審清單
        </button>
      </div>

      {currentRole !== 'admin' && (
        <div
          style={{
            padding: '1rem',
            backgroundColor: '#f8d7da',
            color: '#721c24',
            borderRadius: '8px',
            marginBottom: '1.5rem',
            fontWeight: '600'
          }}
        >
          ⚠️ 警告：當前角色非管理員，無法調用後端審查 API。請先切換為管理員角色。
        </div>
      )}

      {/* AppShell 是 390px 行動版寬度：過去用 '400px 1fr' 兩欄 grid，左欄就超過可用寬度，
          右欄的證件照/自拍被 AppShell overflow:hidden 裁掉，管理員看不到要審的照片 (Backlog 第 3 批 U1)。
          改成單欄：基本資料 → 媒體預覽 → 審核決策（先看照片再決定） */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem', minWidth: 0 }}>
        <Card style={{ padding: '1.5rem' }}>
          <h3
            style={{
              fontSize: '1.2rem',
              fontWeight: '600',
              marginBottom: '1.25rem',
              color: 'var(--color-on-surface)'
            }}
          >
            申請者基本資料
          </h3>

          <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            <div>
              <span
                style={{
                  fontSize: '0.8rem',
                  color: 'var(--color-on-surface-variant)',
                  display: 'block'
                }}
              >
                保母 ID
              </span>
              <span style={{ fontSize: '0.9rem', fontWeight: '600', wordBreak: 'break-all' }}>
                {detail.sitterId}
              </span>
            </div>
            <div>
              <span
                style={{
                  fontSize: '0.8rem',
                  color: 'var(--color-on-surface-variant)',
                  display: 'block'
                }}
              >
                真實姓名
              </span>
              <span style={{ fontSize: '1rem', fontWeight: '600' }}>{detail.fullName}</span>
            </div>
            <div>
              <span
                style={{
                  fontSize: '0.8rem',
                  color: 'var(--color-on-surface-variant)',
                  display: 'block'
                }}
              >
                電子信箱
              </span>
              <span style={{ fontSize: '0.95rem' }}>{detail.email}</span>
            </div>
            <div>
              <span
                style={{
                  fontSize: '0.8rem',
                  color: 'var(--color-on-surface-variant)',
                  display: 'block'
                }}
              >
                申請時間
              </span>
              <span style={{ fontSize: '0.9rem' }}>
                {new Date(detail.submittedAt).toLocaleString()}
              </span>
            </div>
            <div>
              <span
                style={{
                  fontSize: '0.8rem',
                  color: 'var(--color-on-surface-variant)',
                  display: 'block'
                }}
              >
                認證狀態
              </span>
              <span
                data-testid="admin-kyc-detail-status-badge"
                style={{
                  display: 'inline-block',
                  marginTop: '0.25rem',
                  padding: '4px 10px',
                  borderRadius: '9999px',
                  fontSize: '0.8rem',
                  fontWeight: '700',
                  ...getKycBadgeStyle(detail.kycStatus)
                }}
              >
                {kycRecordStatusLabel(detail.kycStatus)}
              </span>
            </div>
          </div>
        </Card>

        <Card style={{ padding: '1.5rem' }}>
          <h3
            style={{
              fontSize: '1.2rem',
              fontWeight: '600',
              marginBottom: '1.5rem',
              color: 'var(--color-on-surface)'
            }}
          >
            認證媒體檔案預覽
          </h3>

          <div
            style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}
            data-testid="admin-kyc-detail-media"
          >
            <div>
              <h4
                style={{
                  fontSize: '0.95rem',
                  fontWeight: '600',
                  marginBottom: '0.75rem',
                  color: 'var(--color-on-surface-variant)'
                }}
              >
                身分證正面照片
              </h4>
              {idCardFrontUrl ? (
                <div
                  style={{
                    borderRadius: '8px',
                    overflow: 'hidden',
                    border: '1px solid rgba(255, 255, 255, 0.1)',
                    aspectRatio: '1.58 / 1',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    backgroundColor: '#111'
                  }}
                >
                  <img
                    src={idCardFrontUrl}
                    alt="身分證正面"
                    data-testid="admin-kyc-detail-id-front-img"
                    style={{ width: '100%', height: '100%', objectFit: 'contain' }}
                  />
                </div>
              ) : (
                <div
                  style={{
                    padding: '2rem',
                    textAlign: 'center',
                    backgroundColor: 'var(--color-surface-low)',
                    borderRadius: '8px'
                  }}
                >
                  無法載入證件照片
                </div>
              )}
            </div>

            <div>
              <h4
                style={{
                  fontSize: '0.95rem',
                  fontWeight: '600',
                  marginBottom: '0.75rem',
                  color: 'var(--color-on-surface-variant)'
                }}
              >
                五官清晰半身自拍照
              </h4>
              {selfieUrl ? (
                <div
                  style={{
                    borderRadius: '8px',
                    overflow: 'hidden',
                    border: '1px solid rgba(255, 255, 255, 0.1)',
                    aspectRatio: '1',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    backgroundColor: '#111'
                  }}
                >
                  <img
                    src={selfieUrl}
                    alt="自拍照"
                    data-testid="admin-kyc-detail-selfie-img"
                    style={{ width: '100%', height: '100%', objectFit: 'contain' }}
                  />
                </div>
              ) : (
                <div
                  style={{
                    padding: '2rem',
                    textAlign: 'center',
                    backgroundColor: 'var(--color-surface-low)',
                    borderRadius: '8px'
                  }}
                >
                  無法載入自拍照
                </div>
              )}
            </div>
          </div>
        </Card>
        <Card style={{ padding: '1.5rem' }}>
          <h3
            style={{
              fontSize: '1.2rem',
              fontWeight: '600',
              marginBottom: '1.25rem',
              color: 'var(--color-on-surface)'
            }}
          >
            審核決策
          </h3>

          {errorMsg && (
            <div
              style={{
                color: '#ef4444',
                fontSize: '0.9rem',
                fontWeight: '500',
                marginBottom: '1rem'
              }}
            >
              ⚠️ {errorMsg}
            </div>
          )}

          {successMsg && (
            <div
              style={{
                color: '#10b981',
                fontSize: '0.9rem',
                fontWeight: '500',
                marginBottom: '1rem'
              }}
            >
              ✅ {successMsg}
            </div>
          )}

          {!showRejectInput ? (
            <div style={{ display: 'flex', gap: '1rem' }}>
              <button
                onClick={() => handleReview('APPROVE')}
                disabled={actionLoading || reviewed}
                className="btn-primary"
                data-testid="admin-kyc-detail-approve-btn"
                style={{
                  flex: 1,
                  padding: '12px',
                  fontSize: '0.95rem',
                  fontWeight: '600',
                  borderRadius: '8px',
                  backgroundColor: '#10b981'
                }}
              >
                批准認證
              </button>
              <button
                onClick={() => setShowRejectInput(true)}
                disabled={actionLoading || reviewed}
                style={{
                  flex: 1,
                  padding: '12px',
                  fontSize: '0.95rem',
                  fontWeight: '600',
                  borderRadius: '8px',
                  border: '1px solid rgba(239, 68, 68, 0.5)',
                  backgroundColor: 'transparent',
                  color: '#ef4444',
                  cursor: 'pointer'
                }}
              >
                駁回申請
              </button>
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
              <div>
                <label
                  style={{
                    display: 'block',
                    fontSize: '0.85rem',
                    fontWeight: '600',
                    marginBottom: '0.5rem'
                  }}
                >
                  請填寫駁回原因 <span style={{ color: 'red' }}>*</span>
                </label>
                <textarea
                  value={rejectReason}
                  onChange={(e) => setRejectReason(e.target.value)}
                  placeholder="請詳細敘述駁回原因，例如：證件照片模糊、自拍與證件不符..."
                  rows={4}
                  style={{
                    width: '100%',
                    padding: '8px',
                    borderRadius: '6px',
                    border: '1px solid rgba(255,255,255,0.1)',
                    backgroundColor: 'var(--color-surface-low)',
                    color: 'white',
                    fontSize: '0.875rem',
                    resize: 'none'
                  }}
                />
              </div>

              <div style={{ display: 'flex', gap: '0.5rem' }}>
                <button
                  onClick={() => handleReview('REJECT')}
                  disabled={actionLoading || reviewed}
                  style={{
                    flex: 1,
                    padding: '10px',
                    backgroundColor: '#ef4444',
                    color: 'white',
                    border: 'none',
                    borderRadius: '6px',
                    fontWeight: '600',
                    cursor: 'pointer'
                  }}
                >
                  確定駁回
                </button>
                <button
                  onClick={() => {
                    setShowRejectInput(false);
                    setRejectReason('');
                  }}
                  disabled={actionLoading || reviewed}
                  style={{
                    padding: '10px 15px',
                    backgroundColor: 'var(--color-surface-low)',
                    color: 'var(--color-on-surface)',
                    border: '1px solid rgba(255,255,255,0.1)',
                    borderRadius: '6px',
                    cursor: 'pointer'
                  }}
                >
                  取消
                </button>
              </div>
            </div>
          )}
        </Card>
      </div>
    </div>
  );
};

const getKycBadgeStyle = (status: string) => {
  switch (status) {
    case 'PENDING':
      return { backgroundColor: 'rgba(59, 130, 246, 0.2)', color: '#60a5fa' };
    case 'APPROVED':
      return { backgroundColor: 'rgba(16, 185, 129, 0.2)', color: '#34d399' };
    case 'REJECTED':
      return { backgroundColor: 'rgba(245, 158, 11, 0.2)', color: '#fbbf24' };
    default:
      return {
        backgroundColor: 'var(--color-surface-high)',
        color: 'var(--color-on-surface-variant)'
      };
  }
};

export default AdminKycDetail;
