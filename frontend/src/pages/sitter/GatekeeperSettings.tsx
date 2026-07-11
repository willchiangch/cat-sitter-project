import React, { useState, useEffect } from 'react';
import { Shield, Lock, Trash2, Plus, AlertCircle, CheckCircle2, Loader2 } from 'lucide-react';
import axiosClient from '../../api/axiosClient';

interface GatekeeperRule {
  id: string;
  sitterId: string;
  ruleType: string;
  scopeType: string;
  planId: string | null;
  targetUserId: string;
  targetEmail: string;
}

interface ServicePlan {
  id: string;
  name: string;
}

const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '0.625rem',
  borderRadius: '8px',
  border: '1px solid var(--color-surface-high)',
  backgroundColor: 'white',
  fontSize: '0.875rem',
  color: 'var(--color-on-surface)'
};

const labelStyle: React.CSSProperties = {
  display: 'block',
  fontSize: '0.75rem',
  fontWeight: 700,
  marginBottom: '0.375rem',
  color: 'var(--color-on-surface-variant)'
};

const cardStyle: React.CSSProperties = {
  backgroundColor: 'var(--color-surface-low)',
  borderRadius: '24px',
  padding: '1.5rem',
  boxShadow: 'var(--shadow-sm)'
};

export const GatekeeperSettings: React.FC = () => {
  const [rules, setRules] = useState<GatekeeperRule[]>([]);
  const [plans, setPlans] = useState<ServicePlan[]>([]);
  const [planTier, setPlanTier] = useState<string>('FREE');
  const [loading, setLoading] = useState<boolean>(true);
  const [actionLoading, setActionLoading] = useState<boolean>(false);
  const [errorMessage, setErrorMessage] = useState<string>('');
  const [successMessage, setSuccessMessage] = useState<string>('');

  // Form State
  const [targetEmail, setTargetEmail] = useState<string>('');
  const [ruleType, setRuleType] = useState<string>('BLACK');
  const [scopeType, setScopeType] = useState<string>('GLOBAL');
  const [selectedPlanId, setSelectedPlanId] = useState<string>('');

  const fetchSubscriptionAndData = async () => {
    try {
      setLoading(true);
      setErrorMessage('');

      // 1. Fetch Subscription
      const subRes = await axiosClient.get('/sitter/gatekeeper/subscription');
      setPlanTier(subRes.data.planTier || 'FREE');

      const isProOrUltimate = subRes.data.planTier === 'PRO' || subRes.data.planTier === 'ULTIMATE';

      if (isProOrUltimate) {
        // 2. Fetch Rules
        const rulesRes = await axiosClient.get('/sitter/gatekeeper');
        setRules(rulesRes.data);

        // 3. Fetch Plans
        const plansRes = await axiosClient.get('/sitter/plans');
        setPlans(plansRes.data.data || []);
      }
    } catch (err: any) {
      console.error(err);
      setErrorMessage('讀取門禁設定失敗，請確認網路連線');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchSubscriptionAndData();
  }, []);

  const handleMockUpgrade = async (tier: string) => {
    try {
      setActionLoading(true);
      await axiosClient.post('/sitter/gatekeeper/subscription/mock', { planTier: tier });
      setSuccessMessage(`已模擬切換訂閱方案為 ${tier}`);
      setTimeout(() => setSuccessMessage(''), 3000);
      await fetchSubscriptionAndData();
    } catch (err: any) {
      setErrorMessage('模擬切換方案失敗');
    } finally {
      setActionLoading(false);
    }
  };

  const handleAddRule = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMessage('');
    setSuccessMessage('');

    if (!targetEmail.trim()) {
      setErrorMessage('請輸入對象 Email');
      return;
    }

    if (scopeType === 'PLAN' && !selectedPlanId) {
      setErrorMessage('請選擇特定服務方案');
      return;
    }

    try {
      setActionLoading(true);
      await axiosClient.post('/sitter/gatekeeper', {
        targetEmail: targetEmail.trim(),
        ruleType,
        scopeType,
        planId: scopeType === 'PLAN' ? selectedPlanId : null
      });

      setSuccessMessage('成功新增門禁規則');
      setTargetEmail('');
      setSelectedPlanId('');
      setTimeout(() => setSuccessMessage(''), 3000);

      // Refresh
      const rulesRes = await axiosClient.get('/sitter/gatekeeper');
      setRules(rulesRes.data);
    } catch (err: any) {
      const msg =
        err.response?.data?.message || '新增門禁規則失敗，請檢查 Email 是否存在且未重複設定';
      setErrorMessage(msg);
    } finally {
      setActionLoading(false);
    }
  };

  const handleDeleteRule = async (ruleId: string) => {
    setErrorMessage('');
    setSuccessMessage('');
    try {
      setActionLoading(true);
      await axiosClient.delete(`/sitter/gatekeeper/${ruleId}`);
      setSuccessMessage('成功刪除門禁規則');
      setTimeout(() => setSuccessMessage(''), 3000);

      // Refresh
      const rulesRes = await axiosClient.get('/sitter/gatekeeper');
      setRules(rulesRes.data);
    } catch (err: any) {
      setErrorMessage('刪除門禁規則失敗');
    } finally {
      setActionLoading(false);
    }
  };

  const isLocked = planTier !== 'PRO' && planTier !== 'ULTIMATE';

  if (loading) {
    return (
      <div
        style={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          minHeight: '400px',
          gap: '1rem',
          color: 'var(--color-on-surface-variant)'
        }}
      >
        <Loader2 className="animate-spin" size={32} style={{ color: 'var(--color-primary)' }} />
        <p style={{ fontWeight: 600 }}>正在載入 Gatekeeper 門禁系統...</p>
      </div>
    );
  }

  // 1. SaaS Locked View
  if (isLocked) {
    return (
      <div style={{ padding: '1rem 0' }}>
        <div style={{ ...cardStyle, display: 'flex', flexDirection: 'column', alignItems: 'center', textAlign: 'center', gap: '1.25rem' }}>
          <div
            style={{
              width: '72px',
              height: '72px',
              borderRadius: '50%',
              backgroundColor: 'var(--color-primary-container)',
              color: 'var(--color-primary)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center'
            }}
          >
            <Lock size={32} />
          </div>

          <h2 style={{ margin: 0, fontSize: '1.5rem', fontWeight: 800, color: 'var(--color-on-surface)' }}>
            解鎖預約門禁系統 (Gatekeeper)
          </h2>

          <p style={{ margin: 0, color: 'var(--color-on-surface-variant)', lineHeight: 1.6, fontSize: '0.875rem' }}>
            門禁系統為專業版/頂級版保母專屬功能。透過黑白名單防禦、問卷豁免機制，讓您輕鬆掌握預約接單的主導權，避開惡意騷擾，專注於優質服務。
          </p>

          <div
            style={{
              width: '100%',
              padding: '1.25rem',
              backgroundColor: 'white',
              borderRadius: '16px',
              border: '1px solid var(--color-surface-high)',
              textAlign: 'left',
              display: 'flex',
              flexDirection: 'column',
              gap: '1rem'
            }}
          >
            <div style={{ display: 'flex', alignItems: 'flex-start', gap: '0.75rem' }}>
              <Shield size={20} style={{ color: 'var(--color-primary)', flexShrink: 0, marginTop: '2px' }} />
              <div>
                <h4 style={{ margin: 0, fontWeight: 700, fontSize: '0.9375rem', color: 'var(--color-on-surface)' }}>
                  專業版 (PRO)
                </h4>
                <p style={{ margin: '4px 0 0 0', fontSize: '0.8125rem', color: 'var(--color-on-surface-variant)' }}>
                  支援設定全域/方案級黑名單，阻擋特定客戶
                </p>
              </div>
            </div>
            <div style={{ display: 'flex', alignItems: 'flex-start', gap: '0.75rem' }}>
              <Shield size={20} style={{ color: 'var(--color-primary)', flexShrink: 0, marginTop: '2px' }} />
              <div>
                <h4 style={{ margin: 0, fontWeight: 700, fontSize: '0.9375rem', color: 'var(--color-on-surface)' }}>
                  頂級版 (ULTIMATE)
                </h4>
                <p style={{ margin: '4px 0 0 0', fontSize: '0.8125rem', color: 'var(--color-on-surface-variant)' }}>
                  解鎖全功能：黑/白名單雙重防禦、預約免填問卷豁免權
                </p>
              </div>
            </div>
          </div>

          <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.75rem', justifyContent: 'center', width: '100%' }}>
            <button
              onClick={() => handleMockUpgrade('PRO')}
              disabled={actionLoading}
              className="btn-primary"
              style={{ fontSize: '0.8125rem', padding: '0.625rem 1.25rem' }}
            >
              模擬升級專業版 (PRO)
            </button>
            <button
              onClick={() => handleMockUpgrade('ULTIMATE')}
              disabled={actionLoading}
              className="btn-primary"
              style={{ fontSize: '0.8125rem', padding: '0.625rem 1.25rem' }}
            >
              模擬升級頂級版 (ULTIMATE)
            </button>
          </div>
        </div>
      </div>
    );
  }

  // 2. Active Sitter Settings View
  return (
    <div style={{ padding: '1rem 0', display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
      {/* Header with status */}
      <div
        style={{
          padding: '1.5rem',
          borderRadius: '24px',
          background: 'var(--color-primary-gradient)',
          color: 'var(--color-on-primary)',
          display: 'flex',
          flexDirection: 'column',
          gap: '1rem'
        }}
      >
        <div>
          <h2 style={{ margin: 0, fontSize: '1.25rem', fontWeight: 700, display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Shield size={22} /> 預約門禁管理系統
          </h2>
          <p style={{ margin: '4px 0 0 0', fontSize: '0.8125rem', opacity: 0.9 }}>
            設定黑白名單防禦機制與問卷填寫規則，全面掌控您的接單權限。
          </p>
        </div>
        <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '0.5rem' }}>
          <span
            style={{
              padding: '4px 12px',
              backgroundColor: 'rgba(255,255,255,0.2)',
              borderRadius: '9999px',
              fontWeight: 700,
              fontSize: '0.7rem',
              textTransform: 'uppercase',
              letterSpacing: '0.05em'
            }}
          >
            方案等級: {planTier}
          </span>
          {planTier === 'PRO' && (
            <button
              onClick={() => handleMockUpgrade('ULTIMATE')}
              disabled={actionLoading}
              style={{
                padding: '4px 12px',
                backgroundColor: 'rgba(255,255,255,0.15)',
                border: '1px solid rgba(255,255,255,0.3)',
                borderRadius: '9999px',
                fontSize: '0.7rem',
                fontWeight: 700,
                color: 'inherit',
                cursor: 'pointer'
              }}
            >
              模擬升級頂級版 (ULTIMATE)
            </button>
          )}
          <button
            onClick={() => handleMockUpgrade('FREE')}
            disabled={actionLoading}
            style={{
              padding: '4px 12px',
              backgroundColor: 'rgba(0,0,0,0.15)',
              border: '1px solid rgba(255,255,255,0.2)',
              borderRadius: '9999px',
              fontSize: '0.7rem',
              fontWeight: 700,
              color: 'inherit',
              cursor: 'pointer'
            }}
          >
            模擬降級 Free
          </button>
        </div>
      </div>

      {/* Messages */}
      {errorMessage && (
        <div
          style={{
            padding: '1rem',
            backgroundColor: '#fef2f2',
            border: '1px solid #fee2e2',
            borderRadius: '16px',
            display: 'flex',
            alignItems: 'flex-start',
            gap: '8px'
          }}
        >
          <AlertCircle size={18} style={{ color: '#ef4444', flexShrink: 0, marginTop: '1px' }} />
          <span style={{ fontSize: '0.8125rem', fontWeight: 600, color: '#7f1d1d' }}>{errorMessage}</span>
        </div>
      )}
      {successMessage && (
        <div
          style={{
            padding: '1rem',
            backgroundColor: '#ecfdf3',
            border: '1px solid #abefc6',
            borderRadius: '16px',
            display: 'flex',
            alignItems: 'flex-start',
            gap: '8px'
          }}
        >
          <CheckCircle2 size={18} style={{ color: '#027a48', flexShrink: 0, marginTop: '1px' }} />
          <span style={{ fontSize: '0.8125rem', fontWeight: 600, color: '#027a48' }}>{successMessage}</span>
        </div>
      )}

      {/* 新增門禁規則 */}
      <div style={cardStyle}>
        <h3
          style={{
            margin: '0 0 1.25rem 0',
            fontSize: '1.0625rem',
            fontWeight: 700,
            color: 'var(--color-on-surface)',
            borderBottom: '1px solid var(--color-surface-high)',
            paddingBottom: '0.75rem'
          }}
        >
          新增門禁規則
        </h3>

        <form onSubmit={handleAddRule} style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
          <div>
            <label style={labelStyle}>飼主帳號 Email</label>
            <input
              type="email"
              required
              placeholder="輸入完整飼主電子信箱"
              value={targetEmail}
              onChange={(e) => setTargetEmail(e.target.value)}
              data-testid="input-target-email"
              style={inputStyle}
            />
          </div>

          <div>
            <label style={labelStyle}>作用範圍</label>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.5rem' }}>
              <button
                type="button"
                onClick={() => {
                  setScopeType('GLOBAL');
                  setSelectedPlanId('');
                }}
                data-testid="btn-scope-global"
                style={{
                  padding: '0.5rem 0.75rem',
                  fontSize: '0.8125rem',
                  fontWeight: 600,
                  borderRadius: '8px',
                  cursor: 'pointer',
                  border: scopeType === 'GLOBAL' ? '1px solid var(--color-primary)' : '1px solid var(--color-surface-high)',
                  backgroundColor: scopeType === 'GLOBAL' ? 'var(--color-primary-container)' : 'white',
                  color: scopeType === 'GLOBAL' ? 'var(--color-primary)' : 'var(--color-on-surface-variant)'
                }}
              >
                全域
              </button>
              <button
                type="button"
                onClick={() => setScopeType('PLAN')}
                data-testid="btn-scope-plan"
                style={{
                  padding: '0.5rem 0.75rem',
                  fontSize: '0.8125rem',
                  fontWeight: 600,
                  borderRadius: '8px',
                  cursor: 'pointer',
                  border: scopeType === 'PLAN' ? '1px solid var(--color-primary)' : '1px solid var(--color-surface-high)',
                  backgroundColor: scopeType === 'PLAN' ? 'var(--color-primary-container)' : 'white',
                  color: scopeType === 'PLAN' ? 'var(--color-primary)' : 'var(--color-on-surface-variant)'
                }}
              >
                特定方案
              </button>
            </div>
          </div>

          {scopeType === 'PLAN' && (
            <div>
              <label style={labelStyle}>選擇適用方案</label>
              <select
                required
                value={selectedPlanId}
                onChange={(e) => setSelectedPlanId(e.target.value)}
                data-testid="select-plan"
                style={inputStyle}
              >
                <option value="">請選擇方案</option>
                {plans.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
            </div>
          )}

          <div>
            <label style={labelStyle}>規則類型</label>
            <select
              value={ruleType}
              onChange={(e) => setRuleType(e.target.value)}
              data-testid="select-rule-type"
              style={inputStyle}
            >
              <option value="BLACK">黑名單 (封鎖預約)</option>
              <option value="WHITE" disabled={planTier === 'PRO'}>
                白名單 (僅限此飼主) {planTier === 'PRO' && '🔒(Ultimate限定)'}
              </option>
              <option value="NO_QUESTIONNAIRE" disabled={planTier === 'PRO'}>
                免填問卷豁免 {planTier === 'PRO' && '🔒(Ultimate限定)'}
              </option>
            </select>
          </div>

          <button
            type="submit"
            disabled={actionLoading}
            className="btn-primary"
            style={{
              width: '100%',
              padding: '0.625rem',
              fontSize: '0.875rem',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '6px'
            }}
          >
            <Plus size={16} /> 新增門禁規則
          </button>
        </form>
      </div>

      {/* 目前門禁設定清單 */}
      <div style={cardStyle}>
        <h3
          style={{
            margin: '0 0 1.25rem 0',
            fontSize: '1.0625rem',
            fontWeight: 700,
            color: 'var(--color-on-surface)',
            borderBottom: '1px solid var(--color-surface-high)',
            paddingBottom: '0.75rem'
          }}
        >
          目前門禁設定清單
        </h3>

        {rules.length === 0 ? (
          <div
            style={{
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              padding: '3rem 0',
              gap: '0.5rem',
              color: 'var(--color-on-surface-variant)'
            }}
          >
            <Shield size={40} strokeWidth={1.5} />
            <p style={{ margin: 0, fontSize: '0.875rem', fontWeight: 600 }}>尚未設定任何門禁規則</p>
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
            {rules.map((rule) => {
              const matchedPlan = plans.find((p) => p.id === rule.planId);
              const ruleTypeConfig =
                rule.ruleType === 'BLACK'
                  ? { label: '黑名單', bg: '#fef2f2', color: '#dc2626' }
                  : rule.ruleType === 'WHITE'
                    ? { label: '白名單', bg: '#ecfdf3', color: '#16a34a' }
                    : { label: '免問卷', bg: '#eff6ff', color: '#2563eb' };
              return (
                <div
                  key={rule.id}
                  data-testid="gatekeeper-rule-row"
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    padding: '0.875rem 1rem',
                    backgroundColor: 'white',
                    borderRadius: '16px',
                    border: '1px solid var(--color-surface-high)',
                    gap: '0.75rem'
                  }}
                >
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '4px', minWidth: 0 }}>
                    <span style={{ fontWeight: 700, fontSize: '0.875rem', color: 'var(--color-on-surface)' }}>
                      {rule.targetEmail}
                    </span>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '6px', flexWrap: 'wrap' }}>
                      <span
                        style={{
                          padding: '2px 10px',
                          borderRadius: '9999px',
                          fontSize: '0.7rem',
                          fontWeight: 700,
                          backgroundColor: ruleTypeConfig.bg,
                          color: ruleTypeConfig.color
                        }}
                      >
                        {ruleTypeConfig.label}
                      </span>
                      <span style={{ fontSize: '0.75rem', color: 'var(--color-on-surface-variant)' }}>
                        {rule.scopeType === 'GLOBAL' ? '全域' : matchedPlan ? matchedPlan.name : '特定方案'}
                      </span>
                    </div>
                  </div>
                  <button
                    onClick={() => handleDeleteRule(rule.id)}
                    disabled={actionLoading}
                    title="刪除"
                    style={{
                      flexShrink: 0,
                      padding: '8px',
                      backgroundColor: 'transparent',
                      border: 'none',
                      borderRadius: '8px',
                      color: '#ef4444',
                      cursor: 'pointer',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center'
                    }}
                  >
                    <Trash2 size={16} />
                  </button>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
};
