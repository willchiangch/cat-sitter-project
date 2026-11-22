import React, { useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import Button from '../ui/Button';
import StatusChip from '../ui/StatusChip';
import { CARE_LOG_EXPIRED_LABEL, careLogStatusLabel } from '../ui/statusLabels';
import { ConfirmSheet, LoadingBlock, QueryErrorBlock, SectionCard } from '../orders/common';
import MediaView from './MediaView';
import { deleteCareLogMedia, saveCareLogDraft, submitCareLog } from '../../api/fulfillApi';
import type { CareLogView, MediaItem, VisitDetail } from '../../api/fulfillApi';
import { getApiError } from '../../api/apiError';
import { errorMessage } from '../../api/errorMessages';
import { useCareLogQuery, visitKeys } from '../../hooks/useVisits';
import { useIdempotentAction } from '../../hooks/useIdempotentAction';
import { formatDateTime } from '../../utils/datetime';
import { codePointLength, stripText, truncateCodePoints } from '../../utils/text';
import {
  isSessionReusable,
  MediaRejectError,
  newUploadSession,
  prepareCareLogMedia,
  runCareLogUpload,
  UploadPutError,
  type PreparedMedia,
  type UploadSession
} from '../../utils/careLogUpload';

const TEXT_MAX = 1000;

interface PendingUpload {
  file: File;
  media?: PreparedMedia;
  session?: UploadSession;
  error?: string;
  busy: boolean;
}

const CareLogBody: React.FC<{ visit: VisitDetail; log: CareLogView; onChanged: () => void }> = ({
  visit,
  log,
  onChanged
}) => {
  const qc = useQueryClient();
  const key = visitKeys.careLog(visit.orderId, visit.visitId);
  // 文字只在掛載時從伺服器帶入；上傳 / 刪除只更新媒體與額度，不覆蓋未暫存的文字（PRD-022 BR-8）
  const [text, setText] = useState(log.text ?? '');
  const [caption, setCaption] = useState('');
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [pending, setPending] = useState<PendingUpload | null>(null);
  const [saving, setSaving] = useState(false);
  const [confirmSubmit, setConfirmSubmit] = useState(false);
  const [readOnly, setReadOnly] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);
  const submit = useIdempotentAction();

  const submitted = log.status === 'SUBMITTED';
  const editable = !!log.editable && !readOnly && !submitted;
  const submittable = !!log.submittable && !readOnly && !submitted;
  const quota = log.quota;
  const textOnly = !!quota && quota.imageLimit === 0 && quota.videoLimit === 0;

  const patchLog = (patch: Partial<CareLogView>) =>
    qc.setQueryData(key, (old: CareLogView | undefined) => (old ? { ...old, ...patch } : old));

  const handleError = (e: unknown) => {
    const err = getApiError(e);
    if (err.code === 'CARE_LOG_STATE_CONFLICT' || err.code === 'DEADLINE_PASSED') setReadOnly(true);
    setMsg({ ok: false, text: errorMessage(err, 'careLog') });
  };

  const saveDraft = async () => {
    setMsg(null);
    setSaving(true);
    try {
      await saveCareLogDraft(visit.orderId, visit.visitId, text);
      patchLog({ text, status: 'DRAFT' });
      setMsg({ ok: true, text: '已暫存草稿' });
    } catch (e) {
      handleError(e);
    } finally {
      setSaving(false);
    }
  };

  const doSubmit = async () => {
    setMsg(null);
    const res = await submit.run((k) => submitCareLog(visit.orderId, visit.visitId, text, k));
    setConfirmSubmit(false);
    if (res) {
      setMsg({ ok: true, text: '日誌已送出' });
      onChanged();
      qc.invalidateQueries({ queryKey: key });
    }
  };

  const startUpload = async (p: PendingUpload) => {
    setPending({ ...p, busy: true, error: undefined });
    try {
      const media = p.media ?? (await prepareCareLogMedia(p.file, quota!));
      const session = p.session ?? newUploadSession();
      try {
        const created = await runCareLogUpload(
          visit.orderId,
          visit.visitId,
          media,
          stripText(caption),
          session
        );
        setPending(null);
        setCaption('');
        if (fileRef.current) fileRef.current.value = '';
        patchLog({ quota: created.quota });
        // 取得含簽名網址的媒體列表（只更新媒體與額度）
        qc.invalidateQueries({ queryKey: key });
      } catch (e) {
        const reusable = isSessionReusable(e);
        const text =
          e instanceof UploadPutError ? e.message : errorMessage(getApiError(e), 'careLog');
        if (getApiError(e).code === 'CARE_LOG_STATE_CONFLICT') setReadOnly(true);
        setPending({
          file: p.file,
          media,
          session: reusable ? session : undefined,
          error: text,
          busy: false
        });
      }
    } catch (e) {
      // 前端檢查（格式、大小、時長、額度）不送出
      setPending({
        file: p.file,
        error: e instanceof MediaRejectError ? e.message : '上傳失敗，請稍候重試',
        busy: false
      });
    }
  };

  const removeMedia = async (m: MediaItem) => {
    setMsg(null);
    try {
      const res = await deleteCareLogMedia(visit.orderId, visit.visitId, m.mediaId);
      patchLog({ media: log.media.filter((x) => x.mediaId !== m.mediaId), quota: res.quota });
    } catch (e) {
      handleError(e);
    }
  };

  const statusLabel = submitted
    ? careLogStatusLabel('SUBMITTED')
    : log.expired
      ? CARE_LOG_EXPIRED_LABEL
      : log.status
        ? careLogStatusLabel(log.status)
        : '尚未建立';

  return (
    <>
      <div className="btn-row" style={{ alignItems: 'center', justifyContent: 'space-between' }}>
        <StatusChip
          label={statusLabel}
          tone={
            submitted
              ? 'success'
              : log.expired
                ? 'danger'
                : log.status === 'DRAFT'
                  ? 'warning'
                  : 'neutral'
          }
          status={log.status}
          testId="sitter-care-log-status"
        />
        {log.deadlineAt && (
          <span className="muted" data-testid="sitter-care-log-deadline">
            日誌可送出至 {formatDateTime(log.deadlineAt)}
          </span>
        )}
      </div>

      <div>
        <label className="field-label" htmlFor="care-log-text">
          日誌內容
        </label>
        <textarea
          id="care-log-text"
          className="form-input"
          rows={6}
          value={text}
          disabled={!editable}
          onChange={(e) => setText(truncateCodePoints(e.target.value, TEXT_MAX))}
          data-testid="sitter-care-log-input-text"
        />
        <div
          className="muted"
          style={{ textAlign: 'right', fontSize: '0.75rem' }}
          data-testid="sitter-care-log-text-counter"
        >
          {codePointLength(text)} / {TEXT_MAX}
        </div>
      </div>

      {quota && (
        <p className="muted" style={{ margin: 0 }} data-testid="sitter-care-log-quota">
          {textOnly
            ? '此訂單方案僅支援文字回報'
            : `照片 ${quota.imageUsed}/${quota.imageLimit}・影片 ${quota.videoUsed}/${quota.videoLimit}`}
        </p>
      )}

      {log.media.length > 0 && (
        <div className="stack" style={{ gap: '0.5rem' }}>
          {log.media.map((m) => (
            <div
              key={m.mediaId}
              className="surface-block stack"
              style={{ gap: '0.375rem' }}
              data-testid={`sitter-care-log-media-item-${m.mediaId}`}
            >
              <MediaView media={m} onUrlExpired={() => qc.invalidateQueries({ queryKey: key })} />
              {m.caption && (
                <span className="muted" style={{ overflowWrap: 'anywhere' }}>
                  {m.caption}
                </span>
              )}
              {editable && (
                <div className="btn-row">
                  <Button
                    variant="danger-outline"
                    style={{ padding: '0.25rem 0.75rem' }}
                    onClick={() => removeMedia(m)}
                    data-testid={`sitter-care-log-btn-delete-media-${m.mediaId}`}
                  >
                    刪除
                  </Button>
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {editable && !textOnly && quota && (
        <div className="stack" style={{ gap: '0.5rem' }}>
          <div>
            <label className="field-label" htmlFor="care-log-caption">
              說明（選填，100 字內）
            </label>
            <input
              id="care-log-caption"
              className="form-input"
              value={caption}
              onChange={(e) => setCaption(truncateCodePoints(e.target.value, 100))}
              data-testid="sitter-care-log-input-caption"
            />
          </div>
          <input
            ref={fileRef}
            type="file"
            accept="image/jpeg,image/png,image/webp,video/mp4,video/quicktime"
            aria-label="選擇照片或影片"
            style={{ maxWidth: '100%' }}
            onChange={(e) => {
              const f = e.target.files?.[0];
              setPending(f ? { file: f, busy: false } : null);
            }}
            data-testid="sitter-care-log-input-file"
          />
          {pending?.error && (
            <p role="alert" className="field-error">
              {pending.error}
            </p>
          )}
          <div className="btn-row">
            <Button
              variant="secondary"
              disabled={!pending || pending.busy}
              onClick={() => pending && startUpload(pending)}
              data-testid="sitter-care-log-btn-upload"
            >
              {pending?.busy ? '上傳中…' : pending?.session ? '重試上傳' : '上傳'}
            </Button>
          </div>
        </div>
      )}

      {!submitted && (
        <div className="btn-row">
          <Button
            variant="secondary"
            disabled={!editable || saving}
            onClick={saveDraft}
            data-testid="sitter-care-log-btn-save"
          >
            {saving ? '暫存中…' : '暫存草稿'}
          </Button>
          <Button
            className="btn-primary"
            disabled={!submittable || submit.pending || codePointLength(stripText(text)) === 0}
            onClick={() => setConfirmSubmit(true)}
            data-testid="sitter-care-log-btn-submit"
          >
            送出日誌
          </Button>
        </div>
      )}
      {!submitted && visit.status !== 'DONE' && editable && (
        <p className="muted" style={{ margin: 0 }}>
          行程結束後才能送出
        </p>
      )}
      {submitted && log.submittedAt && (
        <p className="muted" style={{ margin: 0 }}>
          送出時間 {formatDateTime(log.submittedAt)}
        </p>
      )}
      {submit.error ? (
        <p role="alert" className="field-error">
          {errorMessage(getApiError(submit.error), 'careLog')}
        </p>
      ) : null}
      {msg && (
        <p
          role={msg.ok ? 'status' : 'alert'}
          className={msg.ok ? 'muted' : 'field-error'}
          style={{ margin: 0 }}
        >
          {msg.text}
        </p>
      )}
      <ConfirmSheet
        isOpen={confirmSubmit}
        title="送出日誌"
        message="送出後無法再修改，確定要送出嗎？"
        confirmText="確認送出"
        pending={submit.pending}
        onCancel={() => setConfirmSubmit(false)}
        onConfirm={doSubmit}
      />
    </>
  );
};

/** 照護日誌編輯區（PRD-022、SD-FULFILL F13–F19） */
const CareLogEditor: React.FC<{ visit: VisitDetail; onChanged: () => void }> = ({
  visit,
  onChanged
}) => {
  const { data, isLoading, error, refetch } = useCareLogQuery(visit.orderId, visit.visitId);
  return (
    <SectionCard title="照護日誌">
      {isLoading ? (
        <LoadingBlock />
      ) : error || !data ? (
        <QueryErrorBlock error={error} context="careLog" onRetry={() => refetch()} />
      ) : (
        <CareLogBody key={data.careLogId ?? 'new'} visit={visit} log={data} onChanged={onChanged} />
      )}
    </SectionCard>
  );
};

export default CareLogEditor;
