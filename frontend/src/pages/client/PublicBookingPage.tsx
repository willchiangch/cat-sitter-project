import React, { useState, useEffect, useRef } from 'react';
import Card from '../../components/ui/Card';
import Button from '../../components/ui/Button';
import {
  CheckCircle2,
  ArrowRight,
  Plus,
  Trash2,
  CalendarDays,
  Minus,
  CalendarX,
  ChevronLeft,
  ChevronRight
} from 'lucide-react';
import type { BookingState } from '../../types/booking';
import { useSitterActivePlansQuery } from '../../hooks/useServicePlans';
import { usePetsQuery } from '../../hooks/usePets';
import { createBooking } from '../../api/orderApi';
import { usePublicProfileQuery } from '../../hooks/usePublicProfile';
import { useActiveQuestionsQuery } from '../../hooks/useQuestions';

const formatDatesGroupedByYear = (dates: string[]) => {
  if (!dates || dates.length === 0) return null;
  const grouped: Record<string, string[]> = {};
  dates.forEach((d) => {
    const [year, month, day] = d.split('-');
    if (!grouped[year]) grouped[year] = [];
    grouped[year].push(`${parseInt(month)}/${parseInt(day)}`);
  });
  return grouped;
};

interface PublicBookingPageProps {
  sitterId?: string;
}

