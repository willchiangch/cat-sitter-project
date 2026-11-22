import React, { useState } from 'react';
import Button from '../ui/Button';
import { SectionCard } from '../orders/common';
import {
  addChecklistItem,
  checkChecklistItem,
  deleteChecklistItem,
  reorderChecklist
} from '../../api/fulfillApi';
import type { ChecklistItem, VisitDetail } from '../../api/fulfillApi';
import { getApiError } from '../../api/apiError';
import { errorMessage } from '../../api/errorMessages';
import { useIdempotentAction } from '../../hooks/useIdempotentAction';
import { formatDateTime } from '../../utils/datetime';
import { codePointLength, stripText, truncateCodePoints } from '../../utils/text';

const MAX_ITEMS = 20;

/**
 * SOP 檢核項（PRD-028、SD-FULFILL F8–F12）：新增（冪等）/ 刪除 / 排序（拖曳或上下鈕，整值覆寫）/ 打勾。
 * 編輯依 F2 allowedActions `EDIT_CHECKLIST`，打勾依 `CHECK_ITEM`。
 */
const ChecklistSection: React.FC<{ visit: VisitDetail; onChanged: () => void }> = ({
  visit,
  onChanged
}) => {
  const items = [...(visit.checklist?.items ?? [])].sort((a, b) => a.sortOrder - b.sortOrder);
  const canEdit = visit.allowedActions.includes('EDIT_CHECKLIST');
  const canCheck = visit.allowedActions.includes('CHECK_ITEM');
  const [title, setTitle] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [dragId, setDragId] = useState<string | null>(null);
  const add = useIdempotentAction();

  const run = async (fn: () => Promise<unknown>) => {
    setError(null);
    setBusy(true);
    try {
      await fn();
      onChanged();
    } catch (e) {
      setError(errorMessage(getApiError(e), 'checklist'));
    } finally {
      setBusy(false);
    }
  };

  const reorder = (ids: string[]) => run(() => reorderChecklist(visit.orderId, visit.visitId, ids));
  const move = (idx: number, delta: number) => {
    const ids = items.map((i) => i.itemId);
    const j = idx + delta;
    if (j < 0 || j >= ids.length) return;
    [ids[idx], ids[j]] = [ids[j], ids[idx]];
    reorder(ids);
  };
  const dropOn = (target: ChecklistItem) => {
    if (!dragId || dragId === target.itemId) return;
    const ids = items.map((i) => i.itemId).filter((id) => id !== dragId);
    ids.splice(ids.indexOf(target.itemId), 0, dragId);
    setDragId(null);
    reorder(ids);
  };

  const titleLen = codePointLength(stripText(title));
  const submitAdd = async () => {
    const res = await add.run((key) =>
      addChecklistItem(visit.orderId, visit.visitId, stripText(title), key)
    );
    if (res) {
      setTitle('');
      onChanged();
    }
  };

  return (
    <SectionCard title="照護項目" testId="sitter-visit-panel-checklist">
      {items.length === 0 ? (
        <p className="muted" style={{ margin: 0 }} data-testid="sitter-visit-panel-checklist-empty">
          尚無檢核項，可新增照護項目
        </p>
      ) : (
        items.map((it, idx) => (
          <div
            key={it.itemId}
            className="surface-block"
            style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}
            data-testid={`sitter-visit-panel-checklist-item-${it.itemId}`}
            onDragOver={(e) => canEdit && e.preventDefault()}
            onDrop={() => canEdit && dropOn(it)}
          >
            {canEdit && (
              <span
                draggable
                onDragStart={() => setDragId(it.itemId)}
                aria-label="拖曳排序"
                style={{ cursor: 'grab', flexShrink: 0, color: 'var(--color-on-surface-variant)' }}
                data-testid={`sitter-visit-panel-checklist-drag-${it.itemId}`}
              >
                ⠿
              </span>
            )}
            <input
              type="checkbox"
              checked={it.isCompleted}
              disabled={!canCheck || busy}
              onChange={(e) =>
                run(() =>
                  checkChecklistItem(visit.orderId, visit.visitId, it.itemId, e.target.checked)
                )
              }
              aria-label={it.title}
              data-testid={`sitter-visit-panel-checklist-check-${it.itemId}`}
              style={{ flexShrink: 0 }}
            />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ overflowWrap: 'anywhere' }}>{it.title}</div>
              {it.isCompleted && it.checkedAt && (
                <div
                  className="muted"
                  style={{ fontSize: '0.75rem' }}
                  data-testid={`sitter-visit-panel-checklist-checked-at-${it.itemId}`}
                >
                  {formatDateTime(it.checkedAt)}
                </div>
              )}
            </div>
            {canEdit && (
              <div style={{ display: 'flex', gap: '0.25rem', flexShrink: 0 }}>
                <Button
                  variant="secondary"
                  style={{ padding: '0.25rem 0.5rem' }}
                  disabled={busy || idx === 0}
                  onClick={() => move(idx, -1)}
                  aria-label="上移"
                >
                  ↑
                </Button>
                <Button
                  variant="secondary"
                  style={{ padding: '0.25rem 0.5rem' }}
                  disabled={busy || idx === items.length - 1}
                  onClick={() => move(idx, 1)}
                  aria-label="下移"
                >
                  ↓
                </Button>
                <Button
                  variant="danger-outline"
                  style={{ padding: '0.25rem 0.5rem', whiteSpace: 'nowrap' }}
                  disabled={busy}
                  onClick={() =>
                    run(() => deleteChecklistItem(visit.orderId, visit.visitId, it.itemId))
                  }
                  data-testid={`sitter-visit-panel-checklist-btn-delete-${it.itemId}`}
                >
                  刪除
                </Button>
              </div>
            )}
          </div>
        ))
      )}
      {canEdit && (
        <div className="stack" style={{ gap: '0.375rem' }}>
          <div style={{ display: 'flex', gap: '0.5rem' }}>
            <input
              className="form-input"
              aria-label="新增照護項目"
              placeholder="新增照護項目（1–50 字）"
              value={title}
              onChange={(e) => setTitle(truncateCodePoints(e.target.value, 50))}
              style={{ flex: 1, minWidth: 0 }}
              data-testid="sitter-visit-panel-checklist-input-new"
            />
            <Button
              className="btn-primary"
              style={{ whiteSpace: 'nowrap', flexShrink: 0, padding: '0.5rem 1rem' }}
              disabled={add.pending || titleLen < 1 || items.length >= MAX_ITEMS}
              onClick={submitAdd}
              data-testid="sitter-visit-panel-checklist-btn-add"
            >
              新增
            </Button>
          </div>
          {items.length >= MAX_ITEMS && (
            <p className="muted" style={{ margin: 0 }}>
              每個行程最多 20 項
            </p>
          )}
        </div>
      )}
      {(error || !!add.error) && (
        <p role="alert" className="field-error">
          {error ?? errorMessage(getApiError(add.error), 'checklist')}
        </p>
      )}
    </SectionCard>
  );
};

export default ChecklistSection;
