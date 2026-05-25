import React, { useState, useEffect } from 'react';
import Card from '../../components/ui/Card';
import Button from '../../components/ui/Button';
import { CheckCircle2, ArrowRight, Plus, Trash2, CalendarDays, Minus, CalendarX, ChevronLeft, ChevronRight } from 'lucide-react';
import type { BookingState } from '../../types/booking';
import { useSitterActivePlansQuery } from '../../hooks/useServicePlans';

const formatDatesGroupedByYear = (dates: string[]) => {
  if (!dates || dates.length === 0) return null;
  const grouped: Record<string, string[]> = {};
  dates.forEach(d => {
    const [year, month, day] = d.split('-');
    if (!grouped[year]) grouped[year] = [];
    grouped[year].push(`${parseInt(month)}/${parseInt(day)}`);
  });
  return grouped;
};

interface PublicBookingPageProps {
  sitterId?: string;
}

const PublicBookingPage: React.FC<PublicBookingPageProps> = ({ sitterId = '3d498178-14c0-4376-b81e-7fb02e615dda' }) => {
  const { data: plans = [], isLoading, error } = useSitterActivePlansQuery(sitterId);
  const [step, setStep] = useState(1);
  const [booking, setBooking] = useState<BookingState>({
    sitterId: sitterId,
    planConfigs: [],
    notes: '',
    totalAmount: 0
  });

  useEffect(() => {
    setBooking(prev => ({ ...prev, sitterId }));
  }, [sitterId]);
  
  const [isAddingPlan, setIsAddingPlan] = useState(true);
  const [activeCalendar, _setActiveCalendar] = useState<{ planIndex: number; scheduleIndex: number } | null>(null);
  
  const [currentYear, setCurrentYear] = useState(new Date().getFullYear());
  const [currentMonth, setCurrentMonth] = useState(new Date().getMonth());

  if (isLoading) {
    return (
      <div style={{ padding: '4rem 2rem', textAlign: 'center', color: 'var(--color-primary)' }}>
        <div style={{ fontSize: '1.25rem', fontWeight: '800' }}>讀取方案中...</div>
      </div>
    );
  }

  if (error) {
    return (
      <div style={{ padding: '4rem 2rem', textAlign: 'center', color: 'var(--color-error)' }}>
        <div style={{ fontSize: '1.25rem', fontWeight: '800' }}>載入方案失敗，請稍後再試</div>
      </div>
    );
  }

  if (plans.length === 0) {
    return (
      <div style={{ padding: '4rem 2rem', textAlign: 'center' }}>
        <h2 style={{ fontSize: '1.5rem', fontWeight: '800', marginBottom: '1rem', color: 'var(--color-on-surface)' }}>尚無服務方案</h2>
        <p style={{ color: 'var(--color-on-surface-variant)' }}>此保母目前尚未設定任何服務方案，請稍後再試或與保母聯繫。</p>
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
      setCurrentYear(prev => prev - 1);
    } else {
      setCurrentMonth(prev => prev - 1);
    }
  };

  const handleNextMonth = () => {
    if (currentMonth === 11) {
      setCurrentMonth(0);
      setCurrentYear(prev => prev + 1);
    } else {
      setCurrentMonth(prev => prev + 1);
    }
  };

  const allSelectedDates = booking.planConfigs.flatMap(p => p.schedules.flatMap(s => s.dates));

  const addPlanConfig = (planId: string) => {
    setBooking(prev => {
      const newConfigs = [...prev.planConfigs, { planId, schedules: [{ dates: [], timesPerDay: 1 }] }];
      setActiveCalendar({ planIndex: prev.planConfigs.length, scheduleIndex: 0 });
      return {
        ...prev,
        planConfigs: newConfigs
      };
    });
    setIsAddingPlan(false);
  };

  const addScheduleToPlan = (planIndex: number) => {
    setBooking(prev => {
      const newConfigs = [...prev.planConfigs];
      const plan = { ...newConfigs[planIndex] };
      plan.schedules = [...plan.schedules, { dates: [], timesPerDay: 1 }];
      newConfigs[planIndex] = plan;
      setActiveCalendar({ planIndex, scheduleIndex: plan.schedules.length - 1 });
      return { ...prev, planConfigs: newConfigs };
    });
  };

  const removeScheduleFromPlan = (planIndex: number, scheduleIndex: number) => {
    setBooking(prev => {
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
    setBooking(prev => {
      const newConfigs = [...prev.planConfigs];
      const plan = { ...newConfigs[planIndex] };
      const schedules = [...plan.schedules];
      const schedule = { ...schedules[scheduleIndex] };
      
      if (schedule.dates.includes(date)) {
        schedule.dates = schedule.dates.filter(d => d !== date);
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
    setBooking(prev => {
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
    const isNextDisabled = booking.planConfigs.length === 0 || 
                           booking.planConfigs.some(pc => pc.schedules.some(s => s.dates.length === 0));

    if (isAddingPlan) {
      return (
        <div className="fade-in" style={{ padding: '0 0 2rem 0' }}>
          <div style={{ marginBottom: '2rem' }}>
            <h2 style={{ fontSize: '1.75rem', fontWeight: '800', marginBottom: '0.5rem', fontFamily: 'var(--font-display)', letterSpacing: '-0.03em' }}>選擇服務方案</h2>
            <p style={{ color: 'var(--color-on-surface-variant)', fontSize: '0.95rem' }}>為您的愛貓挑選最貼心的陪伴方式</p>
          </div>
          
          <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            {plans.filter(p => !booking.planConfigs.some(pc => pc.planId === p.id)).map(plan => (
              <div 
                key={plan.id} 
                onClick={() => addPlanConfig(plan.id!)}
                style={{ cursor: 'pointer', padding: '24px', backgroundColor: 'var(--color-surface)', borderRadius: '16px', border: '1px solid var(--color-outline-variant)', transition: 'all 0.3s cubic-bezier(0.4, 0, 0.2, 1)', boxShadow: 'var(--shadow-ambient)' }}
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
                data-testid={`client-booking-plan-card-${plan.id}`}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
                  <h3 style={{ fontWeight: '800', margin: 0, fontSize: '1.25rem', color: 'var(--color-on-surface)' }}>{plan.name}</h3>
                  <span style={{ color: 'var(--color-primary)', fontWeight: '900', fontSize: '1.1rem' }}>$ {plan.price} / 次</span>
                </div>
                <p style={{ fontSize: '0.9rem', color: 'var(--color-on-surface-variant)', margin: 0, lineHeight: 1.5 }}>{plan.description}</p>
              </div>
            ))}

            {/* 檔期不足模擬 */}
            <div style={{ padding: '24px', backgroundColor: 'transparent', borderRadius: '16px', border: '1px dashed var(--color-outline-variant)', opacity: 0.6, cursor: 'not-allowed' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
                <h3 style={{ fontWeight: '800', margin: 0, fontSize: '1.25rem', color: 'var(--color-on-surface-variant)' }}>尊榮包月</h3>
                <span style={{ color: 'var(--color-on-surface-variant)', fontWeight: '700' }}>$ 15,000 / 月</span>
              </div>
              <p style={{ fontSize: '0.85rem', color: 'var(--color-error)', margin: '8px 0 0 0', fontWeight: '700', display: 'flex', alignItems: 'center', gap: '6px' }}>
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
                style={{ border: 'none', backgroundColor: 'rgba(0,0,0,0.04)', borderRadius: '100px' }}
              >
                <ArrowRight size={18} style={{ transform: 'rotate(180deg)', marginRight: '8px' }} /> 返回上頁
              </Button>
            </div>
          )}
        </div>
      );
    }

    return (
      <div className="fade-in">
        <div style={{ marginBottom: '2rem' }}>
          <h2 style={{ fontSize: '1.75rem', fontWeight: '800', marginBottom: '0.5rem', fontFamily: 'var(--font-display)', letterSpacing: '-0.03em' }}>排程配置</h2>
          <p style={{ color: 'var(--color-on-surface-variant)', fontSize: '0.95rem' }}>請設定您的預約日期與次數</p>
        </div>

        {booking.planConfigs.map((planConfig, pIdx) => {
          const plan = plans.find(p => p.id === planConfig.planId);
          return (
            <div key={planConfig.planId} style={{ marginBottom: '2rem', padding: '24px', backgroundColor: 'var(--color-surface)', borderRadius: '24px', boxShadow: 'var(--shadow-ambient)' }}>
              <h3 style={{ fontSize: '1.25rem', fontWeight: '800', marginBottom: '24px', color: 'var(--color-primary)', display: 'flex', alignItems: 'center', gap: '12px' }}>
                <span style={{ width: '4px', height: '24px', backgroundColor: 'var(--color-primary)', borderRadius: '2px' }}></span>
                {plan?.name}
              </h3>
              
              {planConfig.schedules.map((schedule, sIdx) => {
                const isCalendarOpen = activeCalendar?.planIndex === pIdx && activeCalendar?.scheduleIndex === sIdx;
                const groupedDates = formatDatesGroupedByYear(schedule.dates);
                
                return (
                  <div key={sIdx} style={{ backgroundColor: 'var(--color-surface-low)', borderRadius: '16px', padding: '20px', marginBottom: '20px' }}>
                    
                    {!isCalendarOpen && (
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: '12px', marginBottom: '20px' }}>
                        <button 
                          onClick={() => setActiveCalendar({ planIndex: pIdx, scheduleIndex: sIdx })}
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
                          onMouseEnter={(e) => { e.currentTarget.style.transform = 'translateY(-2px) scale(1.05)'; e.currentTarget.style.boxShadow = '0 6px 16px rgba(118, 86, 0, 0.3)'; }}
                          onMouseLeave={(e) => { e.currentTarget.style.transform = 'translateY(0) scale(1)'; e.currentTarget.style.boxShadow = '0 4px 12px rgba(118, 86, 0, 0.2)'; }}
                          data-testid={`client-booking-open-calendar-${pIdx}-${sIdx}`}
                          title="選擇日期"
                        >
                          <CalendarDays size={20} />
                        </button>

                        <div style={{ 
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
                        }}>
                          <div style={{ fontSize: '0.75rem', fontWeight: '800', color: 'var(--color-on-surface-variant)', whiteSpace: 'nowrap', opacity: 0.8 }}>每日次數</div>
                          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexShrink: 0 }}>
                            <button 
                              onClick={() => updateTimesPerDay(pIdx, sIdx, Math.max(1, schedule.timesPerDay - 1))}
                              data-testid={`client-booking-times-minus-${pIdx}-${sIdx}`}
                              style={{ background: 'var(--color-surface-low)', border: 'none', color: 'var(--color-on-surface)', width: '24px', height: '24px', borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', boxShadow: '0 1px 4px rgba(0,0,0,0.05)', transition: 'all 0.2s' }}
                              onMouseEnter={(e) => { e.currentTarget.style.backgroundColor = 'var(--color-primary)'; e.currentTarget.style.color = 'white'; }}
                              onMouseLeave={(e) => { e.currentTarget.style.backgroundColor = 'var(--color-surface-low)'; e.currentTarget.style.color = 'var(--color-on-surface)'; }}
                            >
                              <Minus size={10} />
                            </button>
                            <div data-testid={`client-booking-times-val-${pIdx}-${sIdx}`} style={{ fontWeight: '800', fontSize: '1rem', width: '20px', textAlign: 'center', color: 'var(--color-primary)' }}>
                              {schedule.timesPerDay}
                            </div>
                            <button 
                              onClick={() => updateTimesPerDay(pIdx, sIdx, Math.min(9, schedule.timesPerDay + 1))}
                              data-testid={`client-booking-times-plus-${pIdx}-${sIdx}`}
                              style={{ background: 'var(--color-surface-low)', border: 'none', color: 'var(--color-on-surface)', width: '24px', height: '24px', borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', boxShadow: '0 1px 4px rgba(0,0,0,0.05)', transition: 'all 0.2s' }}
                              onMouseEnter={(e) => { e.currentTarget.style.backgroundColor = 'var(--color-primary)'; e.currentTarget.style.color = 'white'; }}
                              onMouseLeave={(e) => { e.currentTarget.style.backgroundColor = 'var(--color-surface-low)'; e.currentTarget.style.color = 'var(--color-on-surface)'; }}
                            >
                              <Plus size={10} />
                            </button>
                          </div>
                        </div>
                      </div>
                    )}

                    {isCalendarOpen ? (
                      <div className="fade-in" style={{ backgroundColor: 'var(--color-surface)', padding: '20px', borderRadius: '24px', marginTop: '16px', boxShadow: 'var(--shadow-float)', border: '1px solid var(--color-outline-variant)' }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px' }}>
                          <button
                            type="button"
                            onClick={handlePrevMonth}
                            data-testid="client-booking-calendar-prev-month"
                            style={{ background: 'var(--color-surface-low)', border: '1px solid var(--color-outline-variant)', color: 'var(--color-on-surface)', width: '32px', height: '32px', borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', transition: 'all 0.2s' }}
                            onMouseEnter={(e) => { e.currentTarget.style.backgroundColor = 'var(--color-primary)'; e.currentTarget.style.color = 'white'; }}
                            onMouseLeave={(e) => { e.currentTarget.style.backgroundColor = 'var(--color-surface-low)'; e.currentTarget.style.color = 'var(--color-on-surface)'; }}
                          >
                            <ChevronLeft size={16} />
                          </button>
                          <span style={{ fontWeight: '800', fontSize: '1.05rem', color: 'var(--color-on-surface)', fontFamily: 'var(--font-display)' }}>
                            {currentYear} 年 {currentMonth + 1} 月
                          </span>
                          <button
                            type="button"
                            onClick={handleNextMonth}
                            data-testid="client-booking-calendar-next-month"
                            style={{ background: 'var(--color-surface-low)', border: '1px solid var(--color-outline-variant)', color: 'var(--color-on-surface)', width: '32px', height: '32px', borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', transition: 'all 0.2s' }}
                            onMouseEnter={(e) => { e.currentTarget.style.backgroundColor = 'var(--color-primary)'; e.currentTarget.style.color = 'white'; }}
                            onMouseLeave={(e) => { e.currentTarget.style.backgroundColor = 'var(--color-surface-low)'; e.currentTarget.style.color = 'var(--color-on-surface)'; }}
                          >
                            <ChevronRight size={16} />
                          </button>
                        </div>

                        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: '8px', marginBottom: '10px' }}>
                          {['日', '一', '二', '三', '四', '五', '六'].map(d => (
                            <div key={d} style={{ textAlign: 'center', fontSize: '0.75rem', fontWeight: '800', color: 'var(--color-on-surface-variant)' }}>{d}</div>
                          ))}
                        </div>

                        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: '8px', marginBottom: '1.5rem', justifyItems: 'center', alignItems: 'center' }}>
                          {(() => {
                            const firstDay = new Date(currentYear, currentMonth, 1).getDay();
                            const daysNum = new Date(currentYear, currentMonth + 1, 0).getDate();
                            const today = new Date();
                            today.setHours(0, 0, 0, 0);
                            
                            const gridItems = [];
                            
                            for (let i = 0; i < firstDay; i++) {
                              gridItems.push(
                                <div key={`empty-${i}`} style={{ width: '100%', maxWidth: '36px', aspectRatio: '1/1' }} />
                              );
                            }
                            
                            for (let day = 1; day <= daysNum; day++) {
                              const dateObj = new Date(currentYear, currentMonth, day);
                              const dateStr = `${currentYear}-${String(currentMonth + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
                              
                              const isPast = dateObj < today;
                              const isAllowed = !isPast; 
                              
                              const isSelectedHere = schedule.dates.includes(dateStr);
                              const isSelectedElsewhere = !isSelectedHere && allSelectedDates.includes(dateStr);
                              
                              gridItems.push(
                                <button
                                  key={dateStr}
                                  type="button"
                                  disabled={!isAllowed || isSelectedElsewhere}
                                  onClick={() => isAllowed && !isSelectedElsewhere && toggleDateInSchedule(pIdx, sIdx, dateStr)}
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
                                    cursor: (!isAllowed || isSelectedElsewhere) ? 'not-allowed' : 'pointer',
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
                                    boxShadow: isSelectedHere ? '0 4px 12px rgba(118, 86, 0, 0.25)' : 'none',
                                  }}
                                  onMouseEnter={(e) => {
                                    if (isAllowed && !isSelectedHere && !isSelectedElsewhere) {
                                      e.currentTarget.style.backgroundColor = 'var(--color-surface-high)';
                                      e.currentTarget.style.borderColor = 'var(--color-outline-variant)';
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
                              padding: '10px 24px', fontSize: '0.875rem', fontWeight: '800', backgroundColor: 'var(--color-primary)', color: 'var(--color-on-primary)', border: 'none', borderRadius: '100px', cursor: 'pointer', boxShadow: '0 4px 12px rgba(118, 86, 0, 0.25)', transition: 'all 0.2s'
                            }}
                            onMouseEnter={(e) => { e.currentTarget.style.transform = 'translateY(-1px)'; e.currentTarget.style.boxShadow = '0 6px 16px rgba(118, 86, 0, 0.35)'; }}
                            onMouseLeave={(e) => { e.currentTarget.style.transform = 'translateY(0)'; e.currentTarget.style.boxShadow = '0 4px 12px rgba(118, 86, 0, 0.25)'; }}
                            data-testid={`client-booking-btn-confirm-date-${pIdx}-${sIdx}`}
                          >
                            確定日期
                          </button>
                        </div>
                      </div>
                    ) : (
                      <div className="fade-in" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginTop: '0', gap: '16px', paddingTop: '20px', borderTop: '1px solid rgba(0,0,0,0.05)' }}>
                        <div style={{ flex: 1, paddingTop: '0' }}>
                          {!groupedDates ? (
                            <div style={{ color: 'var(--color-text-variant)', fontSize: '0.875rem', marginTop: '0', fontStyle: 'italic', opacity: 0.5 }}>點擊上方按鈕開始規劃行程...</div>
                          ) : (
                            Object.entries(groupedDates).map(([year, datesList]) => (
                              <div key={year}>
                                <div style={{ fontSize: '0.75rem', fontWeight: '800', color: 'var(--color-on-surface-variant)', marginTop: '0', marginBottom: '4px', letterSpacing: '0.05em' }}>{year}年</div>
                                <div style={{ color: 'var(--color-primary)', fontWeight: '700', fontSize: '1rem', lineHeight: '1.4' }}>{datesList.join(', ')}</div>
                              </div>
                            ))
                          )}
                        </div>

                        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: '12px', flexShrink: 0 }}>
                          <button 
                            onClick={() => removeScheduleFromPlan(pIdx, sIdx)}
                            style={{ background: 'none', border: 'none', color: 'var(--color-on-surface-variant)', cursor: 'pointer', padding: '8px', borderRadius: '10px', display: 'flex', alignItems: 'center', justifyContent: 'center', transition: 'all 0.3s', opacity: 0.6 }}
                            onMouseEnter={(e) => { e.currentTarget.style.backgroundColor = 'rgba(186, 26, 26, 0.08)'; e.currentTarget.style.color = 'var(--color-error)'; e.currentTarget.style.opacity = '1'; e.currentTarget.style.transform = 'rotate(8deg)'; }}
                            onMouseLeave={(e) => { e.currentTarget.style.backgroundColor = 'transparent'; e.currentTarget.style.color = 'var(--color-on-surface-variant)'; e.currentTarget.style.opacity = '0.6'; e.currentTarget.style.transform = 'rotate(0)'; }}
                            data-testid={`client-booking-remove-schedule-${pIdx}-${sIdx}`}
                            title="移除此日期排程"
                          >
                            <Trash2 size={20} />
                          </button>
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
          style={{ width: '100%', padding: '20px', background: 'var(--color-surface)', border: '2px dashed var(--color-outline-variant)', color: 'var(--color-primary)', borderRadius: '24px', fontWeight: '800', fontSize: '1rem', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '10px', transition: 'all 0.3s', marginBottom: '2rem' }}
          onMouseEnter={(e) => { e.currentTarget.style.borderColor = 'var(--color-primary)'; e.currentTarget.style.transform = 'translateY(-2px)'; e.currentTarget.style.boxShadow = 'var(--shadow-ambient)'; }}
          onMouseLeave={(e) => { e.currentTarget.style.borderColor = 'var(--color-outline-variant)'; e.currentTarget.style.transform = 'translateY(0)'; e.currentTarget.style.boxShadow = 'none'; }}
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
            style={{ padding: '16px 32px', borderRadius: '100px', fontSize: '1.1rem', fontWeight: '800', boxShadow: '0 8px 24px rgba(118, 86, 0, 0.2)' }}
          >
            最後確認 <ArrowRight size={20} style={{ marginLeft: '8px' }} />
          </Button>
        </div>
      </div>
    );
  };

  const renderStep2 = () => {
    const total = booking.planConfigs.reduce((acc, planConfig) => {
      const plan = plans.find(p => p.id === planConfig.planId);
      const planTotal = planConfig.schedules.reduce((sAcc, schedule) => {
        return sAcc + (plan?.price || 0) * schedule.dates.length * schedule.timesPerDay;
      }, 0);
      return acc + planTotal;
    }, 0);

    return (
      <div className="fade-in">
        <h2 style={{ fontSize: '1.5rem', fontWeight: '800', marginBottom: '0.5rem', fontFamily: 'var(--font-display)', letterSpacing: '-0.025em' }}>預約摘要</h2>
        <p style={{ color: 'var(--color-on-surface-variant)', marginBottom: '2rem', fontSize: '0.875rem' }}>請確認複合方案資訊無誤</p>
        
        <Card style={{ marginBottom: '2rem' }}>
          <div style={{ marginBottom: '1.5rem' }}>
            <label style={{ fontSize: '0.75rem', fontWeight: '700', color: 'var(--color-on-surface-variant)', textTransform: 'uppercase' }}>排程明細</label>
            <div style={{ marginTop: '0.5rem', display: 'flex', flexDirection: 'column', gap: '12px' }}>
              {booking.planConfigs.map((planConfig, pIdx) => {
                const plan = plans.find(p => p.id === planConfig.planId);
                return planConfig.schedules.map((schedule, sIdx) => {
                  const groupedDates = formatDatesGroupedByYear(schedule.dates);
                  return (
                    <div key={`${pIdx}-${sIdx}`} style={{ padding: '12px', backgroundColor: 'var(--color-surface-low)', borderRadius: '8px', borderLeft: '4px solid var(--color-primary)' }}>
                      <div style={{ fontWeight: '800', fontSize: '0.875rem', marginBottom: '4px' }}>{plan?.name} (每天 {schedule.timesPerDay} 次)</div>
                      <div style={{ fontSize: '0.75rem', color: 'var(--color-on-surface-variant)', lineHeight: '1.4' }}>
                        日期：{groupedDates ? Object.entries(groupedDates).map(([y, ds]) => `${y}年 ${ds.join(', ')}`).join(' | ') : '無'}
                      </div>
                      <div style={{ textAlign: 'right', fontWeight: '800', fontSize: '0.875rem', marginTop: '8px', color: 'var(--color-primary)' }}>
                        小計: $ {((plan?.price || 0) * schedule.dates.length * schedule.timesPerDay).toLocaleString()}
                      </div>
                    </div>
                  );
                });
              })}
            </div>
          </div>
          
          <div style={{ 
            backgroundColor: 'var(--color-surface-low)', margin: '1.5rem -1.5rem -1.5rem -1.5rem', padding: '1.5rem', display: 'flex', justifyContent: 'space-between', alignItems: 'center', borderBottomLeftRadius: 'var(--radius-lg)', borderBottomRightRadius: 'var(--radius-lg)'
          }}>
            <span style={{ fontWeight: '800', fontSize: '1.125rem' }}>預計總額</span>
            <span data-testid="client-booking-total" style={{ fontWeight: '800', fontSize: '1.5rem', color: 'var(--color-primary)' }}>$ {total.toLocaleString()}</span>
          </div>
        </Card>

        <textarea 
          placeholder="有什麼特別需要注意的地方嗎？"
          value={booking.notes}
          onChange={(e) => setBooking(prev => ({ ...prev, notes: e.target.value }))}
          data-testid="client-booking-input-notes"
          style={{ width: '100%', height: '100px', padding: '1rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--color-outline-variant)', backgroundColor: 'var(--color-surface-lowest)', fontFamily: 'var(--font-body)', marginBottom: '2rem', resize: 'none', boxSizing: 'border-box' }}
        />

        <div style={{ display: 'flex', gap: '12px' }}>
          <Button variant="secondary" onClick={() => setStep(1)} style={{ flex: 1 }} data-testid="client-booking-btn-step2-back">上一步</Button>
          <Button 
            className="btn-primary" 
            style={{ flex: 2, padding: '12px', fontSize: '1rem' }}
            onClick={() => alert('預約已送出！')}
            data-testid="client-booking-btn-submit"
          >
            確認並送出 <CheckCircle2 size={18} style={{ marginLeft: '8px' }} />
          </Button>
        </div>
      </div>
    );
  };

  return (
    <div style={{ padding: '2rem 1.5rem' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '3rem' }}>
        <div style={{ display: 'flex', gap: '8px' }}>
          {[1, 2].map(s => (
            <div 
              key={s} 
              data-testid={`client-booking-step-indicator-${s}`}
              style={{ 
                width: '48px', height: '4px', 
                backgroundColor: step >= s ? 'var(--color-primary)' : 'var(--color-surface-high)',
                borderRadius: '2px',
                transition: 'all 0.3s ease'
              }} 
            />
          ))}
        </div>
        <span style={{ fontSize: '0.75rem', fontWeight: '800', color: 'var(--color-on-surface-variant)', letterSpacing: '0.05em' }}>STEP {step} / 2</span>
      </div>

      {step === 1 && renderStep1()}
      {step === 2 && renderStep2()}
    </div>
  );
};

export default PublicBookingPage;