const PublicBookingPage: React.FC<PublicBookingPageProps> = ({
  sitterId = '3d498178-14c0-4376-b81e-7fb02e615dda'
}) => {
  const { data: plans = [], isLoading, error } = useSitterActivePlansQuery(sitterId);
  const { data: profile, isLoading: isProfileLoading, error: profileError } = usePublicProfileQuery(sitterId);
  const { data: questions = [] } = useActiveQuestionsQuery(sitterId);
  const [questionAnswers, setQuestionAnswers] = useState<Record<string, string[]>>({});

  const setSingleAnswer = (questionId: string, value: string) => {
    setQuestionAnswers((prev) => ({ ...prev, [questionId]: [value] }));
  };

  const toggleCheckboxAnswer = (questionId: string, option: string) => {
    setQuestionAnswers((prev) => {
      const current = prev[questionId] || [];
      const next = current.includes(option)
        ? current.filter((v) => v !== option)
        : [...current, option];
      return { ...prev, [questionId]: next };
    });
  };

  // 偵測自我介紹文字是否超過 5 行 (被 line-clamp 裁切)，只有真的被裁切時才顯示「顯示更多」按鈕
  useEffect(() => {
    if (bioRef.current) {
      setIsBioOverflowing(bioRef.current.scrollHeight > bioRef.current.clientHeight + 1);
    }
  }, [profile?.bio]);
  const { data: pets = [] } = usePetsQuery();
  const [step, setStep] = useState(1);
  const [isBioExpanded, setIsBioExpanded] = useState(false);
  const [isBioOverflowing, setIsBioOverflowing] = useState(false);
  const bioRef = useRef<HTMLParagraphElement>(null);
  const [booking, setBooking] = useState<BookingState>({
    sitterId: sitterId,
    planConfigs: [],
    notes: '',
    totalAmount: 0
  });

  useEffect(() => {
    setBooking((prev) => ({ ...prev, sitterId }));
  }, [sitterId]);

  const [isAddingPlan, setIsAddingPlan] = useState(true);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [activeCalendar, _setActiveCalendar] = useState<{
    planIndex: number;
    scheduleIndex: number;
  } | null>(null);

  const [currentYear, setCurrentYear] = useState(new Date().getFullYear());
  const [currentMonth, setCurrentMonth] = useState(new Date().getMonth());

  if (isLoading || isProfileLoading) {
    return (
      <div style={{ padding: '4rem 2rem', textAlign: 'center', color: 'var(--color-primary)' }}>
        <div style={{ fontSize: '1.25rem', fontWeight: '800' }}>讀取保母公開檔案與方案中...</div>
      </div>
    );
  }

  if (error || profileError) {
    return (
      <div style={{ padding: '4rem 2rem', textAlign: 'center', color: 'var(--color-error)' }}>
        <div style={{ fontSize: '1.25rem', fontWeight: '800' }}>找不到該保母資料或帳號已刪除</div>
      </div>
    );
  }

  if (plans.length === 0) {
    return (
      <div style={{ padding: '4rem 2rem', textAlign: 'center' }}>
        <h2
          style={{
            fontSize: '1.5rem',
            fontWeight: '800',
            marginBottom: '1rem',
            color: 'var(--color-on-surface)'
          }}
        >
          尚無服務方案
        </h2>
        <p style={{ color: 'var(--color-on-surface-variant)' }}>
          此保母目前尚未設定任何服務方案，請稍後再試或與保母聯繫。
        </p>
      </div>
    );
  }

  const setActiveCalendar = (val: { planIndex: number; scheduleIndex: number } | null) => {
    _setActiveCalendar(val);
    if (val) {
      const today = new Date();
      setCurrentYear(today.getFullYear());
      setCurrentMonth(today.getMonth());
    }
  };

  const handlePrevMonth = () => {
    if (currentMonth === 0) {
      setCurrentMonth(11);
      setCurrentYear((prev) => prev - 1);
    } else {
      setCurrentMonth((prev) => prev - 1);
    }
  };

  const handleNextMonth = () => {
    if (currentMonth === 11) {
      setCurrentMonth(0);
      setCurrentYear((prev) => prev + 1);
    } else {
      setCurrentMonth((prev) => prev + 1);
    }
  };

  const allSelectedDates = booking.planConfigs.flatMap((p) => p.schedules.flatMap((s) => s.dates));

  const togglePetInSchedule = (planIndex: number, scheduleIndex: number, petId: string) => {
    setBooking((prev) => {
      const newConfigs = [...prev.planConfigs];
      const plan = { ...newConfigs[planIndex] };
      const schedules = [...plan.schedules];
      const schedule = { ...schedules[scheduleIndex] };
      const currentPetIds = schedule.petIds || [];
      if (currentPetIds.includes(petId)) {
        schedule.petIds = currentPetIds.filter((id) => id !== petId);
      } else {
        schedule.petIds = [...currentPetIds, petId];
      }
      schedules[scheduleIndex] = schedule;
      plan.schedules = schedules;
      newConfigs[planIndex] = plan;
      return { ...prev, planConfigs: newConfigs };
    });
  };

  const handleSubmitBooking = async () => {
    if (isSubmitting) return;
    if (booking.planConfigs.length === 0) return;
    const ownerId = pets && pets.length > 0 ? pets[0].ownerId : null;
    if (!ownerId) {
      alert('請先去新增毛孩資料以建立預約！');
      return;
    }

    // 檢查是否每個排程都有選寵物
    const hasMissingPets = booking.planConfigs.some((pc) =>
      pc.schedules.some((s) => !s.petIds || s.petIds.length === 0)
    );
    if (hasMissingPets) {
      alert('請為每個排程選擇至少一隻毛孩！');
      return;
    }

    // PRD-004 AC-4：必填問卷題目需在送出前強制驗證
    const missingRequiredQuestion = questions.find((q) => {
      if (!q.required) return false;
      const values = questionAnswers[q.id!] || [];
      return !values.some((v) => v.trim().length > 0);
    });
    if (missingRequiredQuestion) {
      alert(`請填寫必填問題：「${missingRequiredQuestion.questionText}」`);
      return;
    }

    const expectedTotalAmount = booking.planConfigs.reduce((acc, planConfig) => {
      const plan = plans.find((p) => p.id === planConfig.planId);
      const planTotal = planConfig.schedules.reduce((sAcc, schedule) => {
        return sAcc + (plan?.price || 0) * schedule.dates.length * schedule.timesPerDay;
      }, 0);
      return acc + planTotal;
    }, 0);

    const requestBody = {
      ownerId: ownerId,
      sitterId: booking.sitterId,
      items: booking.planConfigs.flatMap((planConfig) =>
        planConfig.schedules.map((schedule) => ({
          planId: planConfig.planId,
          dates: schedule.dates,
          timesPerDay: schedule.timesPerDay,
          petIds: schedule.petIds || []
        }))
      ),
      answers: questions.map((q) => ({
        questionId: q.id,
        answerValues: questionAnswers[q.id!] || []
      })),
      expectedTotalAmount
    };

    try {
      setIsSubmitting(true);
      const idempotencyKey = crypto.randomUUID();
      const res = await createBooking(requestBody, idempotencyKey);
      alert(`預約已送出！訂單 ID: ${res.orderId || res}`);
    } catch (err: any) {
      console.error('送出預約失敗：', err);
      alert('送出預約失敗：' + (err.response?.data?.message || err.message));
    } finally {
      setIsSubmitting(false);
    }
  };

  // 預設全選該方案適用的寵物，避免飼主每次都要手動一隻一隻點選
  const getDefaultPetIds = (planId: string) => {
    const plan = plans.find((p) => p.id === planId);
    const allowedTypes = plan?.applicablePetTypes || [];
    return pets
      .filter((p) => allowedTypes.length === 0 || allowedTypes.includes(p.species))
      .map((p) => p.id!);
  };

  const addPlanConfig = (planId: string) => {
    setBooking((prev) => {
      const newConfigs = [
        ...prev.planConfigs,
        { planId, schedules: [{ dates: [], timesPerDay: 1, petIds: getDefaultPetIds(planId) }] }
      ];
      setActiveCalendar({ planIndex: prev.planConfigs.length, scheduleIndex: 0 });
      return {
        ...prev,
        planConfigs: newConfigs
      };
    });
    setIsAddingPlan(false);
  };

  const addScheduleToPlan = (planIndex: number) => {
    setBooking((prev) => {
      const newConfigs = [...prev.planConfigs];
      const plan = { ...newConfigs[planIndex] };
      plan.schedules = [
        ...plan.schedules,
        { dates: [], timesPerDay: 1, petIds: getDefaultPetIds(plan.planId) }
      ];
      newConfigs[planIndex] = plan;
      setActiveCalendar({ planIndex, scheduleIndex: plan.schedules.length - 1 });
      return { ...prev, planConfigs: newConfigs };
    });
  };

  const removeScheduleFromPlan = (planIndex: number, scheduleIndex: number) => {
    setBooking((prev) => {
      const newConfigs = [...prev.planConfigs];
      const plan = { ...newConfigs[planIndex] };
      const schedules = [...plan.schedules];
      schedules.splice(scheduleIndex, 1);

      if (schedules.length === 0) {
        newConfigs.splice(planIndex, 1);
        if (newConfigs.length === 0) setIsAddingPlan(true);
      } else {
        plan.schedules = schedules;
        newConfigs[planIndex] = plan;
      }
      return { ...prev, planConfigs: newConfigs };
    });
  };

  const toggleDateInSchedule = (planIndex: number, scheduleIndex: number, date: string) => {
    setBooking((prev) => {
      const newConfigs = [...prev.planConfigs];
      const plan = { ...newConfigs[planIndex] };
      const schedules = [...plan.schedules];
      const schedule = { ...schedules[scheduleIndex] };

      if (schedule.dates.includes(date)) {
        schedule.dates = schedule.dates.filter((d) => d !== date);
      } else {
        schedule.dates = [...schedule.dates, date].sort();
      }

      schedules[scheduleIndex] = schedule;
      plan.schedules = schedules;
      newConfigs[planIndex] = plan;

      return { ...prev, planConfigs: newConfigs };
    });
  };

  const updateTimesPerDay = (planIndex: number, scheduleIndex: number, times: number) => {
    setBooking((prev) => {
      const newConfigs = [...prev.planConfigs];
      const plan = { ...newConfigs[planIndex] };
      const schedules = [...plan.schedules];
      schedules[scheduleIndex] = { ...schedules[scheduleIndex], timesPerDay: times };
      plan.schedules = schedules;
      newConfigs[planIndex] = plan;
      return { ...prev, planConfigs: newConfigs };
    });
  };

  const handleNextToStep2 = () => {
    setStep(2);
  };

  const renderStep1 = () => {
    const isNextDisabled =
      profile?.gated ||
      !profile?.isOpen ||
      booking.planConfigs.length === 0 ||
      booking.planConfigs.some((pc) =>
        pc.schedules.some((s) => s.dates.length === 0 || !s.petIds || s.petIds.length === 0)
      );

    if (isAddingPlan) {
      return (
        <div className="fade-in" style={{ padding: '0 0 2rem 0' }}>
          <div style={{ marginBottom: '2rem' }}>
            <h2
              style={{
                fontSize: '1.75rem',
                fontWeight: '800',
                marginBottom: '0.5rem',
                fontFamily: 'var(--font-display)',
                letterSpacing: '-0.03em'
              }}
            >
              選擇服務方案
            </h2>
            <p style={{ color: 'var(--color-on-surface-variant)', fontSize: '0.95rem' }}>
              為您的愛貓挑選最貼心的陪伴方式
            </p>
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            {plans
              .map((plan, globalIdx) => ({ plan, globalIdx }))
              .filter(({ plan }) => !booking.planConfigs.some((pc) => pc.planId === plan.id))
              .map(({ plan, globalIdx }) => (
                <div
                  key={plan.id}
                  onClick={() => addPlanConfig(plan.id!)}
                  style={{
                    cursor: 'pointer',
                    padding: '24px',
                    backgroundColor: 'var(--color-surface)',
                    borderRadius: '16px',
                    border: '1px solid var(--color-outline-variant)',
                    transition: 'all 0.3s cubic-bezier(0.4, 0, 0.2, 1)',
                    boxShadow: 'var(--shadow-ambient)'
                  }}
                  onMouseEnter={(e) => {
                    e.currentTarget.style.borderColor = 'var(--color-primary)';
                    e.currentTarget.style.transform = 'translateY(-4px)';
                    e.currentTarget.style.boxShadow = 'var(--shadow-float)';
                  }}
                  onMouseLeave={(e) => {
                    e.currentTarget.style.borderColor = 'var(--color-outline-variant)';
                    e.currentTarget.style.transform = 'translateY(0)';
                    e.currentTarget.style.boxShadow = 'var(--shadow-ambient)';
                  }}
                  data-testid={`client-booking-plan-card-${globalIdx}`}
                >
                  <div
                    style={{
                      display: 'flex',
                      justifyContent: 'space-between',
                      alignItems: 'center',
                      marginBottom: '8px'
                    }}
                  >
                    <h3
                      style={{
                        fontWeight: '800',
                        margin: 0,
                        fontSize: '1.25rem',
                        color: 'var(--color-on-surface)'
                      }}
                    >
                      {plan.name}
                    </h3>
                    <span
                      style={{
                        color: 'var(--color-primary)',
                        fontWeight: '900',
                        fontSize: '1.1rem'
                      }}
                    >
                      $ {plan.price} / {plan.durationMinutes ?? 60} 分鐘
                    </span>
                  </div>
                  <p
                    style={{
                      fontSize: '0.9rem',
                      color: 'var(--color-on-surface-variant)',
                      margin: 0,
                      lineHeight: 1.5
                    }}
                  >
                    {plan.description}
                  </p>
                </div>
              ))}

            {/* 檔期不足模擬 */}
            <div
              style={{
                padding: '24px',
                backgroundColor: 'transparent',
                borderRadius: '16px',
                border: '1px dashed var(--color-outline-variant)',
                opacity: 0.6,
                cursor: 'not-allowed'
              }}
            >
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  marginBottom: '8px'
                }}
              >
                <h3
                  style={{
                    fontWeight: '800',
                    margin: 0,
                    fontSize: '1.25rem',
                    color: 'var(--color-on-surface-variant)'
                  }}
                >
                  尊榮包月
                </h3>
                <span style={{ color: 'var(--color-on-surface-variant)', fontWeight: '700' }}>
                  $ 15,000 / 月
                </span>
              </div>
              <p
                style={{
                  fontSize: '0.85rem',
                  color: 'var(--color-error)',
                  margin: '8px 0 0 0',
                  fontWeight: '700',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '6px'
                }}
              >
                <CalendarX size={14} /> 此方案目前檔期已滿
              </p>
            </div>
          </div>

          {booking.planConfigs.length > 0 && (
            <div style={{ marginTop: '3rem', textAlign: 'center' }}>
              <Button
                variant="secondary"
                onClick={() => setIsAddingPlan(false)}
                fullWidth
                style={{
                  border: 'none',
                  backgroundColor: 'rgba(0,0,0,0.04)',
                  borderRadius: '100px'
                }}
              >
                <ArrowRight size={18} style={{ transform: 'rotate(180deg)', marginRight: '8px' }} />{' '}
                返回上頁
              </Button>
            </div>
          )}
        </div>
      );
    }

    return (
      <div className="fade-in">
        <div style={{ marginBottom: '2rem' }}>
          <h2
            style={{
              fontSize: '1.75rem',
              fontWeight: '800',
              marginBottom: '0.5rem',
              fontFamily: 'var(--font-display)',
              letterSpacing: '-0.03em'
            }}
          >
            排程配置
          </h2>
          <p style={{ color: 'var(--color-on-surface-variant)', fontSize: '0.95rem' }}>
            請設定您的預約日期與次數
          </p>
        </div>

        {booking.planConfigs.map((planConfig, pIdx) => {
          const plan = plans.find((p) => p.id === planConfig.planId);
          return (
            <div
              key={planConfig.planId}
              style={{
                marginBottom: '2rem',
                padding: '24px',
                backgroundColor: 'var(--color-surface)',
                borderRadius: '24px',
                boxShadow: 'var(--shadow-ambient)'
              }}
            >
              <h3
                style={{
                  fontSize: '1.25rem',
                  fontWeight: '800',
                  marginBottom: '24px',
                  color: 'var(--color-primary)',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '12px'
                }}
              >
                <span
                  style={{
                    width: '4px',
                    height: '24px',
                    backgroundColor: 'var(--color-primary)',
                    borderRadius: '2px'
                  }}
                ></span>
                {plan?.name}
              </h3>

              {planConfig.schedules.map((schedule, sIdx) => {
                const isCalendarOpen =
                  activeCalendar?.planIndex === pIdx && activeCalendar?.scheduleIndex === sIdx;
                const groupedDates = formatDatesGroupedByYear(schedule.dates);

                return (
                  <div
                    key={sIdx}
                    style={{
                      backgroundColor: 'var(--color-surface-low)',
                      borderRadius: '16px',
                      padding: '20px',
                      marginBottom: '20px'
                    }}
                  >
                    {!isCalendarOpen && (
                      <div
                        style={{
                          display: 'flex',
                          justifyContent: 'space-between',
                          alignItems: 'center',
                          gap: '12px',
                          marginBottom: '20px'
                        }}
                      >
                        <button
                          onClick={() =>
                            setActiveCalendar({ planIndex: pIdx, scheduleIndex: sIdx })
                          }
                          style={{
                            display: 'inline-flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            color: 'var(--color-on-primary)',
                            background: 'var(--color-primary)',
                            border: 'none',
                            width: '44px',
                            height: '44px',
                            borderRadius: '50%',
                            cursor: 'pointer',
                            transition: 'all 0.3s cubic-bezier(0.4, 0, 0.2, 1)',
                            boxShadow: '0 4px 12px rgba(118, 86, 0, 0.2)',
                            flexShrink: 0
                          }}
                          onMouseEnter={(e) => {
                            e.currentTarget.style.transform = 'translateY(-2px) scale(1.05)';
                            e.currentTarget.style.boxShadow = '0 6px 16px rgba(118, 86, 0, 0.3)';
                          }}
                          onMouseLeave={(e) => {
                            e.currentTarget.style.transform = 'translateY(0) scale(1)';
                            e.currentTarget.style.boxShadow = '0 4px 12px rgba(118, 86, 0, 0.2)';
                          }}
                          data-testid={`client-booking-open-calendar-${pIdx}-${sIdx}`}
                          title="選擇日期"
                        >
                          <CalendarDays size={20} />
                        </button>

                        <div
                          style={{
                            display: 'flex',
                            flexDirection: 'column',
                            alignItems: 'center',
                            justifyContent: 'center',
                            backgroundColor: 'var(--color-surface)',
                            padding: '6px 12px',
                            borderRadius: '16px',
                            border: '1px solid var(--color-outline-variant)',
                            boxShadow: 'inset 0 2px 4px rgba(0,0,0,0.02)',
                            flexShrink: 0,
                            gap: '4px'
                          }}
                        >
                          <div
                            style={{
                              fontSize: '0.75rem',
                              fontWeight: '800',
                              color: 'var(--color-on-surface-variant)',
                              whiteSpace: 'nowrap',
                              opacity: 0.8
                            }}
                          >
                            每日次數
                          </div>
                          <div
                            style={{
                              display: 'flex',
                              alignItems: 'center',
                              gap: '8px',
                              flexShrink: 0
                            }}
                          >
                            <button
                              onClick={() =>
                                updateTimesPerDay(pIdx, sIdx, Math.max(1, schedule.timesPerDay - 1))
                              }
                              data-testid={`client-booking-times-minus-${pIdx}-${sIdx}`}
                              style={{
                                background: 'var(--color-surface-low)',
                                border: 'none',
                                color: 'var(--color-on-surface)',
                                width: '24px',
                                height: '24px',
                                borderRadius: '50%',
                                display: 'flex',
                                alignItems: 'center',
                                justifyContent: 'center',
                                cursor: 'pointer',
                                boxShadow: '0 1px 4px rgba(0,0,0,0.05)',
                                transition: 'all 0.2s'
                              }}
                              onMouseEnter={(e) => {
                                e.currentTarget.style.backgroundColor = 'var(--color-primary)';
                                e.currentTarget.style.color = 'white';
                              }}
                              onMouseLeave={(e) => {
                                e.currentTarget.style.backgroundColor = 'var(--color-surface-low)';
                                e.currentTarget.style.color = 'var(--color-on-surface)';
                              }}
                            >
                              <Minus size={10} />
                            </button>
                            <div
                              data-testid={`client-booking-times-val-${pIdx}-${sIdx}`}
                              style={{
                                fontWeight: '800',
                                fontSize: '1rem',
                                width: '20px',
                                textAlign: 'center',
                                color: 'var(--color-primary)'
                              }}
                            >
                              {schedule.timesPerDay}
                            </div>
                            <button
                              onClick={() =>
                                updateTimesPerDay(pIdx, sIdx, Math.min(9, schedule.timesPerDay + 1))
                              }
                              data-testid={`client-booking-times-plus-${pIdx}-${sIdx}`}
                              style={{
                                background: 'var(--color-surface-low)',
                                border: 'none',
                                color: 'var(--color-on-surface)',
                                width: '24px',
                                height: '24px',
                                borderRadius: '50%',
                                display: 'flex',
                                alignItems: 'center',
                                justifyContent: 'center',
                                cursor: 'pointer',
                                boxShadow: '0 1px 4px rgba(0,0,0,0.05)',
                                transition: 'all 0.2s'
                              }}
                              onMouseEnter={(e) => {
                                e.currentTarget.style.backgroundColor = 'var(--color-primary)';
                                e.currentTarget.style.color = 'white';
                              }}
                              onMouseLeave={(e) => {
                                e.currentTarget.style.backgroundColor = 'var(--color-surface-low)';
                                e.currentTarget.style.color = 'var(--color-on-surface)';
                              }}
                            >
                              <Plus size={10} />
                            </button>
                          </div>
                        </div>
                      </div>
                    )}

                    {isCalendarOpen ? (
                      <div
                        className="fade-in"
                        style={{
                          backgroundColor: 'var(--color-surface)',
                          padding: '20px',
                          borderRadius: '24px',
                          marginTop: '16px',
                          boxShadow: 'var(--shadow-float)',
                          border: '1px solid var(--color-outline-variant)'
                        }}
                      >
                        <div
                          style={{
                            display: 'flex',
                            justifyContent: 'space-between',
                            alignItems: 'center',
                            marginBottom: '20px'
                          }}
                        >
                          <button
                            type="button"
                            onClick={handlePrevMonth}
                            data-testid="client-booking-calendar-prev-month"
                            style={{
                              background: 'var(--color-surface-low)',
                              border: '1px solid var(--color-outline-variant)',
                              color: 'var(--color-on-surface)',
                              width: '32px',
                              height: '32px',
                              borderRadius: '50%',
                              display: 'flex',
                              alignItems: 'center',
                              justifyContent: 'center',
                              cursor: 'pointer',
                              transition: 'all 0.2s'
                            }}
                            onMouseEnter={(e) => {
                              e.currentTarget.style.backgroundColor = 'var(--color-primary)';
                              e.currentTarget.style.color = 'white';
                            }}
                            onMouseLeave={(e) => {
                              e.currentTarget.style.backgroundColor = 'var(--color-surface-low)';
                              e.currentTarget.style.color = 'var(--color-on-surface)';
                            }}
                          >
                            <ChevronLeft size={16} />
                          </button>
                          <span
                            style={{
                              fontWeight: '800',
                              fontSize: '1.05rem',
                              color: 'var(--color-on-surface)',
                              fontFamily: 'var(--font-display)'
                            }}
                          >
                            {currentYear} 年 {currentMonth + 1} 月
                          </span>
                          <button
                            type="button"
                            onClick={handleNextMonth}
                            data-testid="client-booking-calendar-next-month"
                            style={{
                              background: 'var(--color-surface-low)',
                              border: '1px solid var(--color-outline-variant)',
                              color: 'var(--color-on-surface)',
                              width: '32px',
                              height: '32px',
                              borderRadius: '50%',
                              display: 'flex',
                              alignItems: 'center',
                              justifyContent: 'center',
                              cursor: 'pointer',
                              transition: 'all 0.2s'
                            }}
                            onMouseEnter={(e) => {
                              e.currentTarget.style.backgroundColor = 'var(--color-primary)';
                              e.currentTarget.style.color = 'white';
                            }}
                            onMouseLeave={(e) => {
                              e.currentTarget.style.backgroundColor = 'var(--color-surface-low)';
                              e.currentTarget.style.color = 'var(--color-on-surface)';
                            }}
                          >
                            <ChevronRight size={16} />
                          </button>
                        </div>

                        <div
                          style={{
                            display: 'grid',
                            gridTemplateColumns: 'repeat(7, 1fr)',
                            gap: '8px',
                            marginBottom: '10px'
                          }}
                        >
                          {['日', '一', '二', '三', '四', '五', '六'].map((d) => (
                            <div
                              key={d}
                              style={{
                                textAlign: 'center',
                                fontSize: '0.75rem',
                                fontWeight: '800',
                                color: 'var(--color-on-surface-variant)'
                              }}
                            >
                              {d}
                            </div>
                          ))}
                        </div>

                        <div
                          style={{
                            display: 'grid',
                            gridTemplateColumns: 'repeat(7, 1fr)',
                            gap: '8px',
                            marginBottom: '1.5rem',
                            justifyItems: 'center',
                            alignItems: 'center'
                          }}
                        >
                          {(() => {
                            const firstDay = new Date(currentYear, currentMonth, 1).getDay();
                            const daysNum = new Date(currentYear, currentMonth + 1, 0).getDate();
                            const today = new Date();
                            today.setHours(0, 0, 0, 0);

                            const gridItems = [];

                            for (let i = 0; i < firstDay; i++) {
                              gridItems.push(
                                <div
                                  key={`empty-${i}`}
                                  style={{ width: '100%', maxWidth: '36px', aspectRatio: '1/1' }}
                                />
                              );
                            }

                            for (let day = 1; day <= daysNum; day++) {
                              const dateObj = new Date(currentYear, currentMonth, day);
                              const dateStr = `${currentYear}-${String(currentMonth + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;

                              const isPast = dateObj < today;
                              const isAllowed = !isPast;

                              const isSelectedHere = schedule.dates.includes(dateStr);
                              const isSelectedElsewhere =
                                !isSelectedHere && allSelectedDates.includes(dateStr);

                              gridItems.push(
                                <button
                                  key={dateStr}
                                  type="button"
                                  disabled={!isAllowed || isSelectedElsewhere}
                                  onClick={() =>
                                    isAllowed &&
                                    !isSelectedElsewhere &&
                                    toggleDateInSchedule(pIdx, sIdx, dateStr)
                                  }
                                  data-testid={`client-booking-date-${pIdx}-${sIdx}-${dateStr}`}
                                  style={{
                                    width: '100%',
                                    maxWidth: '36px',
                                    aspectRatio: '1/1',
                                    display: 'flex',
                                    alignItems: 'center',
                                    justifyContent: 'center',
                                    borderRadius: '50%',
                                    padding: '0',
                                    boxSizing: 'border-box',
                                    cursor:
                                      !isAllowed || isSelectedElsewhere ? 'not-allowed' : 'pointer',
                                    fontSize: '0.875rem',
                                    fontWeight: isSelectedHere ? '800' : '600',
                                    backgroundColor: isSelectedHere
                                      ? 'var(--color-primary)'
                                      : isSelectedElsewhere
                                        ? 'var(--color-surface-lowest)'
                                        : 'transparent',
                                    color: isSelectedHere
                                      ? 'var(--color-on-primary)'
                                      : isPast
                                        ? 'var(--color-outline-variant)'
                                        : isSelectedElsewhere
                                          ? 'var(--color-outline-variant)'
                                          : 'var(--color-on-surface)',
                                    transition: 'all 0.2s cubic-bezier(0.4, 0, 0.2, 1)',
                                    border: isSelectedHere ? 'none' : '1px solid transparent',
                                    opacity: isPast ? 0.35 : 1,
                                    textDecoration: isSelectedElsewhere ? 'line-through' : 'none',
                                    boxShadow: isSelectedHere
                                      ? '0 4px 12px rgba(118, 86, 0, 0.25)'
                                      : 'none'
                                  }}
                                  onMouseEnter={(e) => {
                                    if (isAllowed && !isSelectedHere && !isSelectedElsewhere) {
                                      e.currentTarget.style.backgroundColor =
                                        'var(--color-surface-high)';
                                      e.currentTarget.style.borderColor =
                                        'var(--color-outline-variant)';
                                    }
                                  }}
                                  onMouseLeave={(e) => {
                                    if (!isSelectedHere) {
                                      e.currentTarget.style.backgroundColor = 'transparent';
                                      e.currentTarget.style.borderColor = 'transparent';
                                    }
                                  }}
                                >
                                  {day}
                                </button>
                              );
                            }
                            return gridItems;
                          })()}
                        </div>
                        <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                          <button
                            onClick={() => setActiveCalendar(null)}
                            style={{
                              padding: '10px 24px',
                              fontSize: '0.875rem',
                              fontWeight: '800',
                              backgroundColor: 'var(--color-primary)',
                              color: 'var(--color-on-primary)',
                              border: 'none',
                              borderRadius: '100px',
                              cursor: 'pointer',
                              boxShadow: '0 4px 12px rgba(118, 86, 0, 0.25)',
                              transition: 'all 0.2s'
                            }}
                            onMouseEnter={(e) => {
                              e.currentTarget.style.transform = 'translateY(-1px)';
                              e.currentTarget.style.boxShadow = '0 6px 16px rgba(118, 86, 0, 0.35)';
                            }}
                            onMouseLeave={(e) => {
                              e.currentTarget.style.transform = 'translateY(0)';
                              e.currentTarget.style.boxShadow = '0 4px 12px rgba(118, 86, 0, 0.25)';
                            }}
                            data-testid={`client-booking-btn-confirm-date-${pIdx}-${sIdx}`}
                          >
                            確定日期
                          </button>
                        </div>
                      </div>
                    ) : (
                      <div
                        className="fade-in"
                        style={{
                          display: 'flex',
                          justifyContent: 'space-between',
                          alignItems: 'flex-start',
                          marginTop: '0',
                          gap: '16px',
                          paddingTop: '20px',
                          borderTop: '1px solid rgba(0,0,0,0.05)'
                        }}
                      >
                        <div style={{ flex: 1, paddingTop: '0' }}>
                          {!groupedDates ? (
                            <div
                              style={{
                                color: 'var(--color-text-variant)',
                                fontSize: '0.875rem',
                                marginTop: '0',
                                fontStyle: 'italic',
                                opacity: 0.5
                              }}
                            >
                              點擊上方按鈕開始規劃行程...
                            </div>
                          ) : (
                            Object.entries(groupedDates).map(([year, datesList]) => (
                              <div key={year}>
                                <div
                                  style={{
                                    fontSize: '0.75rem',
                                    fontWeight: '800',
                                    color: 'var(--color-on-surface-variant)',
                                    marginTop: '0',
                                    marginBottom: '4px',
                                    letterSpacing: '0.05em'
                                  }}
                                >
                                  {year}年
                                </div>
                                <div
                                  style={{
                                    color: 'var(--color-primary)',
                                    fontWeight: '700',
                                    fontSize: '1rem',
                                    lineHeight: '1.4'
                                  }}
                                >
                                  {datesList.join(', ')}
                                </div>
                              </div>
                            ))
                          )}
                        </div>

                        <div
                          style={{
                            display: 'flex',
                            flexDirection: 'column',
                            alignItems: 'flex-end',
                            gap: '12px',
                            flexShrink: 0
                          }}
                        >
                          <button
                            onClick={() => removeScheduleFromPlan(pIdx, sIdx)}
                            style={{
                              background: 'none',
                              border: 'none',
                              color: 'var(--color-on-surface-variant)',
                              cursor: 'pointer',
                              padding: '8px',
                              borderRadius: '10px',
                              display: 'flex',
                              alignItems: 'center',
                              justifyContent: 'center',
                              transition: 'all 0.3s',
                              opacity: 0.6
                            }}
                            onMouseEnter={(e) => {
                              e.currentTarget.style.backgroundColor = 'rgba(186, 26, 26, 0.08)';
                              e.currentTarget.style.color = 'var(--color-error)';
                              e.currentTarget.style.opacity = '1';
                              e.currentTarget.style.transform = 'rotate(8deg)';
                            }}
                            onMouseLeave={(e) => {
                              e.currentTarget.style.backgroundColor = 'transparent';
                              e.currentTarget.style.color = 'var(--color-on-surface-variant)';
                              e.currentTarget.style.opacity = '0.6';
                              e.currentTarget.style.transform = 'rotate(0)';
                            }}
                            data-testid={`client-booking-remove-schedule-${pIdx}-${sIdx}`}
                            title="移除此日期排程"
                          >
                            <Trash2 size={20} />
                          </button>
                        </div>

                        {/* 選擇毛孩區域 */}
                        <div
                          style={{
                            marginTop: '16px',
                            paddingTop: '16px',
                            borderTop: '1px solid rgba(0,0,0,0.05)',
                            width: '100%'
                          }}
                        >
                          <div
                            style={{
                              fontSize: '0.75rem',
                              fontWeight: '850',
                              color: 'var(--color-on-surface-variant)',
                              marginBottom: '8px',
                              letterSpacing: '0.05em',
                              textTransform: 'uppercase'
                            }}
                          >
                            照護對象 (毛孩)
                          </div>
                          {(() => {
                            const allowedTypes = plan?.applicablePetTypes || [];
                            const eligiblePets = pets.filter(
                              (p) => allowedTypes.length === 0 || allowedTypes.includes(p.species)
                            );

                            if (pets.length === 0) {
                              return (
                                <div
                                  style={{
                                    fontSize: '0.85rem',
                                    color: 'var(--color-error)',
                                    fontStyle: 'italic'
                                  }}
                                >
                                  您名下尚無毛孩資料，請先至個人檔案新增毛孩。
                                </div>
                              );
                            }

                            if (eligiblePets.length === 0) {
                              return (
                                <div
                                  style={{
                                    fontSize: '0.85rem',
                                    color: 'var(--color-error)',
                                    fontStyle: 'italic'
                                  }}
                                  data-testid={`client-booking-no-eligible-pet-warning-${pIdx}-${sIdx}`}
                                >
                                  您沒有適合此方案 ({allowedTypes.join('/')})
                                  的寵物，請先至個人檔案新增。
                                </div>
                              );
                            }

                            return (
                              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px' }}>
                                {eligiblePets.map((pet) => {
                                  const isChecked = (schedule.petIds || []).includes(pet.id!);
                                  return (
                                    <label
                                      key={pet.id}
                                      style={{
                                        display: 'inline-flex',
                                        alignItems: 'center',
                                        gap: '8px',
                                        padding: '6px 12px',
                                        backgroundColor: isChecked
                                          ? 'var(--color-primary)'
                                          : 'var(--color-surface)',
                                        color: isChecked
                                          ? 'var(--color-on-primary)'
                                          : 'var(--color-on-surface)',
                                        border: '1px solid var(--color-outline-variant)',
                                        borderRadius: '12px',
                                        fontSize: '0.875rem',
                                        fontWeight: '700',
                                        cursor: 'pointer',
                                        transition: 'all 0.2s',
                                        boxShadow: isChecked
                                          ? '0 2px 6px rgba(118, 86, 0, 0.2)'
                                          : 'none'
                                      }}
                                      data-testid={`client-booking-pet-label-${pIdx}-${sIdx}-${pet.name}`}
                                    >
                                      <input
                                        type="checkbox"
                                        checked={isChecked}
                                        onChange={() => togglePetInSchedule(pIdx, sIdx, pet.id!)}
                                        style={{ display: 'none' }}
                                        data-testid={`client-booking-pet-checkbox-${pIdx}-${sIdx}-${pet.name}`}
                                      />
                                      {pet.photoUrl ? (
                                        <img
                                          src={pet.photoUrl}
                                          alt={pet.name}
                                          style={{
                                            width: '20px',
                                            height: '20px',
                                            borderRadius: '50%',
                                            objectFit: 'cover'
                                          }}
                                        />
                                      ) : (
                                        <div
                                          style={{
                                            width: '20px',
                                            height: '20px',
                                            borderRadius: '50%',
                                            backgroundColor: 'var(--color-surface-high)',
                                            display: 'flex',
                                            alignItems: 'center',
                                            justifyContent: 'center',
                                            fontSize: '0.65rem'
                                          }}
                                        >
                                          {pet.name.charAt(0)}
                                        </div>
                                      )}
                                      {pet.name} (
                                      {pet.species === 'CAT'
                                        ? '貓咪'
                                        : pet.species === 'DOG'
                                          ? '狗狗'
                                          : pet.species}
                                      )
                                    </label>
                                  );
                                })}
                              </div>
                            );
                          })()}
                        </div>
                      </div>
                    )}
                  </div>
                );
              })}

              {/* 新增其他日期按鈕放置於白色方案區域底部 */}
              <div style={{ display: 'flex', justifyContent: 'flex-start', marginTop: '12px' }}>
                <button
                  onClick={() => addScheduleToPlan(pIdx)}
                  style={{
                    background: 'var(--color-surface-low)',
                    border: '1px dashed var(--color-outline-variant)',
                    color: 'var(--color-primary)',
                    fontWeight: '800',
                    cursor: 'pointer',
                    fontSize: '0.9rem',
                    display: 'flex',
                    alignItems: 'center',
                    gap: '8px',
                    padding: '12px 24px',
                    borderRadius: '16px',
                    transition: 'all 0.3s cubic-bezier(0.4, 0, 0.2, 1)',
                    boxShadow: '0 2px 6px rgba(0,0,0,0.02)'
                  }}
                  onMouseEnter={(e) => {
                    e.currentTarget.style.backgroundColor = 'var(--color-surface-high)';
                    e.currentTarget.style.borderColor = 'var(--color-primary)';
                    e.currentTarget.style.transform = 'translateY(-1px)';
                    e.currentTarget.style.boxShadow = '0 4px 12px rgba(118, 86, 0, 0.08)';
                  }}
                  onMouseLeave={(e) => {
                    e.currentTarget.style.backgroundColor = 'var(--color-surface-low)';
                    e.currentTarget.style.borderColor = 'var(--color-outline-variant)';
                    e.currentTarget.style.transform = 'translateY(0)';
                    e.currentTarget.style.boxShadow = '0 2px 6px rgba(0,0,0,0.02)';
                  }}
                  data-testid={`client-booking-btn-add-schedule-${pIdx}`}
                >
                  <Plus size={16} /> 新增其他日期
                </button>
              </div>
            </div>
          );
        })}

        <button
          onClick={() => setIsAddingPlan(true)}
          style={{
            width: '100%',
            padding: '20px',
            background: 'var(--color-surface)',
            border: '2px dashed var(--color-outline-variant)',
            color: 'var(--color-primary)',
            borderRadius: '24px',
            fontWeight: '800',
            fontSize: '1rem',
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: '10px',
            transition: 'all 0.3s',
            marginBottom: '2rem'
          }}
          onMouseEnter={(e) => {
            e.currentTarget.style.borderColor = 'var(--color-primary)';
            e.currentTarget.style.transform = 'translateY(-2px)';
            e.currentTarget.style.boxShadow = 'var(--shadow-ambient)';
          }}
          onMouseLeave={(e) => {
            e.currentTarget.style.borderColor = 'var(--color-outline-variant)';
            e.currentTarget.style.transform = 'translateY(0)';
            e.currentTarget.style.boxShadow = 'none';
          }}
          data-testid="client-booking-btn-add-plan"
        >
          <Plus size={20} /> 新增其他方案
        </button>

        <div style={{ marginTop: '2rem' }}>
          <Button
            className="btn-primary"
            fullWidth
            disabled={isNextDisabled}
            onClick={handleNextToStep2}
            data-testid="client-booking-btn-step1-next"
            style={{
              padding: '16px 32px',
              borderRadius: '100px',
              fontSize: '1.1rem',
              fontWeight: '800',
              boxShadow: '0 8px 24px rgba(118, 86, 0, 0.2)'
            }}
          >
            最後確認 <ArrowRight size={20} style={{ marginLeft: '8px' }} />
          </Button>
        </div>
      </div>
    );
  };

  const renderStep2 = () => {
    const total = booking.planConfigs.reduce((acc, planConfig) => {
      const plan = plans.find((p) => p.id === planConfig.planId);
      const planTotal = planConfig.schedules.reduce((sAcc, schedule) => {
        return sAcc + (plan?.price || 0) * schedule.dates.length * schedule.timesPerDay;
      }, 0);
      return acc + planTotal;
    }, 0);

    return (
      <div className="fade-in">
        <h2
          style={{
            fontSize: '1.5rem',
            fontWeight: '800',
            marginBottom: '0.5rem',
            fontFamily: 'var(--font-display)',
            letterSpacing: '-0.025em'
          }}
        >
          預約摘要
        </h2>
        <p
          style={{
            color: 'var(--color-on-surface-variant)',
            marginBottom: '2rem',
            fontSize: '0.875rem'
          }}
        >
          請確認複合方案資訊無誤
        </p>

        <Card style={{ marginBottom: '2rem' }}>
          <div style={{ marginBottom: '1.5rem' }}>
            <label
              style={{
                fontSize: '0.75rem',
                fontWeight: '700',
                color: 'var(--color-on-surface-variant)',
                textTransform: 'uppercase'
              }}
            >
              排程明細
            </label>
            <div
              style={{ marginTop: '0.5rem', display: 'flex', flexDirection: 'column', gap: '12px' }}
            >
              {booking.planConfigs.map((planConfig, pIdx) => {
                const plan = plans.find((p) => p.id === planConfig.planId);
                return planConfig.schedules.map((schedule, sIdx) => {
                  const groupedDates = formatDatesGroupedByYear(schedule.dates);
                  return (
                    <div
                      key={`${pIdx}-${sIdx}`}
                      style={{
                        padding: '12px',
                        backgroundColor: 'var(--color-surface-low)',
                        borderRadius: '8px',
                        borderLeft: '4px solid var(--color-primary)'
                      }}
                    >
                      <div style={{ fontWeight: '800', fontSize: '0.875rem', marginBottom: '4px' }}>
                        {plan?.name} (每次 {plan?.durationMinutes ?? 60} 分鐘 / 每天 {schedule.timesPerDay} 次)
                      </div>
                      <div
                        style={{
                          fontSize: '0.75rem',
                          color: 'var(--color-on-surface-variant)',
                          lineHeight: '1.4'
                        }}
                      >
                        日期：
                        {groupedDates
                          ? Object.entries(groupedDates)
                              .map(([y, ds]) => `${y}年 ${ds.join(', ')}`)
                              .join(' | ')
                          : '無'}
                      </div>
                      <div
                        style={{
                          textAlign: 'right',
                          fontWeight: '800',
                          fontSize: '0.875rem',
                          marginTop: '8px',
                          color: 'var(--color-primary)'
                        }}
                      >
                        小計: ${' '}
                        {(
                          (plan?.price || 0) *
                          schedule.dates.length *
                          schedule.timesPerDay
                        ).toLocaleString()}
                      </div>
                    </div>
                  );
                });
              })}
            </div>
          </div>

          <div
            style={{
              backgroundColor: 'var(--color-surface-low)',
              margin: '1.5rem -1.5rem -1.5rem -1.5rem',
              padding: '1.5rem',
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              borderBottomLeftRadius: 'var(--radius-lg)',
              borderBottomRightRadius: 'var(--radius-lg)'
            }}
          >
            <span style={{ fontWeight: '800', fontSize: '1.125rem' }}>預計總額</span>
            <span
              data-testid="client-booking-total"
              style={{ fontWeight: '800', fontSize: '1.5rem', color: 'var(--color-primary)' }}
            >
              $ {total.toLocaleString()}
            </span>
          </div>
        </Card>

        {questions.length > 0 && (
          <Card style={{ marginBottom: '2rem' }} data-testid="client-booking-questionnaire">
            <label
              style={{
                fontSize: '0.75rem',
                fontWeight: '700',
                color: 'var(--color-on-surface-variant)',
                textTransform: 'uppercase'
              }}
            >
              保母的事前問卷
            </label>
            <div style={{ marginTop: '0.75rem', display: 'flex', flexDirection: 'column', gap: '1.25rem' }}>
              {questions.map((q) => (
                <div key={q.id} data-testid={`booking-question-${q.id}`}>
                  <div style={{ fontWeight: '600', marginBottom: '0.5rem', fontSize: '0.9rem' }}>
                    {q.questionText}
                    {q.required && <span style={{ color: '#dc2626' }}> *</span>}
                  </div>

                  {(q.answerType === 'RADIO') && (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '0.4rem' }}>
                      {q.options.map((option) => (
                        <label key={option} style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.85rem' }}>
                          <input
                            type="radio"
                            name={`question-${q.id}`}
                            checked={(questionAnswers[q.id!] || [])[0] === option}
                            onChange={() => setSingleAnswer(q.id!, option)}
                            data-testid={`booking-question-${q.id}-option-${option}`}
                          />
                          {option}
                        </label>
                      ))}
                    </div>
                  )}

                  {q.answerType === 'CHECKBOX' && (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '0.4rem' }}>
                      {q.options.map((option) => (
                        <label key={option} style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.85rem' }}>
                          <input
                            type="checkbox"
                            checked={(questionAnswers[q.id!] || []).includes(option)}
                            onChange={() => toggleCheckboxAnswer(q.id!, option)}
                            data-testid={`booking-question-${q.id}-option-${option}`}
                          />
                          {option}
                        </label>
                      ))}
                    </div>
                  )}

                  {q.answerType === 'INPUT' && (
                    <input
                      type="text"
                      value={(questionAnswers[q.id!] || [])[0] || ''}
                      onChange={(e) => setSingleAnswer(q.id!, e.target.value)}
                      data-testid={`booking-question-${q.id}-input`}
                      style={{
                        width: '100%',
                        padding: '0.6rem',
                        borderRadius: '8px',
                        border: '1px solid var(--color-outline-variant)',
                        boxSizing: 'border-box'
                      }}
                    />
                  )}

                  {q.answerType === 'TEXTAREA' && (
                    <textarea
                      value={(questionAnswers[q.id!] || [])[0] || ''}
                      onChange={(e) => setSingleAnswer(q.id!, e.target.value)}
                      rows={3}
                      data-testid={`booking-question-${q.id}-textarea`}
                      style={{
                        width: '100%',
                        padding: '0.6rem',
                        borderRadius: '8px',
                        border: '1px solid var(--color-outline-variant)',
                        resize: 'none',
                        boxSizing: 'border-box'
                      }}
                    />
                  )}
                </div>
              ))}
            </div>
          </Card>
        )}

        <textarea
          placeholder="有什麼特別需要注意的地方嗎？"
          value={booking.notes}
          onChange={(e) => setBooking((prev) => ({ ...prev, notes: e.target.value }))}
          data-testid="client-booking-input-notes"
          style={{
            width: '100%',
            height: '100px',
            padding: '1rem',
            borderRadius: 'var(--radius-sm)',
            border: '1px solid var(--color-outline-variant)',
            backgroundColor: 'var(--color-surface-lowest)',
            fontFamily: 'var(--font-body)',
            marginBottom: '2rem',
            resize: 'none',
            boxSizing: 'border-box'
          }}
        />

        <div style={{ display: 'flex', gap: '12px' }}>
          <Button
            variant="secondary"
            onClick={() => setStep(1)}
            style={{ flex: 1 }}
            data-testid="client-booking-btn-step2-back"
          >
            上一步
          </Button>
          <Button
            className="btn-primary"
            style={{ flex: 2, padding: '12px', fontSize: '1rem' }}
            onClick={handleSubmitBooking}
            disabled={profile?.gated || !profile?.isOpen || isSubmitting}
            data-testid="client-booking-btn-submit"
          >
            {isSubmitting ? '送出中...' : (
              <>
                確認並送出 <CheckCircle2 size={18} style={{ marginLeft: '8px' }} />
              </>
            )}
          </Button>
        </div>
      </div>
    );
  };

  return (
    <div style={{ padding: '2rem 1.5rem' }}>
      {/* 警示 Banner */}
      {profile?.gated && (
        <div
          style={{
            backgroundColor: '#fee2e2',
            border: '1px solid #fca5a5',
            padding: '1rem',
            borderRadius: '8px',
            color: '#b91c1c',
            marginBottom: '1.5rem',
            fontWeight: 'bold',
            fontSize: '0.875rem'
          }}
        >
          此保母目前暫停服務或處於休息狀態，暫時無法接受預約。
        </div>
      )}
      {!profile?.gated && !profile?.isOpen && (
        <div
          style={{
            backgroundColor: '#fef3c7',
            border: '1px solid #fcd34d',
            padding: '1rem',
            borderRadius: '8px',
            color: '#b45309',
            marginBottom: '1.5rem',
            fontWeight: 'bold',
            fontSize: '0.875rem'
          }}
        >
          此保母目前不開放預約，您仍可瀏覽其公開檔案。
        </div>
      )}

      {/* 保母基本公開檔案 */}
      <Card style={{ marginBottom: '2rem', padding: '1.5rem' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '1.5rem' }}>
          <img
            src={profile?.avatarUrl || 'https://via.placeholder.com/150'}
            alt="Sitter Avatar"
            style={{
              width: '80px',
              height: '80px',
              borderRadius: '50%',
              objectFit: 'cover',
              border: '2px solid var(--color-primary-light)'
            }}
          />
          <div>
            <h2
              data-testid="client-booking-sitter-name"
              style={{ margin: 0, fontSize: '1.5rem', fontWeight: '800', color: 'var(--color-on-surface)' }}
            >
              {profile?.displayName || '保母'}
            </h2>
            <p
              ref={bioRef}
              data-testid="client-booking-sitter-bio"
              style={{
                margin: '8px 0',
                fontSize: '0.875rem',
                color: 'var(--color-on-surface-variant)',
                lineHeight: '1.5',
                ...(isBioExpanded
                  ? {}
                  : {
                      display: '-webkit-box',
                      WebkitLineClamp: 5,
                      WebkitBoxOrient: 'vertical' as const,
                      overflow: 'hidden'
                    })
              }}
            >
              {profile?.bio || '尚無自我介紹'}
            </p>
            {isBioOverflowing && (
              <button
                onClick={() => setIsBioExpanded((prev) => !prev)}
                data-testid="client-booking-sitter-bio-toggle"
                style={{
                  background: 'none',
                  border: 'none',
                  color: 'var(--color-primary)',
                  fontSize: '0.8125rem',
                  fontWeight: 700,
                  cursor: 'pointer',
                  padding: 0,
                  marginBottom: '8px'
                }}
              >
                {isBioExpanded ? '收合' : '顯示更多'}
              </button>
            )}
            
            {/* 標籤 */}
            {profile?.tags && profile.tags.length > 0 && (
              <div
                data-testid="client-booking-sitter-tags"
                style={{ display: 'flex', flexWrap: 'wrap', gap: '6px', marginTop: '8px' }}
              >
                {profile.tags.map((tag, idx) => (
                  <span
                    key={idx}
                    style={{
                      padding: '2px 8px',
                      borderRadius: '12px',
                      fontSize: '0.75rem',
                      backgroundColor: 'var(--color-primary-lowest)',
                      color: 'var(--color-primary)',
                      border: '1px solid var(--color-primary-light)'
                    }}
                  >
                    {tag}
                  </span>
                ))}
              </div>
            )}

            {/* 服務區域 */}
            {profile?.serviceAreas && profile.serviceAreas.length > 0 && (
              <div
                data-testid="client-booking-sitter-areas"
                style={{ display: 'flex', flexWrap: 'wrap', gap: '6px', marginTop: '8px' }}
              >
                {profile.serviceAreas.map((area, idx) => (
                  <span
                    key={idx}
                    style={{
                      padding: '2px 8px',
                      borderRadius: '4px',
                      fontSize: '0.75rem',
                      backgroundColor: 'var(--color-surface-high)',
                      color: 'var(--color-on-surface)',
                      border: '1px solid var(--color-surface-higher)'
                    }}
                  >
                    {area.city} {area.district}
                  </span>
                ))}
              </div>
            )}
          </div>
        </div>
      </Card>

      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: '3rem'
        }}
      >
        <div style={{ display: 'flex', gap: '8px' }}>

          {[1, 2].map((s) => (
            <div
              key={s}
              data-testid={`client-booking-step-indicator-${s}`}
              style={{
                width: '48px',
                height: '4px',
                backgroundColor: step >= s ? 'var(--color-primary)' : 'var(--color-surface-high)',
                borderRadius: '2px',
                transition: 'all 0.3s ease'
              }}
            />
          ))}
        </div>
        <span
          style={{
            fontSize: '0.75rem',
            fontWeight: '800',
            color: 'var(--color-on-surface-variant)',
            letterSpacing: '0.05em'
          }}
        >
          STEP {step} / 2
        </span>
      </div>

      {step === 1 && renderStep1()}
      {step === 2 && renderStep2()}
    </div>
  );
};

export default PublicBookingPage;
