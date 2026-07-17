import React, { useState } from 'react';
import {
  useSitterPlansQuery,
  useCreatePlanMutation,
  useUpdatePlanMutation,
  useSetPlanActiveMutation,
  useSortPlansMutation
} from '../../hooks/useServicePlans';
import type { ServicePlan } from '../../types/servicePlan';
import { ArrowUp, ArrowDown, Plus, Trash2, Upload, Edit2, X, AlertCircle } from 'lucide-react';
import axios from 'axios';

const PET_TYPE_LABELS: Record<string, string> = {
  CAT: '🐱 貓咪',
  DOG: '🐶 狗狗',
  BIRD: '🐦 鳥類',
  MOUSE: '🐭 鼠類',
  RABBIT: '🐰 兔子',
  REPTILE: '🦎 爬蟲',
  INSECT: '🐛 昆蟲',
  OTHER: '🐾 其他'
};
const PET_TYPE_OPTIONS = Object.keys(PET_TYPE_LABELS);

const SitterPlans: React.FC = () => {
  const { data: plans = [], isLoading, error } = useSitterPlansQuery();
  const createPlanMutation = useCreatePlanMutation();
  const updatePlanMutation = useUpdatePlanMutation();
  const setPlanActiveMutation = useSetPlanActiveMutation();
  const sortPlansMutation = useSortPlansMutation();

  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editingPlan, setEditingPlan] = useState<ServicePlan | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  // 表單獨立 State
  const [name, setName] = useState('');
  const [price, setPrice] = useState<number | ''>('');
  const [dailyCapacity, setDailyCapacity] = useState<number | ''>('');
  const [durationMinutes, setDurationMinutes] = useState<number | ''>(60);
  const [applicablePetTypes, setApplicablePetTypes] = useState<string[]>([]);
  const [defaultTasks, setDefaultTasks] = useState<string[]>([]);
  const [description, setDescription] = useState('');
  const [hasDateRange, setHasDateRange] = useState(false);
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [isRestricted, setIsRestricted] = useState(false);

  // 打開新增/編輯 Modal
  const openModal = (plan: ServicePlan | null = null) => {
    setEditingPlan(plan);
    setFormError(null);
    if (plan) {
      setName(plan.name);
      setPrice(plan.price);
      setDailyCapacity(plan.dailyCapacity);
      setDurationMinutes(plan.durationMinutes ?? 60);
      setApplicablePetTypes(plan.applicablePetTypes);
      setDefaultTasks(plan.defaultTasks);
      setDescription(plan.description || '');
      setHasDateRange(!!(plan.startDate || plan.endDate));
      setStartDate(plan.startDate || '');
      setEndDate(plan.endDate || '');
      setIsRestricted(plan.isRestricted);
    } else {
      setName('');
      setPrice('');
      setDailyCapacity('');
      setDurationMinutes(60);
      setApplicablePetTypes(['CAT']);
      setDefaultTasks(['基本餵食', '清理砂盆', '更換新鮮水']);
      setDescription('');
      setHasDateRange(false);
      setStartDate('');
      setEndDate('');
      setIsRestricted(false);
    }
    setIsModalOpen(true);
  };

  const closeModal = () => {
    setIsModalOpen(false);
    setEditingPlan(null);
    setFormError(null);
  };

  // 下架方案 (飼主端立即看不到，但保母端仍保留可隨時上架)
  const handleDeactivate = async (planId: string) => {
    if (
      window.confirm(
        '確定要下架此服務方案嗎？\n下架後飼主將無法再預約此方案，但不會影響已建立的歷史訂單，您隨時可以重新上架。'
      )
    ) {
      try {
        await setPlanActiveMutation.mutateAsync({ planId, isActive: false });
      } catch (err) {
        alert('下架失敗，請稍後再試。');
      }
    }
  };

  // 重新上架方案
  const handleActivate = async (planId: string) => {
    try {
      await setPlanActiveMutation.mutateAsync({ planId, isActive: true });
    } catch (err) {
      alert('上架失敗，請稍後再試。');
    }
  };

  // 排序：上移或下移
  const handleMove = async (index: number, direction: 'up' | 'down') => {
    const targetIndex = direction === 'up' ? index - 1 : index + 1;
    if (targetIndex < 0 || targetIndex >= plans.length) return;

    const newPlans = [...plans];
    const temp = newPlans[index];
    newPlans[index] = newPlans[targetIndex];
    newPlans[targetIndex] = temp;

    const planIds = newPlans.map((p) => p.id!).filter(Boolean);
    try {
      await sortPlansMutation.mutateAsync({ planIds });
    } catch (err) {
      alert('排序調整失敗，請重試。');
    }
  };

  // 處理表單送出
  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError(null);

    // 前端基本驗證
    if (!name.trim()) {
      setFormError('方案名稱不可為空');
      return;
    }
    if (price === '' || price <= 0) {
      setFormError('價格必須大於 0');
      return;
    }
    if (dailyCapacity === '' || dailyCapacity < 1) {
      setFormError('每日最大接單量不可小於 1');
      return;
    }
    if (durationMinutes === '' || durationMinutes < 1) {
      setFormError('服務時長必須大於 0 分鐘');
      return;
    }
    if (applicablePetTypes.length === 0) {
      setFormError('請至少選擇一種適用寵物類型');
      return;
    }

    const payload: ServicePlan = {
      name,
      price: Number(price),
      dailyCapacity: Number(dailyCapacity),
      durationMinutes: Number(durationMinutes),
      applicablePetTypes,
      defaultTasks: defaultTasks.filter((t) => t.trim() !== ''),
      description,
      startDate: hasDateRange && startDate ? startDate : null,
      endDate: hasDateRange && endDate ? endDate : null,
      isRestricted,
      version: editingPlan ? editingPlan.version : 0
    };

    try {
      if (editingPlan && editingPlan.id) {
        await updatePlanMutation.mutateAsync({ planId: editingPlan.id, plan: payload });
      } else {
        await createPlanMutation.mutateAsync(payload);
      }
      closeModal();
    } catch (err: any) {
      if (axios.isAxiosError(err)) {
        const responseData = err.response?.data;
        const status = err.response?.status;
        const errorCode = responseData?.error || responseData?.message;

        if (status === 403 || errorCode === 'AUTH_PLAN_LIMIT') {
          setFormError('僅限專業版以上方案可設定開放預約區間');
        } else if (status === 409 || errorCode === 'VERSION_CONFLICT') {
          setFormError('內容已被更新，請重新整理後再試');
        } else {
          setFormError(responseData?.message || '儲存失敗，請檢查欄位輸入。');
        }
      } else {
        setFormError('儲存失敗，發生未知錯誤。');
      }
    }
  };

  // 增刪預設任務
  const handleAddTask = () => {
    setDefaultTasks([...defaultTasks, '']);
  };

  const handleUpdateTask = (index: number, value: string) => {
    const updated = [...defaultTasks];
    updated[index] = value;
    setDefaultTasks(updated);
  };

  const handleRemoveTask = (index: number) => {
    setDefaultTasks(defaultTasks.filter((_, idx) => idx !== index));
  };

  // 適用寵物多選控制
  const handleTogglePetType = (type: string) => {
    if (applicablePetTypes.includes(type)) {
      setApplicablePetTypes(applicablePetTypes.filter((t) => t !== type));
    } else {
      setApplicablePetTypes([...applicablePetTypes, type]);
    }
  };

  return (
    <div
      style={{ padding: '2rem 1.5rem', maxWidth: '600px', margin: '0 auto' }}
      data-theme="sitter"
    >
      {/* Header section with asymmetry design */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'flex-start',
          marginBottom: '2.5rem'
        }}
      >
        <div>
          <h2
            style={{
              fontSize: '1.75rem',
              fontWeight: '800',
              margin: '0 0 0.5rem 0',
              color: 'var(--color-primary)'
            }}
          >
            服務方案設定
          </h2>
          <p style={{ color: 'var(--color-on-surface-variant)', fontSize: '0.9rem', margin: 0 }}>
            定義您提供的照護方案與任務清單
          </p>
        </div>
        <button
          onClick={() => openModal()}
          className="btn-primary"
          style={{
            padding: '10px 20px',
            fontSize: '0.9rem',
            borderRadius: '100px',
            boxShadow: 'var(--shadow-ambient)'
          }}
          data-testid="sitter-plan-btn-add"
        >
          <Plus size={16} /> 新增方案
        </button>
      </div>

      {/* Loading & Error handlers */}
      {isLoading && (
        <div style={{ padding: '4rem 0', textAlign: 'center', color: 'var(--color-primary)' }}>
          <div style={{ fontWeight: '700' }}>載入設定中...</div>
        </div>
      )}

      {error && (
        <div
          style={{
            padding: '2rem',
            backgroundColor: 'rgba(176, 37, 0, 0.05)',
            borderRadius: '12px',
            display: 'flex',
            gap: '8px',
            color: 'var(--color-error)'
          }}
        >
          <AlertCircle size={20} />
          <div>載入服務方案失敗，請確認後端服務已啟動並重新整理。</div>
        </div>
      )}

      {/* Plans List */}
      {!isLoading && !error && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
          {plans.length === 0 ? (
            <div
              style={{
                padding: '4rem 2rem',
                textAlign: 'center',
                backgroundColor: 'var(--color-surface-low)',
                borderRadius: 'var(--radius-lg)',
                border: '1px dashed var(--color-outline-variant)'
              }}
            >
              <p
                style={{ color: 'var(--color-on-surface-variant)', fontSize: '0.95rem', margin: 0 }}
              >
                目前尚無任何服務方案，請點擊右上角新增。
              </p>
            </div>
          ) : (
            plans.map((plan, index) => (
              <div
                key={plan.id}
                className="card-layered"
                style={{
                  position: 'relative',
                  transition: 'all 0.2s ease-in-out',
                  opacity: plan.isActive === false ? 0.6 : 1
                }}
                data-testid={`sitter-plan-card-${plan.id}`}
              >
                {/* Asymmetrical Top Layout */}
                <div
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'flex-start',
                    marginBottom: '1rem',
                    gap: '12px'
                  }}
                >
                  <div>
                    <h3
                      style={{
                        fontSize: '1.25rem',
                        fontWeight: '800',
                        margin: '0 0 4px 0',
                        color: 'var(--color-on-surface)',
                        display: 'flex',
                        alignItems: 'center',
                        gap: '8px'
                      }}
                    >
                      {plan.name}
                      {plan.isActive === false && (
                        <span
                          style={{
                            fontSize: '0.7rem',
                            fontWeight: '700',
                            backgroundColor: 'var(--color-surface-high)',
                            color: 'var(--color-on-surface-variant)',
                            padding: '2px 8px',
                            borderRadius: '9999px'
                          }}
                        >
                          已下架
                        </span>
                      )}
                    </h3>
                    <div
                      style={{ display: 'flex', gap: '6px', flexWrap: 'wrap', marginBottom: '8px' }}
                    >
                      {plan.applicablePetTypes.map((type) => (
                        <span
                          key={type}
                          style={{
                            fontSize: '0.75rem',
                            fontWeight: '700',
                            backgroundColor: 'var(--color-surface-low)',
                            color: 'var(--color-primary)',
                            padding: '2px 8px',
                            borderRadius: '4px'
                          }}
                        >
                          {PET_TYPE_LABELS[type] ?? type}
                        </span>
                      ))}
                      <span
                        style={{
                          fontSize: '0.75rem',
                          backgroundColor: 'rgba(0, 107, 27, 0.08)',
                          color: 'var(--color-tertiary)',
                          padding: '2px 8px',
                          borderRadius: '4px',
                          fontWeight: '700'
                        }}
                      >
                        限額 {plan.dailyCapacity} 次/日
                      </span>
                    </div>
                  </div>
                  <div style={{ textAlign: 'right', flexShrink: 0 }}>
                    <span
                      style={{
                        fontSize: '1.3rem',
                        fontWeight: '900',
                        color: 'var(--color-primary)'
                      }}
                    >
                      $ {plan.price}
                    </span>
                    <span style={{ fontSize: '0.8rem', color: 'var(--color-on-surface-variant)' }}>
                      {' '}
                      / {plan.durationMinutes ?? 60} 分鐘
                    </span>
                  </div>
                </div>

                {/* Description */}
                {plan.description && (
                  <p
                    style={{
                      fontSize: '0.875rem',
                      color: 'var(--color-on-surface-variant)',
                      margin: '0 0 1rem 0',
                      lineHeight: 1.5
                    }}
                  >
                    {plan.description}
                  </p>
                )}

                {/* SOP Tasks */}
                {plan.defaultTasks && plan.defaultTasks.length > 0 && (
                  <div
                    style={{
                      backgroundColor: 'var(--color-surface-low)',
                      padding: '12px 16px',
                      borderRadius: '12px',
                      marginBottom: '1rem'
                    }}
                  >
                    <div
                      style={{
                        fontSize: '0.75rem',
                        fontWeight: '800',
                        color: 'var(--color-on-surface-variant)',
                        marginBottom: '8px',
                        letterSpacing: '0.05em'
                      }}
                    >
                      預設照護 SOP
                    </div>
                    <ul
                      style={{
                        margin: 0,
                        paddingLeft: '1.2rem',
                        fontSize: '0.85rem',
                        color: 'var(--color-on-surface)',
                        lineHeight: 1.6
                      }}
                    >
                      {plan.defaultTasks.map((task, tIdx) => (
                        <li key={tIdx}>{task}</li>
                      ))}
                    </ul>
                  </div>
                )}

                {/* Restrictions and Date Ranges */}
                <div
                  style={{
                    display: 'flex',
                    flexWrap: 'wrap',
                    gap: '8px',
                    fontSize: '0.75rem',
                    color: 'var(--color-on-surface-variant)',
                    marginBottom: '1rem',
                    borderTop: '1px solid var(--color-outline-variant)',
                    paddingTop: '10px'
                  }}
                >
                  <div>
                    開放日期：
                    <strong style={{ color: 'var(--color-on-surface)' }}>
                      {plan.startDate || plan.endDate
                        ? `${plan.startDate || '無限制'} 至 ${plan.endDate || '無限制'}`
                        : '常態方案 (無限制)'}
                    </strong>
                  </div>
                  {plan.isRestricted && (
                    <div
                      style={{ marginLeft: 'auto', color: 'var(--color-error)', fontWeight: '700' }}
                    >
                      ⚠️ 啟用白名單限制
                    </div>
                  )}
                </div>

                {/* Card Actions (Edit, Delete, Move) */}
                <div
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    gap: '8px'
                  }}
                >
                  {/* Sorting Buttons */}
                  <div style={{ display: 'flex', gap: '4px' }}>
                    <button
                      onClick={() => handleMove(index, 'up')}
                      disabled={index === 0}
                      style={{
                        padding: '6px',
                        border: 'none',
                        background: 'var(--color-surface-low)',
                        color: 'var(--color-on-surface-variant)',
                        borderRadius: '6px',
                        cursor: index === 0 ? 'not-allowed' : 'pointer',
                        opacity: index === 0 ? 0.4 : 1
                      }}
                      title="上移"
                      data-testid={`sitter-plan-btn-move-up-${plan.id}`}
                    >
                      <ArrowUp size={16} />
                    </button>
                    <button
                      onClick={() => handleMove(index, 'down')}
                      disabled={index === plans.length - 1}
                      style={{
                        padding: '6px',
                        border: 'none',
                        background: 'var(--color-surface-low)',
                        color: 'var(--color-on-surface-variant)',
                        borderRadius: '6px',
                        cursor: index === plans.length - 1 ? 'not-allowed' : 'pointer',
                        opacity: index === plans.length - 1 ? 0.4 : 1
                      }}
                      title="下移"
                      data-testid={`sitter-plan-btn-move-down-${plan.id}`}
                    >
                      <ArrowDown size={16} />
                    </button>
                  </div>

                  {/* Operational Buttons */}
                  <div style={{ display: 'flex', gap: '8px' }}>
                    <button
                      onClick={() => openModal(plan)}
                      style={{
                        display: 'inline-flex',
                        alignItems: 'center',
                        gap: '4px',
                        padding: '6px 12px',
                        border: 'none',
                        background: 'var(--color-surface-low)',
                        color: 'var(--color-on-surface)',
                        borderRadius: '8px',
                        fontSize: '0.8rem',
                        cursor: 'pointer',
                        fontWeight: '600'
                      }}
                      data-testid={`sitter-plan-btn-edit-${plan.id}`}
                    >
                      <Edit2 size={12} /> 編輯
                    </button>
                    {plan.isActive === false ? (
                      <button
                        onClick={() => handleActivate(plan.id!)}
                        style={{
                          display: 'inline-flex',
                          alignItems: 'center',
                          gap: '4px',
                          padding: '6px 12px',
                          border: 'none',
                          background: 'rgba(0, 107, 27, 0.08)',
                          color: 'var(--color-tertiary)',
                          borderRadius: '8px',
                          fontSize: '0.8rem',
                          cursor: 'pointer',
                          fontWeight: '600'
                        }}
                        data-testid={`sitter-plan-btn-activate-${plan.id}`}
                      >
                        <Upload size={12} /> 上架
                      </button>
                    ) : (
                      <button
                        onClick={() => handleDeactivate(plan.id!)}
                        style={{
                          display: 'inline-flex',
                          alignItems: 'center',
                          gap: '4px',
                          padding: '6px 12px',
                          border: 'none',
                          background: 'rgba(176, 37, 0, 0.08)',
                          color: 'var(--color-error)',
                          borderRadius: '8px',
                          fontSize: '0.8rem',
                          cursor: 'pointer',
                          fontWeight: '600'
                        }}
                        data-testid={`sitter-plan-btn-delete-${plan.id}`}
                      >
                        <Trash2 size={12} /> 下架
                      </button>
                    )}
                  </div>
                </div>
              </div>
            ))
          )}
        </div>
      )}

      {/* Drawer-like BottomSheet Form Modal */}
      {isModalOpen && (
        <div
          style={{
            position: 'fixed',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            backgroundColor: 'rgba(45, 47, 46, 0.4)',
            zIndex: 100,
            display: 'flex',
            alignItems: 'flex-end',
            justifyContent: 'center',
            animation: 'fade-in 0.2s ease-out'
          }}
          onClick={closeModal}
        >
          <div
            style={{
              backgroundColor: 'var(--color-surface-lowest)',
              width: '100%',
              maxWidth: '500px',
              borderTopLeftRadius: 'var(--radius-xl)',
              borderTopRightRadius: 'var(--radius-xl)',
              maxHeight: '90vh',
              overflowY: 'auto',
              padding: '2rem 1.5rem',
              boxShadow: '0 -8px 32px rgba(45, 47, 46, 0.1)',
              animation: 'slide-up 0.3s cubic-bezier(0.16, 1, 0.3, 1)',
              boxSizing: 'border-box'
            }}
            onClick={(e) => e.stopPropagation()}
          >
            {/* Modal Title */}
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                marginBottom: '1.5rem'
              }}
            >
              <h3
                style={{
                  fontSize: '1.5rem',
                  fontWeight: '800',
                  margin: 0,
                  color: 'var(--color-on-surface)'
                }}
              >
                {editingPlan ? '編輯服務方案' : '新增服務方案'}
              </h3>
              <button
                onClick={closeModal}
                style={{
                  background: 'none',
                  border: 'none',
                  color: 'var(--color-on-surface-variant)',
                  cursor: 'pointer',
                  padding: '4px'
                }}
                data-testid="sitter-plan-btn-cancel"
              >
                <X size={24} />
              </button>
            </div>

            {/* Error Message Section */}
            {formError && (
              <div
                style={{
                  padding: '12px 16px',
                  backgroundColor: 'rgba(176, 37, 0, 0.06)',
                  color: 'var(--color-error)',
                  borderRadius: '8px',
                  fontSize: '0.85rem',
                  fontWeight: '600',
                  marginBottom: '1.5rem',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '8px'
                }}
                data-testid="sitter-plan-form-error"
              >
                <AlertCircle size={16} style={{ flexShrink: 0 }} />
                <span>{formError}</span>
              </div>
            )}

            {/* Form */}
            <form
              onSubmit={handleSubmit}
              style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem' }}
            >
              {/* Name */}
              <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
                <label
                  style={{
                    fontSize: '0.8rem',
                    fontWeight: '800',
                    color: 'var(--color-on-surface-variant)'
                  }}
                >
                  方案名稱 *
                </label>
                <input
                  type="text"
                  placeholder="例如: 30分鐘到府餵食"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  style={{
                    padding: '12px',
                    borderRadius: 'var(--radius-sm)',
                    border: '1px solid var(--color-surface-high)',
                    backgroundColor: 'var(--color-surface-low)',
                    fontFamily: 'var(--font-body)',
                    fontSize: '0.95rem'
                  }}
                  data-testid="sitter-plan-input-name"
                  required
                />
              </div>

              {/* Price & Capacity Horizontal Layout */}
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
                <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
                  <label
                    style={{
                      fontSize: '0.8rem',
                      fontWeight: '800',
                      color: 'var(--color-on-surface-variant)'
                    }}
                  >
                    單次價格 ($) *
                  </label>
                  <input
                    type="number"
                    placeholder="500"
                    value={price}
                    onChange={(e) => setPrice(e.target.value === '' ? '' : Number(e.target.value))}
                    style={{
                      padding: '12px',
                      borderRadius: 'var(--radius-sm)',
                      border: '1px solid var(--color-surface-high)',
                      backgroundColor: 'var(--color-surface-low)',
                      fontFamily: 'var(--font-body)',
                      fontSize: '0.95rem'
                    }}
                    data-testid="sitter-plan-input-price"
                    min="1"
                    required
                  />
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
                  <label
                    style={{
                      fontSize: '0.8rem',
                      fontWeight: '800',
                      color: 'var(--color-on-surface-variant)'
                    }}
                  >
                    每日接單容量限制 *
                  </label>
                  <input
                    type="number"
                    placeholder="3"
                    value={dailyCapacity}
                    onChange={(e) =>
                      setDailyCapacity(e.target.value === '' ? '' : Number(e.target.value))
                    }
                    style={{
                      padding: '12px',
                      borderRadius: 'var(--radius-sm)',
                      border: '1px solid var(--color-surface-high)',
                      backgroundColor: 'var(--color-surface-low)',
                      fontFamily: 'var(--font-body)',
                      fontSize: '0.95rem'
                    }}
                    data-testid="sitter-plan-input-capacity"
                    min="1"
                    required
                  />
                </div>
              </div>

              {/* Duration */}
              <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
                <label
                  style={{
                    fontSize: '0.8rem',
                    fontWeight: '800',
                    color: 'var(--color-on-surface-variant)'
                  }}
                >
                  服務時長 (分鐘) *
                </label>
                <input
                  type="number"
                  placeholder="60"
                  value={durationMinutes}
                  onChange={(e) =>
                    setDurationMinutes(e.target.value === '' ? '' : Number(e.target.value))
                  }
                  style={{
                    padding: '12px',
                    borderRadius: 'var(--radius-sm)',
                    border: '1px solid var(--color-surface-high)',
                    backgroundColor: 'var(--color-surface-low)',
                    fontFamily: 'var(--font-body)',
                    fontSize: '0.95rem'
                  }}
                  data-testid="sitter-plan-input-duration"
                  min="1"
                  required
                />
              </div>

              {/* Applicable Pet Types */}
              <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
                <label
                  style={{
                    fontSize: '0.8rem',
                    fontWeight: '800',
                    color: 'var(--color-on-surface-variant)'
                  }}
                >
                  適用寵物類型 *
                </label>
                <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
                  {PET_TYPE_OPTIONS.map((type) => (
                    <button
                      key={type}
                      type="button"
                      onClick={() => handleTogglePetType(type)}
                      style={{
                        flex: '1 0 22%',
                        padding: '10px',
                        border: '1px solid var(--color-outline-variant)',
                        backgroundColor: applicablePetTypes.includes(type)
                          ? 'var(--color-primary)'
                          : 'var(--color-surface-low)',
                        color: applicablePetTypes.includes(type)
                          ? 'white'
                          : 'var(--color-on-surface)',
                        borderRadius: '8px',
                        cursor: 'pointer',
                        fontWeight: '700',
                        transition: 'all 0.2s'
                      }}
                      data-testid={`sitter-plan-checkbox-pet-${type}`}
                    >
                      {PET_TYPE_LABELS[type]}
                    </button>
                  ))}
                </div>
              </div>

              {/* Description */}
              <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
                <label
                  style={{
                    fontSize: '0.8rem',
                    fontWeight: '800',
                    color: 'var(--color-on-surface-variant)'
                  }}
                >
                  方案說明
                </label>
                <textarea
                  placeholder="請簡單描述此方案的服務細節"
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  style={{
                    padding: '12px',
                    borderRadius: 'var(--radius-sm)',
                    border: '1px solid var(--color-surface-high)',
                    backgroundColor: 'var(--color-surface-low)',
                    fontFamily: 'var(--font-body)',
                    fontSize: '0.95rem',
                    minHeight: '80px',
                    resize: 'none'
                  }}
                  data-testid="sitter-plan-input-description"
                />
              </div>

              {/* SOP Default Tasks Dynamic Editor */}
              <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
                <div
                  style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}
                >
                  <label
                    style={{
                      fontSize: '0.8rem',
                      fontWeight: '800',
                      color: 'var(--color-on-surface-variant)'
                    }}
                  >
                    預設照護 SOP 工作項目
                  </label>
                  <button
                    type="button"
                    onClick={handleAddTask}
                    style={{
                      background: 'none',
                      border: 'none',
                      color: 'var(--color-primary)',
                      fontSize: '0.8rem',
                      fontWeight: '800',
                      cursor: 'pointer',
                      display: 'flex',
                      alignItems: 'center',
                      gap: '4px'
                    }}
                    data-testid="sitter-plan-btn-add-task"
                  >
                    <Plus size={14} /> 新增項目
                  </button>
                </div>
                <div
                  style={{ display: 'flex', flexDirection: 'column', gap: '8px', marginTop: '4px' }}
                >
                  {defaultTasks.map((task, idx) => (
                    <div key={idx} style={{ display: 'flex', gap: '6px', alignItems: 'center' }}>
                      <input
                        type="text"
                        placeholder="例如: 拍照回報"
                        value={task}
                        onChange={(e) => handleUpdateTask(idx, e.target.value)}
                        style={{
                          flex: 1,
                          padding: '10px',
                          borderRadius: 'var(--radius-sm)',
                          border: '1px solid var(--color-surface-high)',
                          backgroundColor: 'var(--color-surface-low)',
                          fontFamily: 'var(--font-body)',
                          fontSize: '0.875rem'
                        }}
                        data-testid={`sitter-plan-input-task-${idx}`}
                        required
                      />
                      <button
                        type="button"
                        onClick={() => handleRemoveTask(idx)}
                        style={{
                          background: 'none',
                          border: 'none',
                          color: 'var(--color-error)',
                          cursor: 'pointer',
                          padding: '4px'
                        }}
                        data-testid={`sitter-plan-btn-delete-task-${idx}`}
                      >
                        <Trash2 size={16} />
                      </button>
                    </div>
                  ))}
                  {defaultTasks.length === 0 && (
                    <div
                      style={{
                        fontSize: '0.8rem',
                        color: 'var(--color-on-surface-variant)',
                        fontStyle: 'italic',
                        padding: '6px 0'
                      }}
                    >
                      無預設照護工作項目
                    </div>
                  )}
                </div>
              </div>

              {/* Date Limits with Switch */}
              <div
                style={{
                  borderTop: '1px solid var(--color-outline-variant)',
                  paddingTop: '1rem',
                  display: 'flex',
                  flexDirection: 'column',
                  gap: '10px'
                }}
              >
                <div
                  style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}
                >
                  <div>
                    <div style={{ fontSize: '0.9rem', fontWeight: '800' }}>
                      限制開放預約日期區間
                    </div>
                    <div
                      style={{
                        fontSize: '0.75rem',
                        color: 'var(--color-on-surface-variant)',
                        marginTop: '2px'
                      }}
                    >
                      僅在選定區間內開放飼主預約
                    </div>
                  </div>
                  <input
                    type="checkbox"
                    checked={hasDateRange}
                    onChange={(e) => setHasDateRange(e.target.checked)}
                    style={{
                      width: '20px',
                      height: '20px',
                      cursor: 'pointer',
                      accentColor: 'var(--color-primary)'
                    }}
                    data-testid="sitter-plan-switch-date-range"
                  />
                </div>

                {hasDateRange && (
                  <div
                    style={{
                      display: 'grid',
                      gridTemplateColumns: '1fr 1fr',
                      gap: '12px',
                      marginTop: '4px',
                      animation: 'fade-in 0.2s'
                    }}
                  >
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
                      <label
                        style={{
                          fontSize: '0.75rem',
                          fontWeight: '800',
                          color: 'var(--color-on-surface-variant)'
                        }}
                      >
                        開始日期
                      </label>
                      <input
                        type="date"
                        value={startDate}
                        onChange={(e) => setStartDate(e.target.value)}
                        style={{
                          padding: '10px',
                          borderRadius: 'var(--radius-sm)',
                          border: '1px solid var(--color-surface-high)',
                          backgroundColor: 'var(--color-surface-low)',
                          fontSize: '0.85rem'
                        }}
                        data-testid="sitter-plan-input-start-date"
                        required={hasDateRange}
                      />
                    </div>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
                      <label
                        style={{
                          fontSize: '0.75rem',
                          fontWeight: '800',
                          color: 'var(--color-on-surface-variant)'
                        }}
                      >
                        結束日期
                      </label>
                      <input
                        type="date"
                        value={endDate}
                        onChange={(e) => setEndDate(e.target.value)}
                        style={{
                          padding: '10px',
                          borderRadius: 'var(--radius-sm)',
                          border: '1px solid var(--color-surface-high)',
                          backgroundColor: 'var(--color-surface-low)',
                          fontSize: '0.85rem'
                        }}
                        data-testid="sitter-plan-input-end-date"
                        required={hasDateRange}
                      />
                    </div>
                  </div>
                )}
                {hasDateRange && (
                  <p
                    style={{
                      fontSize: '0.75rem',
                      color: 'var(--color-on-surface-variant)',
                      margin: '4px 0 0 0',
                      lineHeight: 1.4
                    }}
                  >
                    <strong style={{ color: 'var(--color-primary)' }}>* 限制提示：</strong>
                    設定開放預約區間需要您訂閱{' '}
                    <strong style={{ color: 'var(--color-primary)' }}>專業版 (PRO) 或以上</strong>
                    。普通版保母送出將會被限制。
                  </p>
                )}
              </div>

              {/* Restrict Whitelist with Switch */}
              <div
                style={{
                  borderTop: '1px solid var(--color-outline-variant)',
                  paddingTop: '1rem',
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center'
                }}
              >
                <div>
                  <div style={{ fontSize: '0.9rem', fontWeight: '800' }}>啟用白名單預約限制</div>
                  <div
                    style={{
                      fontSize: '0.75rem',
                      color: 'var(--color-on-surface-variant)',
                      marginTop: '2px'
                    }}
                  >
                    啟用後，僅有加入白名單的飼主可檢視並預約此方案
                  </div>
                </div>
                <input
                  type="checkbox"
                  checked={isRestricted}
                  onChange={(e) => setIsRestricted(e.target.checked)}
                  style={{
                    width: '20px',
                    height: '20px',
                    cursor: 'pointer',
                    accentColor: 'var(--color-primary)'
                  }}
                  data-testid="sitter-plan-switch-restricted"
                />
              </div>

              {/* Form Buttons */}
              <div
                style={{
                  display: 'flex',
                  gap: '12px',
                  marginTop: '1.5rem',
                  borderTop: '1px solid var(--color-outline-variant)',
                  paddingTop: '1.5rem'
                }}
              >
                <button
                  type="button"
                  onClick={closeModal}
                  style={{
                    flex: 1,
                    padding: '12px',
                    borderRadius: '100px',
                    border: '1px solid var(--color-surface-high)',
                    backgroundColor: 'var(--color-surface-low)',
                    color: 'var(--color-on-surface)',
                    fontWeight: '700',
                    cursor: 'pointer'
                  }}
                >
                  取消
                </button>
                <button
                  type="submit"
                  className="btn-primary"
                  style={{ flex: 2, padding: '12px' }}
                  data-testid="sitter-plan-btn-save"
                >
                  儲存並發布
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};

export default SitterPlans;
