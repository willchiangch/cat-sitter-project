import React, { useState, useEffect } from 'react';
import { Shield, Lock, Trash2, Plus, AlertCircle, CheckCircle2, RefreshCw } from 'lucide-react';
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
      const msg = err.response?.data?.message || '新增門禁規則失敗，請檢查 Email 是否存在且未重複設定';
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
      <div className="flex flex-col items-center justify-center min-h-[400px] space-y-4">
        <RefreshCw className="w-8 h-8 animate-spin text-purple-600" />
        <p className="text-gray-500 font-medium">正在載入 Gatekeeper 門禁系統...</p>
      </div>
    );
  }

  // 1. SaaS Locked View
  if (isLocked) {
    return (
      <div className="max-w-3xl mx-auto my-12 p-8 rounded-3xl backdrop-blur-md bg-white/80 dark:bg-zinc-900/80 border border-white/20 dark:border-zinc-800/20 shadow-2xl transition-all duration-300">
        <div className="flex flex-col items-center text-center space-y-6">
          <div className="p-4 bg-purple-50 dark:bg-purple-900/30 rounded-full text-purple-600 animate-pulse">
            <Lock className="w-16 h-16" />
          </div>
          
          <h2 className="text-3xl font-extrabold tracking-tight bg-gradient-to-r from-purple-600 to-indigo-600 bg-clip-text text-transparent">
            解鎖預約門禁系統 (Gatekeeper)
          </h2>
          
          <p className="text-gray-600 dark:text-gray-300 max-w-lg leading-relaxed">
            門禁系統為專業版/頂級版保母專屬功能。透過黑白名單防禦、問卷豁免機制，讓您輕鬆掌握預約接單的主導權，避開惡意騷擾，專注於優質服務。
          </p>

          <div className="w-full max-w-md p-6 bg-purple-50/50 dark:bg-zinc-800/50 rounded-2xl border border-purple-100 dark:border-zinc-700/50 text-left space-y-4">
            <div className="flex items-start gap-3">
              <Shield className="w-5 h-5 text-purple-600 shrink-0 mt-0.5" />
              <div>
                <h4 className="font-semibold text-gray-800 dark:text-gray-100">專業版 (PRO)</h4>
                <p className="text-sm text-gray-500">支援設定全域/方案級黑名單，阻擋特定客戶</p>
              </div>
            </div>
            <div className="flex items-start gap-3">
              <Shield className="w-5 h-5 text-indigo-600 shrink-0 mt-0.5" />
              <div>
                <h4 className="font-semibold text-gray-800 dark:text-gray-100">頂級版 (ULTIMATE)</h4>
                <p className="text-sm text-gray-500">解鎖全功能：黑/白名單雙重防禦、預約免填問卷豁免權</p>
              </div>
            </div>
          </div>

          <div className="flex flex-wrap gap-4 justify-center w-full pt-4">
            <button
              onClick={() => handleMockUpgrade('PRO')}
              disabled={actionLoading}
              className="px-6 py-3 bg-gradient-to-r from-purple-500 to-indigo-500 hover:from-purple-600 hover:to-indigo-600 text-white rounded-xl font-semibold shadow-lg shadow-purple-500/20 active:scale-95 transition-all duration-150 disabled:opacity-50"
            >
              模擬升級專業版 (PRO)
            </button>
            <button
              onClick={() => handleMockUpgrade('ULTIMATE')}
              disabled={actionLoading}
              className="px-6 py-3 bg-gradient-to-r from-indigo-500 to-purple-600 hover:from-indigo-600 hover:to-purple-700 text-white rounded-xl font-semibold shadow-lg shadow-indigo-500/20 active:scale-95 transition-all duration-150 disabled:opacity-50"
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
    <div className="max-w-5xl mx-auto px-4 py-8 space-y-8">
      {/* Header with status */}
      <div className="flex flex-col md:flex-row md:items-center md:justify-between p-6 bg-gradient-to-r from-purple-600 to-indigo-600 rounded-3xl text-white shadow-xl shadow-indigo-500/10">
        <div>
          <h2 className="text-2xl font-bold flex items-center gap-2">
            <Shield className="w-6 h-6" /> 預約門禁管理系統
          </h2>
          <p className="text-purple-100 text-sm mt-1">
            設定黑白名單防禦機制與問卷填寫規則，全面掌控您的接單權限。
          </p>
        </div>
        <div className="mt-4 md:mt-0 flex items-center gap-3">
          <span className="px-3 py-1 bg-white/20 rounded-full font-bold text-xs uppercase tracking-wider backdrop-blur-sm border border-white/10">
            方案等級: {planTier}
          </span>
          {planTier === 'PRO' && (
            <button
              onClick={() => handleMockUpgrade('ULTIMATE')}
              disabled={actionLoading}
              className="px-3 py-1 bg-green-500/30 hover:bg-green-500/55 border border-green-400/30 rounded-full text-xs font-semibold active:scale-95 transition-all"
            >
              模擬升級頂級版 (ULTIMATE)
            </button>
          )}
          <button
            onClick={() => handleMockUpgrade('FREE')}
            disabled={actionLoading}
            className="px-3 py-1 bg-red-500/30 hover:bg-red-500/50 border border-red-400/30 rounded-full text-xs font-semibold active:scale-95 transition-all"
          >
            模擬降級 Free
          </button>
        </div>
      </div>

      {/* Messages */}
      {errorMessage && (
        <div className="p-4 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 text-red-600 dark:text-red-400 rounded-2xl flex items-start gap-2">
          <AlertCircle className="w-5 h-5 shrink-0 mt-0.5" />
          <span className="text-sm font-medium">{errorMessage}</span>
        </div>
      )}
      {successMessage && (
        <div className="p-4 bg-green-50 dark:bg-green-900/20 border border-green-200 dark:border-green-800 text-green-600 dark:text-green-400 rounded-2xl flex items-start gap-2">
          <CheckCircle2 className="w-5 h-5 shrink-0 mt-0.5" />
          <span className="text-sm font-medium">{successMessage}</span>
        </div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
        {/* Left Form */}
        <div className="lg:col-span-1 p-6 bg-white dark:bg-zinc-900 rounded-2xl border border-gray-100 dark:border-zinc-800 shadow-sm space-y-6">
          <h3 className="text-lg font-bold text-gray-800 dark:text-gray-100 border-b pb-3 border-gray-100 dark:border-zinc-800">
            新增門禁規則
          </h3>

          <form onSubmit={handleAddRule} className="space-y-4">
            {/* Target Email */}
            <div className="space-y-1.5">
              <label className="text-sm font-semibold text-gray-600 dark:text-gray-400">飼主帳號 Email</label>
              <input
                type="email"
                required
                placeholder="輸入完整飼主電子信箱"
                value={targetEmail}
                onChange={(e) => setTargetEmail(e.target.value)}
                data-testid="input-target-email"
                className="w-full px-4 py-2 border rounded-xl dark:bg-zinc-800 dark:border-zinc-700 focus:outline-none focus:ring-2 focus:ring-purple-500 focus:border-transparent text-sm"
              />
            </div>

            {/* Scope Type */}
            <div className="space-y-1.5">
              <label className="text-sm font-semibold text-gray-600 dark:text-gray-400">作用範圍</label>
              <div className="grid grid-cols-2 gap-2">
                <button
                  type="button"
                  onClick={() => {
                    setScopeType('GLOBAL');
                    setSelectedPlanId('');
                  }}
                  data-testid="btn-scope-global"
                  className={`py-2 px-3 text-sm font-medium rounded-xl border transition-all ${
                    scopeType === 'GLOBAL'
                      ? 'bg-purple-50 dark:bg-purple-900/20 border-purple-500 text-purple-600'
                      : 'border-gray-200 dark:border-zinc-700 text-gray-600 dark:text-gray-400'
                  }`}
                >
                  全域
                </button>
                <button
                  type="button"
                  onClick={() => setScopeType('PLAN')}
                  data-testid="btn-scope-plan"
                  className={`py-2 px-3 text-sm font-medium rounded-xl border transition-all ${
                    scopeType === 'PLAN'
                      ? 'bg-purple-50 dark:bg-purple-900/20 border-purple-500 text-purple-600'
                      : 'border-gray-200 dark:border-zinc-700 text-gray-600 dark:text-gray-400'
                  }`}
                >
                  特定方案
                </button>
              </div>
            </div>

            {/* Plan Selector (if PLAN scope) */}
            {scopeType === 'PLAN' && (
              <div className="space-y-1.5 animate-fadeIn">
                <label className="text-sm font-semibold text-gray-600 dark:text-gray-400">選擇適用方案</label>
                <select
                  required
                  value={selectedPlanId}
                  onChange={(e) => setSelectedPlanId(e.target.value)}
                  data-testid="select-plan"
                  className="w-full px-4 py-2 border rounded-xl dark:bg-zinc-800 dark:border-zinc-700 focus:outline-none focus:ring-2 focus:ring-purple-500 focus:border-transparent text-sm"
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

            {/* Rule Type */}
            <div className="space-y-1.5">
              <label className="text-sm font-semibold text-gray-600 dark:text-gray-400">規則類型</label>
              <select
                value={ruleType}
                onChange={(e) => setRuleType(e.target.value)}
                data-testid="select-rule-type"
                className="w-full px-4 py-2 border rounded-xl dark:bg-zinc-800 dark:border-zinc-700 focus:outline-none focus:ring-2 focus:ring-purple-500 focus:border-transparent text-sm"
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

            {/* Submit Button */}
            <button
              type="submit"
              disabled={actionLoading}
              className="w-full py-2.5 bg-gradient-to-r from-purple-600 to-indigo-600 hover:from-purple-700 hover:to-indigo-700 text-white rounded-xl font-semibold shadow-md active:scale-95 transition-all duration-150 flex items-center justify-center gap-1 text-sm disabled:opacity-50"
            >
              <Plus className="w-4 h-4" /> 新增門禁規則
            </button>
          </form>
        </div>

        {/* Right Rules List */}
        <div className="lg:col-span-2 p-6 bg-white dark:bg-zinc-900 rounded-2xl border border-gray-100 dark:border-zinc-800 shadow-sm flex flex-col space-y-4">
          <h3 className="text-lg font-bold text-gray-800 dark:text-gray-100 border-b pb-3 border-gray-100 dark:border-zinc-800">
            目前門禁設定清單
          </h3>

          {rules.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-12 text-gray-400 dark:text-zinc-500 space-y-2">
              <Shield className="w-12 h-12 stroke-[1.5]" />
              <p className="text-sm font-medium">尚未設定任何門禁規則</p>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left border-collapse">
                <thead>
                  <tr className="border-b border-gray-100 dark:border-zinc-800 text-xs font-semibold text-gray-400 dark:text-zinc-500">
                    <th className="pb-3 font-medium">飼主帳號 (遮蔽)</th>
                    <th className="pb-3 font-medium">類型</th>
                    <th className="pb-3 font-medium">範圍</th>
                    <th className="pb-3 font-medium">適用方案</th>
                    <th className="pb-3 text-right font-medium">動作</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-50 dark:divide-zinc-800 text-sm">
                  {rules.map((rule) => {
                    const matchedPlan = plans.find((p) => p.id === rule.planId);
                    return (
                      <tr key={rule.id} className="text-gray-700 dark:text-gray-300">
                        <td className="py-3.5 font-medium">{rule.targetEmail}</td>
                        <td className="py-3.5">
                          <span
                            className={`px-2.5 py-0.5 rounded-full text-xs font-semibold ${
                              rule.ruleType === 'BLACK'
                                ? 'bg-red-50 dark:bg-red-900/20 text-red-600'
                                : rule.ruleType === 'WHITE'
                                ? 'bg-green-50 dark:bg-green-900/20 text-green-600'
                                : 'bg-blue-50 dark:bg-blue-900/20 text-blue-600'
                            }`}
                          >
                            {rule.ruleType === 'BLACK'
                              ? '黑名單'
                              : rule.ruleType === 'WHITE'
                              ? '白名單'
                              : '免問卷'}
                          </span>
                        </td>
                        <td className="py-3.5">
                          <span className="text-xs text-gray-400 dark:text-zinc-500 font-medium">
                            {rule.scopeType === 'GLOBAL' ? '全域' : '特定方案'}
                          </span>
                        </td>
                        <td className="py-3.5 text-xs text-gray-500">
                          {rule.scopeType === 'GLOBAL' ? '-' : matchedPlan ? matchedPlan.name : '未知方案'}
                        </td>
                        <td className="py-3.5 text-right">
                          <button
                            onClick={() => handleDeleteRule(rule.id)}
                            disabled={actionLoading}
                            className="p-1.5 hover:bg-red-50 dark:hover:bg-red-900/20 text-red-600 hover:text-red-700 rounded-lg active:scale-95 transition-all disabled:opacity-50"
                            title="刪除"
                          >
                            <Trash2 className="w-4 h-4" />
                          </button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
