import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import Button from '../../components/ui/Button';
import StatusChip from '../../components/ui/StatusChip';
import { subStatusTone } from '../../components/ui/statusTone';
import { visitStatusFullLabel } from '../../components/ui/statusLabels';
import {
  ConfirmSheet,
  KV,
  LoadingBlock,
  PageContainer,
  QueryErrorBlock,
  SectionCard
} from '../../components/orders/common';
import ChecklistSection from '../../components/visits/ChecklistSection';
import CareLogEditor from '../../components/visits/CareLogEditor';
import { completeVisit, renameVisit, startVisit } from '../../api/fulfillApi';
import type { VisitDetail } from '../../api/fulfillApi';
import { getApiError } from '../../api/apiError';
import { errorMessage } from '../../api/errorMessages';
import { useInvalidateVisit, useVisitQuery } from '../../hooks/useVisits';
import { useIdempotentAction } from '../../hooks/useIdempotentAction';
import { useCurrentUser } from '../../hooks/useCurrentUser';
import { formatDateTime } from '../../utils/datetime';
import { stripText, truncateCodePoints } from '../../utils/text';

const VisitHeader: React.FC<{ visit: VisitDetail; onChanged: () => void }> = ({
  visit,
  onChanged
}) => {
  const actions = visit.allowedActions;
  const start = useIdempotentAction();
  const complete = useIdempotentAction();
  const [completeOpen, setCompleteOpen] = useState(false);
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(visit.customName ?? '');
  const [renameError, setRenameError] = useState<string | null>(null);
  const [renaming, setRenaming] = useState(false);
  const unchecked = (visit.checklist?.total ?? 0) - (visit.checklist?.completed ?? 0);
  const pending = visit.status === 'PENDING';

  const saveName = async () => {
    setRenameError(null);
    setRenaming(true);
    try {
      await renameVisit(visit.orderId, visit.visitId, stripText(name));
      setEditing(false);
      onChanged();
    } catch (e) {
      setRenameError(errorMessage(getApiError(e), 'visit'));
    } finally {
      setRenaming(false);
    }
  };

  const actionError = start.error ?? complete.error;
  const actionErr = actionError ? getApiError(actionError) : null;

  return (
    <SectionCard>
      <div className="btn-row" style={{ alignItems: 'center', justifyContent: 'space-between' }}>
        <h2 style={{ margin: 0, minWidth: 0, overflowWrap: 'anywhere', fontSize: '1.25rem' }}>
          {visit.name}
        </h2>
        <StatusChip
          label={visitStatusFullLabel(visit.status, visit.completionSource)}
          tone={subStatusTone(visit.status)}
          status={visit.status}
          testId="sitter-visit-panel-status"
        />
      </div>
      {visit.completionSource === 'SYSTEM' && (
        <span className="muted" data-testid="sitter-visit-panel-auto-completed-label">
          系統自動結束
        </span>
      )}
      <KV label="日期">{visit.serviceDate}</KV>
      <KV label="飼主">{visit.owner?.displayName ?? '—'}</KV>
      <KV label="毛孩">{(visit.pets ?? []).map((p) => p.name).join('、') || '—'}</KV>
      {visit.startedAt && <KV label="開始時間">{formatDateTime(visit.startedAt)}</KV>}
      {visit.completedAt && <KV label="結束時間">{formatDateTime(visit.completedAt)}</KV>}

      {actions.includes('RENAME') &&
        (editing ? (
          <div className="stack" style={{ gap: '0.375rem' }}>
            <div style={{ display: 'flex', gap: '0.5rem' }}>
              <input
                className="form-input"
                aria-label="行程名稱"
                placeholder="留空則使用預設名稱"
                value={name}
                onChange={(e) => setName(truncateCodePoints(e.target.value, 20))}
                style={{ flex: 1, minWidth: 0 }}
                data-testid="sitter-visit-panel-input-name"
              />
              <Button
                className="btn-primary"
                style={{ whiteSpace: 'nowrap', flexShrink: 0, padding: '0.5rem 1rem' }}
                disabled={renaming}
                onClick={saveName}
                data-testid="sitter-visit-panel-btn-save-name"
              >
                儲存
              </Button>
            </div>
            {renameError && (
              <p role="alert" className="field-error">
                {renameError}
              </p>
            )}
          </div>
        ) : (
          <div className="btn-row">
            <Button
              variant="secondary"
              style={{ padding: '0.375rem 0.875rem' }}
              onClick={() => setEditing(true)}
              data-testid="sitter-visit-panel-btn-edit-name"
            >
              修改名稱
            </Button>
          </div>
        ))}

      {pending && !visit.dateReached && (
        <p
          className="muted"
          style={{ margin: 0 }}
          data-testid="sitter-visit-panel-date-not-reached-message"
        >
          行程日期為 {visit.serviceDate}，當天才能開始
        </p>
      )}
      {pending && visit.dateReached && visit.zombieDeadlinePassed && (
        <p
          className="field-error"
          style={{ margin: 0 }}
          data-testid="sitter-visit-panel-deadline-passed-message"
        >
          此行程已超過可開始期限，系統將自動關閉
        </p>
      )}
      {actionErr?.code === 'VISIT_DATE_NOT_REACHED' && (
        <p
          className="muted"
          style={{ margin: 0 }}
          data-testid="sitter-visit-panel-date-not-reached-message"
        >
          行程日期為 {visit.serviceDate}，當天才能開始
        </p>
      )}

      <div className="btn-row">
        {pending && (
          <Button
            className="btn-primary"
            disabled={
              !actions.includes('START') ||
              !visit.dateReached ||
              visit.zombieDeadlinePassed ||
              start.pending
            }
            onClick={async () => {
              const res = await start.run((key) => startVisit(visit.orderId, visit.visitId, key));
              if (res) onChanged();
            }}
            data-testid="sitter-visit-panel-btn-start"
          >
            {start.pending ? '處理中…' : '開始行程'}
          </Button>
        )}
        {visit.status === 'IN_PROGRESS' && (
          <Button
            className="btn-primary"
            disabled={!actions.includes('COMPLETE')}
            onClick={() => {
              complete.reset();
              setCompleteOpen(true);
            }}
            data-testid="sitter-visit-panel-btn-complete"
          >
            結束行程
          </Button>
        )}
      </div>
      {actionErr && actionErr.code !== 'VISIT_DATE_NOT_REACHED' && (
        <p role="alert" className="field-error">
          {errorMessage(actionErr, 'visit')}
        </p>
      )}

      <ConfirmSheet
        isOpen={completeOpen}
        title="結束行程"
        message={
          <div className="stack" data-testid="sitter-visit-panel-complete-dialog">
            <span>
              結束後無法再開始此行程。請於結束後 24 小時內送出照護日誌，逾時日誌將無法送出。
            </span>
            {unchecked > 0 && (
              <span className="muted">尚有 {unchecked} 項未打勾（不影響結束）</span>
            )}
          </div>
        }
        confirmText="確認結束"
        pending={complete.pending}
        onCancel={() => setCompleteOpen(false)}
        onConfirm={async () => {
          const res = await complete.run((key) => completeVisit(visit.orderId, visit.visitId, key));
          setCompleteOpen(false);
          if (res) onChanged();
        }}
        confirmTestId="sitter-visit-panel-btn-complete-confirm"
      />
    </SectionCard>
  );
};

