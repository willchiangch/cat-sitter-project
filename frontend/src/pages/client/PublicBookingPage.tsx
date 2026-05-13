import React, { useState } from 'react';
import Card from '../../components/ui/Card';
import Button from '../../components/ui/Button';
import { Dog, CheckCircle2, ArrowRight, Plus, Trash2 } from 'lucide-react';
import type { BookingState, Plan, Pet, BookingItem } from '../../types/booking';

const MOCK_PLANS: Plan[] = [
  { id: 'p1', name: '基礎照顧', price: 500, description: '包含換水、餵食、清砂盆、拍照記錄' },
  { id: 'p2', name: '進階陪伴', price: 800, description: '包含基礎照顧 + 陪玩 20 分鐘、梳毛' }
];

const MOCK_PETS: Pet[] = [
  { id: 'pet1', name: '咪咪', type: 'Cat' },
  { id: 'pet2', name: '小黑', type: 'Cat' }
];

const PublicBookingPage: React.FC = () => {
  const [step, setStep] = useState(1);
  const [selectedDatesPool, setSelectedDatesPool] = useState<string[]>([]);
  const [booking, setBooking] = useState<BookingState>({
    sitterId: 'sitter-123',
    items: [],
    selectedPetIds: [],
    notes: '',
    totalAmount: 0
  });

  const toggleDateInPool = (date: string) => {
    setSelectedDatesPool(prev => 
      prev.includes(date) ? prev.filter(d => d !== date) : [...prev, date].sort()
    );
  };

  const addItem = () => {
    setBooking(prev => ({
      ...prev,
      items: [...prev.items, { planId: MOCK_PLANS[0].id, dates: [...selectedDatesPool], timesPerDay: 1 }]
    }));
  };

  const removeItem = (index: number) => {
    setBooking(prev => ({
      ...prev,
      items: prev.items.filter((_, i) => i !== index)
    }));
  };

  const updateItem = (index: number, field: keyof BookingItem, value: any) => {
    setBooking(prev => ({
      ...prev,
      items: prev.items.map((item, i) => i === index ? { ...item, [field]: value } : item)
    }));
  };

  const toggleDateInItem = (itemIndex: number, date: string) => {
    const item = booking.items[itemIndex];
    const newDates = item.dates.includes(date) 
      ? item.dates.filter(d => d !== date) 
      : [...item.dates, date].sort();
    updateItem(itemIndex, 'dates', newDates);
  };

  const handleNextToStep2 = () => {
    if (booking.items.length === 0) {
      setBooking(prev => ({
        ...prev,
        items: [{ planId: MOCK_PLANS[0].id, dates: [...selectedDatesPool], timesPerDay: 1 }]
      }));
    }
    setStep(2);
  };

  const renderStep1 = () => {
    const days = Array.from({ length: 30 }, (_, i) => {
      const d = new Date();
      d.setDate(d.getDate() + i);
      return d.toISOString().split('T')[0];
    });

    return (
      <div className="fade-in">
        <h2 style={{ fontSize: '1.5rem', fontWeight: '700', marginBottom: '0.5rem', fontFamily: 'var(--font-display)' }}>選擇服務日期</h2>
        <p style={{ color: 'var(--color-on-surface-variant)', marginBottom: '2rem', fontSize: '0.875rem' }}>請點選保母前往照顧的日期範疇</p>
        
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: '8px', marginBottom: '2rem' }}>
          {['日', '一', '二', '三', '四', '五', '六'].map(d => (
            <div key={d} style={{ textAlign: 'center', fontSize: '0.75rem', fontWeight: '600', color: 'var(--color-on-surface-variant)' }}>{d}</div>
          ))}
          {days.map(date => {
            const dayNum = new Date(date).getDate();
            const isSelected = selectedDatesPool.includes(date);
            return (
              <div 
                key={date}
                onClick={() => toggleDateInPool(date)}
                data-testid={`client-booking-date-${date}`}
                style={{
                  aspectRatio: '1/1',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  borderRadius: '12px',
                  cursor: 'pointer',
                  fontSize: '0.875rem',
                  fontWeight: isSelected ? '700' : '500',
                  backgroundColor: isSelected ? 'var(--color-primary)' : 'var(--color-surface-low)',
                  color: isSelected ? 'var(--color-on-primary)' : 'var(--color-on-surface)',
                  transition: 'all 0.2s ease',
                  boxShadow: isSelected ? 'var(--shadow-ambient)' : 'none'
                }}
              >
                {dayNum}
              </div>
            );
          })}
        </div>
        
        <Button 
          className="btn-primary" 
          fullWidth 
          disabled={selectedDatesPool.length === 0}
          onClick={handleNextToStep2}
          data-testid="client-booking-btn-step1-next"
        >
          下一步：配置方案排程 <ArrowRight size={18} />
        </Button>
      </div>
    );
  };

  const renderStep2 = () => {
    return (
      <div className="fade-in">
        <h2 style={{ fontSize: '1.5rem', fontWeight: '700', marginBottom: '0.5rem', fontFamily: 'var(--font-display)' }}>預約方案配置</h2>
        <p style={{ color: 'var(--color-on-surface-variant)', marginBottom: '2rem', fontSize: '0.875rem' }}>您可以為不同日期組合設定不同的方案與次數</p>
        
        <div style={{ display: 'flex', flexDirection: 'column', gap: '20px', marginBottom: '2rem' }}>
          {booking.items.map((item, idx) => (
             <Card key={idx} style={{ padding: '1.5rem', backgroundColor: 'var(--color-surface-lowest)', position: 'relative' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
                  <span style={{ fontWeight: '700', color: 'var(--color-primary)' }}>預約項目 #{idx + 1}</span>
                  {booking.items.length > 1 && (
                    <button 
                      onClick={() => removeItem(idx)} 
                      style={{ color: 'var(--color-error)', border: 'none', background: 'none', cursor: 'pointer' }}
                      data-testid={`client-booking-item-remove-${idx}`}
                    >
                      <Trash2 size={18} />
                    </button>
                  )}
                </div>

                <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                   <div>
                     <label style={{ fontSize: '0.75rem', fontWeight: '700', display: 'block', marginBottom: '4px' }}>適用日期</label>
                     <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px' }}>
                        {selectedDatesPool.map(date => {
                          const isSelectedInItem = item.dates.includes(date);
                          return (
                            <div 
                              key={date}
                              onClick={() => toggleDateInItem(idx, date)}
                              data-testid={`client-booking-item-${idx}-date-${date}`}
                              style={{
                                padding: '4px 8px',
                                borderRadius: '4px',
                                fontSize: '0.75rem',
                                cursor: 'pointer',
                                backgroundColor: isSelectedInItem ? 'var(--color-primary-container)' : 'var(--color-surface-low)',
                                color: isSelectedInItem ? 'var(--color-on-primary-container)' : 'var(--color-on-surface-variant)',
                                border: 'none',
                                boxShadow: isSelectedInItem ? 'inset 0 0 0 1px var(--color-primary)' : 'none'
                              }}
                            >
                              {date.split('-')[2]}日
                            </div>
                          );
                        })}
                     </div>
                   </div>

                   <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
                     <div>
                       <label style={{ fontSize: '0.75rem', fontWeight: '700', display: 'block', marginBottom: '4px' }}>選擇方案</label>
                       <select 
                         value={item.planId} 
                         onChange={(e) => updateItem(idx, 'planId', e.target.value)}
                         style={{ width: '100%', padding: '10px', borderRadius: '8px', border: 'none', backgroundColor: 'var(--color-surface-low)', color: 'var(--color-on-surface)' }}
                         data-testid={`client-booking-item-${idx}-plan-select`}
                       >
                         {MOCK_PLANS.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
                       </select>
                     </div>
                     <div>
                       <label style={{ fontSize: '0.75rem', fontWeight: '700', display: 'block', marginBottom: '4px' }}>每日趟次</label>
                       <select 
                         value={item.timesPerDay} 
                         onChange={(e) => updateItem(idx, 'timesPerDay', parseInt(e.target.value))}
                         style={{ width: '100%', padding: '10px', borderRadius: '8px', border: 'none', backgroundColor: 'var(--color-surface-low)', color: 'var(--color-on-surface)' }}
                         data-testid={`client-booking-item-${idx}-times-select`}
                       >
                         {[1, 2, 3].map(t => <option key={t} value={t}>{t} 次</option>)}
                       </select>
                     </div>
                   </div>
                </div>
             </Card>
          ))}

          <Button 
            variant="secondary" 
            onClick={addItem} 
            fullWidth 
            style={{ borderStyle: 'dashed' }}
            data-testid="client-booking-btn-add-item"
          >
            <Plus size={18} /> 新增預約項目
          </Button>
        </div>

        <h3 style={{ fontSize: '1rem', fontWeight: '700', marginBottom: '1rem', fontFamily: 'var(--font-display)' }}>選擇參與毛孩</h3>
        <div style={{ display: 'flex', gap: '12px', marginBottom: '2rem' }}>
          {MOCK_PETS.map(pet => {
            const isSelected = booking.selectedPetIds.includes(pet.id);
            return (
              <div 
                key={pet.id}
                onClick={() => setBooking(prev => ({
                  ...prev,
                  selectedPetIds: isSelected ? prev.selectedPetIds.filter(id => id !== pet.id) : [...prev.selectedPetIds, pet.id]
                }))}
                data-testid={`client-booking-pet-${pet.id}`}
                style={{
                  padding: '12px 20px',
                  borderRadius: '9999px',
                  border: isSelected ? '2px solid var(--color-primary)' : 'none',
                  backgroundColor: isSelected ? 'var(--color-surface-lowest)' : 'var(--color-surface-low)',
                  cursor: 'pointer',
                  fontWeight: '600',
                  fontSize: '0.875rem',
                  color: isSelected ? 'var(--color-primary)' : 'var(--color-on-surface)',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '8px'
                }}
              >
                <Dog size={16} /> {pet.name}
              </div>
            );
          })}
        </div>

        <div style={{ display: 'flex', gap: '12px' }}>
          <Button variant="secondary" onClick={() => setStep(1)} style={{ flex: 1 }} data-testid="client-booking-btn-step2-back">上一步</Button>
          <Button 
            className="btn-primary" 
            style={{ flex: 2 }}
            disabled={booking.selectedPetIds.length === 0 || booking.items.some(it => it.dates.length === 0)}
            onClick={() => setStep(3)}
            data-testid="client-booking-btn-step2-next"
          >
            最後確認 <ArrowRight size={18} />
          </Button>
        </div>
      </div>
    );
  };

  const renderStep3 = () => {
    const total = booking.items.reduce((acc, item) => {
      const plan = MOCK_PLANS.find(p => p.id === item.planId);
      return acc + (plan?.price || 0) * item.dates.length * item.timesPerDay;
    }, 0);

    return (
      <div className="fade-in">
        <h2 style={{ fontSize: '1.5rem', fontWeight: '700', marginBottom: '0.5rem', fontFamily: 'var(--font-display)' }}>預約摘要</h2>
        <p style={{ color: 'var(--color-on-surface-variant)', marginBottom: '2rem', fontSize: '0.875rem' }}>請確認複合方案資訊無誤</p>
        
        <Card style={{ marginBottom: '2rem' }}>
          <div style={{ marginBottom: '1.5rem' }}>
            <label style={{ fontSize: '0.75rem', fontWeight: '700', color: 'var(--color-on-surface-variant)', textTransform: 'uppercase' }}>排程明細</label>
            <div style={{ marginTop: '0.5rem', display: 'flex', flexDirection: 'column', gap: '12px' }}>
              {booking.items.map((item, idx) => {
                const plan = MOCK_PLANS.find(p => p.id === item.planId);
                return (
                  <div key={idx} style={{ padding: '10px', backgroundColor: 'var(--color-surface-low)', borderRadius: '8px', borderLeft: '4px solid var(--color-primary)' }}>
                    <div style={{ fontWeight: '700', fontSize: '0.875rem' }}>{plan?.name} (每天 {item.timesPerDay} 趟)</div>
                    <div style={{ fontSize: '0.75rem', color: 'var(--color-on-surface-variant)' }}>
                      日期：{item.dates.join(', ')}
                    </div>
                    <div style={{ textAlign: 'right', fontWeight: '700', fontSize: '0.75rem', marginTop: '4px' }}>
                      小計: $ {(plan?.price || 0) * item.dates.length * item.timesPerDay}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
          
          <div style={{ marginBottom: '1.5rem' }}>
            <label style={{ fontSize: '0.75rem', fontWeight: '700', color: 'var(--color-on-surface-variant)', textTransform: 'uppercase' }}>參與毛孩</label>
            <p style={{ margin: '0.25rem 0', fontWeight: '600' }}>
              {booking.selectedPetIds.map(id => MOCK_PETS.find(p => p.id === id)?.name).join(', ')}
            </p>
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
          <Button variant="secondary" onClick={() => setStep(2)} style={{ flex: 1 }} data-testid="client-booking-btn-step3-back">上一步</Button>
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
          {[1, 2, 3].map(s => (
            <div 
              key={s} 
              data-testid={`client-booking-step-indicator-${s}`}
              style={{ 
                width: '32px', height: '4px', 
                backgroundColor: step >= s ? 'var(--color-primary)' : 'var(--color-surface-high)',
                borderRadius: '2px',
                transition: 'all 0.3s ease'
              }} 
            />
          ))}
        </div>
        <span style={{ fontSize: '0.75rem', fontWeight: '700', color: 'var(--color-on-surface-variant)' }}>STEP {step} / 3</span>
      </div>

      {step === 1 && renderStep1()}
      {step === 2 && renderStep2()}
      {step === 3 && renderStep3()}
    </div>
  );
};

export default PublicBookingPage;
