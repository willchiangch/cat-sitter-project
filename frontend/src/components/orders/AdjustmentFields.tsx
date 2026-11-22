import React from 'react';
import type { OrderDetail } from '../../api/orderApi';
import { CountedTextArea } from './common';

/** 保母加減欄（E17 SITTER / E20）：FREE / BASIC 或鎖定（SYSTEM_ONLY）時停用（PRD-016 EX-5） */
const AdjustmentFields: React.FC<{
  detail: OrderDetail;
  surcharge: string;
  discount: string;
  reason: string;
  onChange: (patch: { surcharge?: string; discount?: string; reason?: string }) => void;
}> = ({ detail, surcharge, discount, reason, onChange }) => {
  const custom = detail.modificationPricingMode === 'CUSTOM_ALLOWED';
  return (
    <div className="stack">
      {!custom && (
        <p className="muted" style={{ margin: 0 }}>
          {detail.sitterTier === 'PRO' || detail.sitterTier === 'ULTIMATE'
            ? '訂閱已逾期，變更金額依系統計算'
            : '自訂變更金額需專業版以上'}
        </p>
      )}
      <div className="btn-row">
        <div style={{ flex: '1 1 7rem', minWidth: 0 }}>
          <label className="field-label" htmlFor="mod-surcharge">
            加價
          </label>
          <input
            id="mod-surcharge"
            className="form-input"
            inputMode="numeric"
            disabled={!custom}
            value={surcharge}
            onChange={(e) => onChange({ surcharge: e.target.value.replace(/\D/g, '').slice(0, 6) })}
            data-testid="modification-input-surcharge"
          />
        </div>
        <div style={{ flex: '1 1 7rem', minWidth: 0 }}>
          <label className="field-label" htmlFor="mod-discount">
            折扣
          </label>
          <input
            id="mod-discount"
            className="form-input"
            inputMode="numeric"
            disabled={!custom}
            value={discount}
            onChange={(e) => onChange({ discount: e.target.value.replace(/\D/g, '').slice(0, 6) })}
            data-testid="modification-input-discount"
          />
        </div>
      </div>
      <CountedTextArea
        id="mod-adjust-reason"
        label="調整原因"
        value={reason}
        onChange={(v) => onChange({ reason: v })}
        max={200}
        disabled={!custom}
        testId="modification-input-adjustment-reason"
      />
    </div>
  );
};

export default AdjustmentFields;