/**
 * 服務執行面板（SD-FULFILL §9.1 `/sitter/orders/:orderId/visits/:visitId`，F2–F5、F8–F19；
 * 取代 `/visit-reports/manage/:visitId`）。開始鈕三態依 F2 `dateReached` / `zombieDeadlinePassed`，按下後以伺服器為準。
 */
const VisitPanel: React.FC<{ orderId: string; visitId: string }> = ({ orderId, visitId }) => {
  const navigate = useNavigate();
  const me = useCurrentUser();
  const { data: visit, isLoading, error, refetch } = useVisitQuery(orderId, visitId);
  const invalidate = useInvalidateVisit();
  const refresh = () => invalidate(orderId, visitId);

  if (isLoading)
    return (
      <PageContainer>
        <LoadingBlock />
      </PageContainer>
    );
  if (error || !visit)
    return (
      <PageContainer>
        <QueryErrorBlock error={error} context="visit" onRetry={() => refetch()} />
      </PageContainer>
    );
  const showCareLog = ['IN_PROGRESS', 'DONE'].includes(visit.status);

  return (
    <PageContainer testId="sitter-visit-panel">
      <VisitHeader visit={visit} onChanged={refresh} />
      {visit.owner && (
        <Link
          to={`/care-notes/manage/${me.userId}/${visit.owner.userId}`}
          style={{ color: 'var(--color-primary)' }}
          data-testid="sitter-visit-panel-link-pet-notes"
        >
          查看照護注意事項
        </Link>
      )}
      <ChecklistSection visit={visit} onChanged={refresh} />
      {showCareLog && <CareLogEditor visit={visit} onChanged={refresh} />}
      <div className="btn-row">
        <Button variant="secondary" onClick={() => navigate(`/sitter/orders/${orderId}`)}>
          返回訂單
        </Button>
      </div>
    </PageContainer>
  );
};

export default VisitPanel;
