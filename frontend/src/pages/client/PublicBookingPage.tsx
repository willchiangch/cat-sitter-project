import React, { useState } from 'react';
import Card from '../../components/ui/Card';
import Button from '../../components/ui/Button';
import { CheckCircle2, ArrowRight, Plus, Trash2 } from 'lucide-react';
import type { BookingState, Plan } from '../../types/booking';

const MOCK_PLANS: Plan[] = [
  { id: 'p1', name: '基礎照顧', price: 500, description: '包含換水、餵食、清砂盆、拍照記錄' },
  { id: 'p2', name: '進階陪伴', price: 800, description: '包含基礎照顧 + 陪玩 20 分鐘、梳毛' }
];

const PublicBookingPage: React.FC = () => {
  const [step, setStep] = useState(1);
  const [booking, setBooking] = useState<BookingState>({
    sitterId: 'sitter-123',
    planConfigs: [],
    notes: '',
    totalAmount: 0
  });
  
  // 當還沒有選任何方案，或使用者主動點擊「選擇其他方案」時為 true
  const [isAddingPlan, setIsAddingPlan] = useState(true);

  const allSelectedDates = booking.planConfigs.flatMap(p => p.schedules.flatMap(s => s.dates));

  const addPlanConfig = (planId: string) => {
    setBooking(prev => ({
      ...prev,
      planConfigs: [...prev.planConfigs, { planId, schedules: [{ dates: [], timesPerDay: 1 }] }]
    }));
    setIsAddingPlan(false);
  };

  const addScheduleToPlan = (planIndex: number) => {
    setBooking(prev => {
      const newConfigs = [...prev.planConfigs];
      const plan = { ...newConfigs[planIndex] };
      plan.schedules = [...plan.schedules, { dates: [], timesPerDay: 1 }];
      newConfigs[planIndex] = plan;
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
    const days = Array.from({ length: 30 }, (_, i) => {
      const d = new Date();
      d.setDate(d.getDate() + i);
      return d.toISOString().split('T')[0];
    });

    const isNextDisabled = booking.planConfigs.length === 0 || 
                           booking.planConfigs.some(pc => pc.schedules.some(s => s.dates.length === 0));

    return (
      <div className="fade-in">
        <h2 style={{ fontSize: '1.5rem', fontWeight: '700', marginBottom: '0.5rem', fontFamily: 'var(--font-display)' }}>排程配置</h2>
        <p style={{ color: 'var(--color-on-surface-variant)', marginBottom: '2rem', fontSize: '0.875rem' }}>請先選擇服務方案，再點選需要的日期與每日趟次</p>

        {/* 已經選擇的方案清單 */}
        {booking.planConfigs.map((planConfig, pIdx) => {
          const plan = MOCK_PLANS.find(p => p.id === planConfig.planId);
          return (
            <Card key={planConfig.planId} style={{ marginBottom: '2rem', padding: '1.5rem', backgroundColor: 'var(--color-surface-lowest)' }}>
              <h3 style={{ fontSize: '1.25rem', fontWeight: '700', marginBottom: '1.5rem', color: 'var(--color-primary)', display: 'flex', alignItems: 'center', gap: '8px' }}>
                <span style={{ width: '8px', height: '24px', backgroundColor: 'var(--color-primary)', borderRadius: '4px' }}></span>
                {plan?.name}
              </h3>
              
              {planConfig.schedules.map((schedule, sIdx) => (
                <div key={sIdx} style={{ marginBottom: '1.5rem', paddingBottom: '1.5rem', borderBottom: sIdx < planConfig.schedules.length - 1 ? '1px dashed var(--color-outline-variant)' : 'none' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                    <span style={{ fontWeight: '700', fontSize: '0.875rem', color: 'var(--color-on-surface-variant)' }}>排程 #{sIdx + 1}</span>
                    <button 
                      onClick={() => removeScheduleFromPlan(pIdx, sIdx)} 
                      style={{ color: 'var(--color-error)', border: 'none', background: 'none', cursor: 'pointer', padding: '4px' }}
                      data-testid={`client-booking-remove-schedule-${pIdx}-${sIdx}`}
                    >
                      <Trash2 size={18} />
                    </button>
                  </div>

                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: '8px', marginBottom: '1.5rem' }}>
                    {['日', '一', '二', '三', '四', '五', '六'].map(d => (
                      <div key={d} style={{ textAlign: 'center', fontSize: '0.75rem', fontWeight: '600', color: 'var(--color-on-surface-variant)' }}>{d}</div>
                    ))}
                    {days.map(date => {
                      const dayNum = new Date(date).getDate();
                      const isSelectedHere = schedule.dates.includes(date);
                      const isSelectedElsewhere = !isSelectedHere && allSelectedDates.includes(date);
                      
                      return (
                        <div 
                          key={date}
                          onClick={() => !isSelectedElsewhere && toggleDateInSchedule(pIdx, sIdx, date)}
                          data-testid={`client-booking-date-${pIdx}-${sIdx}-${date}`}
                          style={{
                            aspectRatio: '1/1',
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            borderRadius: '12px',
                            cursor: isSelectedElsewhere ? 'not-allowed' : 'pointer',
                            fontSize: '0.875rem',
                            fontWeight: isSelectedHere ? '700' : '500',
                            backgroundColor: isSelectedHere ? 'var(--color-primary)' : isSelectedElsewhere ? 'var(--color-surface-lowest)' : 'var(--color-surface-low)',
                            color: isSelectedHere ? 'var(--color-on-primary)' : isSelectedElsewhere ? 'var(--color-outline-variant)' : 'var(--color-on-surface)',
                            transition: 'all 0.2s ease',
                            boxShadow: isSelectedHere ? 'var(--shadow-ambient)' : 'none',
                            textDecoration: isSelectedElsewhere ? 'line-through' : 'none'
                          }}
                        >
                          {dayNum}
                        </div>
                      );
                    })}
                  </div>

                  <div>
                    <label style={{ fontSize: '0.75rem', fontWeight: '700', display: 'block', marginBottom: '6px' }}>每日趟次</label>
                    <select 
                      value={schedule.timesPerDay} 
                      onChange={(e) => updateTimesPerDay(pIdx, sIdx, parseInt(e.target.value))}
                      style={{ width: '100%', padding: '12px', borderRadius: '8px', border: 'none', backgroundColor: 'var(--color-surface-low)', color: 'var(--color-on-surface)' }}
                      data-testid={`client-booking-times-${pIdx}-${sIdx}`}
                    >
                      {[1, 2, 3].map(t => <option key={t} value={t}>{t} 次</option>)}
                    </select>
                  </div>
                </div>
              ))}

              <Button 
                variant="secondary" 
                onClick={() => addScheduleToPlan(pIdx)} 
                fullWidth 
                style={{ borderStyle: 'dashed' }}
                data-testid={`client-booking-btn-add-schedule-${pIdx}`}
              >
                <Plus size={18} /> 新增其他日期 (同方案)
              </Button>
            </Card>
          );
        })}

        {/* 可供選擇的方案卡片區塊 */}
        {isAddingPlan && (
          <div style={{ marginBottom: '2rem' }}>
            <h3 style={{ fontSize: '1rem', fontWeight: '700', marginBottom: '1rem', fontFamily: 'var(--font-display)' }}>選擇服務方案</h3>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
              {MOCK_PLANS.filter(p => !booking.planConfigs.some(pc => pc.planId === p.id)).map(plan => (
                <Card 
                  key={plan.id} 
                  onClick={() => addPlanConfig(plan.id)}
                  style={{ cursor: 'pointer', padding: '1.5rem', backgroundColor: 'var(--color-surface-low)', transition: 'all 0.2s', border: 'none' }}
                  data-testid={`client-booking-plan-card-${plan.id}`}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '0.5rem' }}>
                    <h4 style={{ fontWeight: '700', margin: 0 }}>{plan.name}</h4>
                    <span style={{ color: 'var(--color-primary)', fontWeight: '700' }}>$ {plan.price} / 趟</span>
                  </div>
                  <p style={{ fontSize: '0.875rem', color: 'var(--color-on-surface-variant)', margin: 0 }}>{plan.description}</p>
                </Card>
              ))}
            </div>
          </div>
        )}

        {!isAddingPlan && booking.planConfigs.length < MOCK_PLANS.length && (
          <div style={{ marginBottom: '2rem' }}>
            <Button variant="secondary" fullWidth onClick={() => setIsAddingPlan(true)} data-testid="client-booking-btn-add-plan">
              <Plus size={18} /> 選擇其他方案
            </Button>
          </div>
        )}

        <Button 
          className="btn-primary" 
          fullWidth 
          disabled={isNextDisabled}
          onClick={handleNextToStep2}
          data-testid="client-booking-btn-step1-next"
        >
          最後確認 <ArrowRight size={18} />
        </Button>
      </div>
    );
  };

  const renderStep2 = () => {
    // 計算總額
    const total = booking.planConfigs.reduce((acc, planConfig) => {
      const plan = MOCK_PLANS.find(p => p.id === planConfig.planId);
      const planTotal = planConfig.schedules.reduce((sAcc, schedule) => {
        return sAcc + (plan?.price || 0) * schedule.dates.length * schedule.timesPerDay;
      }, 0);
      return acc + planTotal;
    }, 0);

    return (
      <div className="fade-in">
        <h2 style={{ fontSize: '1.5rem', fontWeight: '700', marginBottom: '0.5rem', fontFamily: 'var(--font-display)' }}>預約摘要</h2>
        <p style={{ color: 'var(--color-on-surface-variant)', marginBottom: '2rem', fontSize: '0.875rem' }}>請確認複合方案資訊無誤</p>
        
        <Card style={{ marginBottom: '2rem' }}>
          <div style={{ marginBottom: '1.5rem' }}>
            <label style={{ fontSize: '0.75rem', fontWeight: '700', color: 'var(--color-on-surface-variant)', textTransform: 'uppercase' }}>排程明細</label>
            <div style={{ marginTop: '0.5rem', display: 'flex', flexDirection: 'column', gap: '12px' }}>
              {booking.planConfigs.map((planConfig, pIdx) => {
                const plan = MOCK_PLANS.find(p => p.id === planConfig.planId);
                return planConfig.schedules.map((schedule, sIdx) => (
                  <div key={`${pIdx}-${sIdx}`} style={{ padding: '12px', backgroundColor: 'var(--color-surface-low)', borderRadius: '8px', borderLeft: '4px solid var(--color-primary)' }}>
                    <div style={{ fontWeight: '700', fontSize: '0.875rem', marginBottom: '4px' }}>{plan?.name} (每天 {schedule.timesPerDay} 趟)</div>
                    <div style={{ fontSize: '0.75rem', color: 'var(--color-on-surface-variant)', lineHeight: '1.4' }}>
                      日期：{schedule.dates.join(', ')}
                    </div>
                    <div style={{ textAlign: 'right', fontWeight: '700', fontSize: '0.875rem', marginTop: '8px' }}>
                      小計: $ {(plan?.price || 0) * schedule.dates.length * schedule.timesPerDay}
                    </div>
                  </div>
                ));
              })}
            </div>
          </div>
          
          <div style={{ 
            backgroundColor: 'var(--color-surface-low)',
            margin: '1.5rem -1.5rem -1.5rem -1.5rem',
            padding: '1.5rem',
            display: 'flex', 
            justifyContent: 'space-between', 
            alignItems: 'center',
            borderBottomLeftRadius: 'var(--radius-lg)',
            borderBottomRightRadius: 'var(--radius-lg)'
          }}>
            <span style={{ fontWeight: '700', fontSize: '1.125rem' }}>預計總額</span>
            <span data-testid="client-booking-total" style={{ fontWeight: '700', fontSize: '1.5rem', color: 'var(--color-primary)' }}>$ {total.toLocaleString()}</span>
          </div>
        </Card>

        <textarea 
          placeholder="有什麼特別需要注意的地方嗎？"
          value={booking.notes}
          onChange={(e) => setBooking(prev => ({ ...prev, notes: e.target.value }))}
          data-testid="client-booking-input-notes"
          style={{
            width: '100%',
            height: '100px',
            padding: '1rem',
            borderRadius: 'var(--radius-sm)',
            border: 'none',
            backgroundColor: 'var(--color-surface-low)',
            fontFamily: 'var(--font-body)',
            marginBottom: '2rem',
            resize: 'none',
            boxSizing: 'border-box'
          }}
        />

        <div style={{ display: 'flex', gap: '12px' }}>
          <Button variant="secondary" onClick={() => setStep(1)} style={{ flex: 1 }} data-testid="client-booking-btn-step2-back">上一步</Button>
          <Button 
            className="btn-primary" 
            style={{ flex: 2 }}
            onClick={() => alert('預約已送出！')}
            data-testid="client-booking-btn-submit"
          >
            確認並送出預約 <CheckCircle2 size={18} />
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
        <span style={{ fontSize: '0.75rem', fontWeight: '700', color: 'var(--color-on-surface-variant)' }}>STEP {step} / 2</span>
      </div>

      {step === 1 && renderStep1()}
      {step === 2 && renderStep2()}
    </div>
  );
};

export default PublicBookingPage;
