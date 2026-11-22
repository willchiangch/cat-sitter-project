import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Button from '../../components/ui/Button';
import { adminTaskStatusLabel, adminTaskTypeLabel } from '../../components/ui/statusLabels';
import { LoadingBlock, PageContainer, QueryErrorBlock } from '../../components/orders/common';
import { useAdminTasksQuery } from '../../hooks/useOrders';
import { formatDateTime } from '../../utils/datetime';

/** ADMIN 待辦（SD-ORDER §9.1 `/admin/tasks`，E28；created_at 由舊到新） */
const AdminTasksPage: React.FC = () => {
  const navigate = useNavigate();
  const [status, setStatus] = useState<'OPEN' | 'RESOLVED'>('OPEN');
  const [page, setPage] = useState(0);
  const { data, isLoading, error, refetch } = useAdminTasksQuery(status, page);
  const totalPages = data ? Math.max(1, Math.ceil(data.totalElements / data.size)) : 1;
  return (
    <PageContainer>
      <h2 style={{ margin: 0, color: 'var(--color-on-surface)' }}>平台待辦</h2>
      <div>
        <label className="field-label" htmlFor="task-status">
          狀態
        </label>
        <select
          id="task-status"
          className="form-input"
          value={status}
          onChange={(e) => {
            setStatus(e.target.value as 'OPEN' | 'RESOLVED');
            setPage(0);
          }}
        >
          <option value="OPEN">{adminTaskStatusLabel('OPEN')}</option>
          <option value="RESOLVED">{adminTaskStatusLabel('RESOLVED')}</option>
        </select>
      </div>
      {isLoading ? (
        <LoadingBlock />
      ) : error ? (
        <QueryErrorBlock error={error} onRetry={() => refetch()} />
      ) : !data || data.content.length === 0 ? (
        <p className="muted" style={{ textAlign: 'center' }}>
          目前沒有待辦
        </p>
      ) : (
        <>
          {data.content.map((t) => (
            <div
              key={t.taskId}
              className="card-layered stack"
              style={{ padding: '1rem', cursor: 'pointer', gap: '0.25rem' }}
              role="link"
              tabIndex={0}
              onClick={() => navigate(`/admin/orders/${t.orderId}`)}
              onKeyDown={(e) => e.key === 'Enter' && navigate(`/admin/orders/${t.orderId}`)}
              data-testid={`admin-task-item-${t.taskId}`}
            >
              <strong>{adminTaskTypeLabel(t.type)}</strong>
              <span className="muted">訂單 {t.orderId.slice(0, 8).toUpperCase()}</span>
              <span className="muted">{formatDateTime(t.createdAt)}</span>
            </div>
          ))}
          <div className="btn-row" style={{ justifyContent: 'center', alignItems: 'center' }}>
            <Button variant="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>
              上一頁
            </Button>
            <span className="muted" style={{ whiteSpace: 'nowrap' }}>
              {page + 1} / {totalPages}
            </span>
            <Button
              variant="secondary"
              disabled={page + 1 >= totalPages}
              onClick={() => setPage(page + 1)}
            >
              下一頁
            </Button>
          </div>
        </>
      )}
    </PageContainer>
  );
};

export default AdminTasksPage;
