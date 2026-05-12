import React, { useState } from 'react';
import Button from '../../components/ui/Button';
import Card from '../../components/ui/Card';
import { ChevronLeft, ClipboardCheck, PlusCircle, MinusCircle } from 'lucide-react';

const OrderEvalView: React.FC = () => {
  const baseFee = 2400;
  const [addFee, setAddFee] = useState(0);
  const [discountFee, setDiscountFee] = useState(0);

  const finalTotal = baseFee + addFee - discountFee;

  return (
    <div style={{ padding: '2rem 1.5rem' }}>
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: '2.5rem' }}>
        <button style={{ border: 'none', background: 'var(--color-surface-low)', color: 'var(--color-on-surface)', borderRadius: '50%', width: '40px', height: '40px', display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer', marginRight: '1rem' }}>
          <ChevronLeft size={24} />
        </button>
        <h2 style={{ fontSize: '1.5rem', fontWeight: '700', color: 'var(--color-on-surface)', fontFamily: 'var(--font-display)', margin: 0 }}>
          新訂單評估 <span style={{ fontSize: '1rem', fontWeight: '500', color: 'var(--color-on-surface-variant)', marginLeft: '0.5rem' }}>#A1023</span>
        </h2>
      </div>

      <Card style={{ marginBottom: '2rem' }}>
        <div style={{ display: 'flex', alignItems: 'center', marginBottom: '2rem' }}>
          <div style={{ 
            width: '48px', height: '48px', backgroundColor: 'var(--color-surface-high)', borderRadius: '50%', 
            display: 'flex', alignItems: 'center', justifyContent: 'center', 
            fontWeight: '700', color: 'var(--color-on-surface)', marginRight: '1rem',
            fontFamily: 'var(--font-display)', fontSize: '1.25rem'
          }}>
            陳
          </div>
          <div>
            <h3 style={{ margin: 0, fontSize: '1.125rem', fontWeight: '700', fontFamily: 'var(--font-display)' }}>陳先生</h3>
            <p style={{ margin: '0.25rem 0 0', fontSize: '0.875rem', color: 'var(--color-on-surface-variant)' }}>距離約 2.5 公里</p>
          </div>
        </div>

        <div style={{ 
          backgroundColor: 'var(--color-surface-low)', padding: '1.25rem', borderRadius: 'var(--radius-sm)', 
          marginBottom: '2rem', display: 'flex', 
          justifyContent: 'space-between', alignItems: 'center' 
        }}>
          <div>
            <p style={{ margin: 0, fontSize: '0.875rem', fontWeight: '600', color: 'var(--color-primary)', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <ClipboardCheck size={18} /> 事前問卷已回收
            </p>
          </div>
          <button style={{ border: 'none', background: 'none', color: 'var(--color-primary)', fontWeight: '700', fontSize: '0.875rem', cursor: 'pointer', textDecoration: 'underline' }}>
            查看問卷
          </button>
        </div>

        <div style={{ padding: '1.5rem', backgroundColor: 'var(--color-surface-low)', borderRadius: 'var(--radius-sm)' }}>
          <h4 style={{ margin: '0 0 1.5rem', fontSize: '1rem', fontWeight: '700', fontFamily: 'var(--font-display)', color: 'var(--color-on-surface)' }}>💰 報價微調</h4>
          
          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.875rem', marginBottom: '1rem', color: 'var(--color-on-surface-variant)', fontWeight: '500' }}>
            <span>基礎服務費 (5 趟)</span>
            <span>$ {baseFee.toLocaleString()}</span>
          </div>

          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.875rem', marginBottom: '1rem', color: 'var(--color-primary)', alignItems: 'center', fontWeight: '600' }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}><PlusCircle size={16} /> 加價</span>
            <div style={{ display: 'flex', alignItems: 'center' }}>
              <span style={{ marginRight: '0.5rem' }}>+ $</span>
              <input 
                type="number" 
                value={addFee} 
                onChange={(e) => setAddFee(Number(e.target.value))}
                style={{ width: '80px', border: '1px solid var(--color-outline-variant)', borderRadius: 'var(--radius-sm)', padding: '0.5rem', textAlign: 'right', background: 'var(--color-surface-lowest)', fontWeight: '700' }}
                data-testid="sitter-order-eval-input-add-fee"
              />
            </div>
          </div>

          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.875rem', marginBottom: '1.5rem', color: 'var(--color-error)', alignItems: 'center', fontWeight: '600' }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}><MinusCircle size={16} /> 折扣</span>
            <div style={{ display: 'flex', alignItems: 'center' }}>
              <span style={{ marginRight: '0.5rem' }}>- $</span>
              <input 
                type="number" 
                value={discountFee} 
                onChange={(e) => setDiscountFee(Number(e.target.value))}
                style={{ width: '80px', border: '1px solid var(--color-outline-variant)', borderRadius: 'var(--radius-sm)', padding: '0.5rem', textAlign: 'right', background: 'var(--color-surface-lowest)', fontWeight: '700' }}
                data-testid="sitter-order-eval-input-discount"
              />
            </div>
          </div>

          <div style={{ display: 'flex', justifyContent: 'space-between', fontWeight: '700', fontSize: '1.5rem', marginTop: '1.5rem', paddingTop: '1.5rem', borderTop: '2px solid var(--color-surface-high)', color: 'var(--color-on-surface)', fontFamily: 'var(--font-display)' }}>
            <span>報價總計</span>
            <span>$ {finalTotal.toLocaleString()}</span>
          </div>
        </div>
      </Card>

      <div style={{ display: 'flex', gap: '1rem' }}>
        <Button variant="danger-outline" style={{ flex: 1, borderRadius: '9999px', padding: '1rem' }} data-testid="sitter-order-eval-btn-reject">拒絕接單</Button>
        <Button className="btn-primary" style={{ flex: 2, padding: '1rem' }} data-testid="sitter-order-eval-btn-confirm">發送報價</Button>
      </div>
    </div>
  );
};

export default OrderEvalView;
