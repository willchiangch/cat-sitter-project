import React from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import Button from '../../components/ui/Button';
import {
  LoadingBlock,
  PageContainer,
  QueryErrorBlock,
  SectionCard
} from '../../components/orders/common';
import MediaView from '../../components/visits/MediaView';
import { useCareLogQuery, visitKeys } from '../../hooks/useVisits';
import { useRole } from '../../contexts/useRole';
import { getApiError } from '../../api/apiError';
import { formatDateTime } from '../../utils/datetime';

/**
 * 飼主 / ADMIN 檢視照護日誌（SD-FULFILL §9.1 `/orders/:orderId/visits/:visitId/care-log`，F13；
 * 取代 `/visit-reports/view/:visitId`）。只讀 SUBMITTED（草稿 404「找不到此日誌」）；
 * 媒體網址失效時重新呼叫 F13；`purged` →「檔案已逾期移除」。
 */
const CareLogViewPage: React.FC<{ orderId: string; visitId: string }> = ({ orderId, visitId }) => {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { currentRole } = useRole();
  const { data, isLoading, error, refetch } = useCareLogQuery(orderId, visitId);
  const backHref =
    currentRole === 'admin' ? `/admin/orders/${orderId}` : `/owner/orders/${orderId}`;

  if (isLoading)
    return (
      <PageContainer>
        <LoadingBlock />
      </PageContainer>
    );
  if (error || !data) {
    const err = getApiError(error);
    return (
      <PageContainer>
        {err.code === 'NOT_FOUND' ? (
          <p className="muted">找不到此日誌</p>
        ) : err.code === 'FORBIDDEN' ? (
          <p className="muted">無權限檢視此訂單</p>
        ) : err.code === 'STORAGE_UNAVAILABLE' ? (
          <QueryErrorBlock error={error} context="careLog" onRetry={() => refetch()} />
        ) : (
          <QueryErrorBlock error={error} onRetry={() => refetch()} />
        )}
        <div className="btn-row">
          <Button variant="secondary" onClick={() => navigate(backHref)}>
            返回訂單
          </Button>
        </div>
      </PageContainer>
    );
  }

  return (
    <PageContainer testId="owner-care-log-view">
      <SectionCard>
        <h2
          style={{ margin: 0, overflowWrap: 'anywhere', fontSize: '1.25rem' }}
          data-testid="owner-care-log-visit-name"
        >
          {data.visitName}
        </h2>
        <span className="muted">
          {data.serviceDate}
          {data.submittedAt ? `・送出於 ${formatDateTime(data.submittedAt)}` : ''}
        </span>
        <p style={{ margin: 0, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{data.text}</p>
      </SectionCard>
      {data.media.length > 0 && (
        <SectionCard title="照片與影片">
          {data.media.map((m) => (
            <div key={m.mediaId} className="stack" style={{ gap: '0.25rem' }}>
              <MediaView
                media={m}
                onUrlExpired={() =>
                  qc.invalidateQueries({ queryKey: visitKeys.careLog(orderId, visitId) })
                }
              />
              {m.caption && (
                <span className="muted" style={{ overflowWrap: 'anywhere' }}>
                  {m.caption}
                </span>
              )}
            </div>
          ))}
        </SectionCard>
      )}
      <div className="btn-row">
        <Button variant="secondary" onClick={() => navigate(backHref)}>
          返回訂單
        </Button>
      </div>
    </PageContainer>
  );
};

export default CareLogViewPage;
