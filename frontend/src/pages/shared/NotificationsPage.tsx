import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useRole } from '../../contexts/RoleContext';
import {
  useNotificationsQuery,
  useMarkAsReadMutation,
  useMarkAllAsReadMutation
} from '../../hooks/useNotifications';
import type { NotificationDto } from '../../api/notificationApi';

export const NotificationsPage: React.FC = () => {
  const { currentRole } = useRole();
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const size = 10;

  const roleParam =
    currentRole === 'client' ? 'OWNER' : currentRole === 'sitter' ? 'SITTER' : undefined;

  // 取得通知列表
  const { data, isLoading, isError } = useNotificationsQuery(page, size, undefined, roleParam);

  const markAsReadMutation = useMarkAsReadMutation(roleParam);
  const markAllAsReadMutation = useMarkAllAsReadMutation(roleParam);

  // 解析連結進行單頁應用視圖跳轉
  const handleNotificationClick = async (noti: NotificationDto) => {
    if (!noti.isRead) {
      await markAsReadMutation.mutateAsync(noti.id);
    }
    
    // 依據 linkUrl 切換視圖
    if (noti.linkUrl === '/sitter/orders') {
      navigate('/sitter/orders');
    } else if (noti.linkUrl === '/client/orders') {
      navigate('/owner/orders');
    } else if (noti.linkUrl === '/sitter/kyc') {
      navigate('/sitter/kyc');
    }
  };

  const getCategoryLabel = (category: string) => {
    switch (category) {
      case 'ORDER_AFFAIR':
        return '訂單交易';
      case 'ACCOUNT_AUTH':
        return '帳號安全';
      case 'SUBSCRIPTION_MAINTENANCE':
        return '系統維護';
      case 'SERVICE_RECORD':
        return '服務日誌';
      default:
        return '系統通知';
    }
  };

  const getCategoryStyles = (category: string) => {
    switch (category) {
      case 'ORDER_AFFAIR':
        return { bg: '#e0f2fe', color: '#0369a1', border: '#bae6fd' };
      case 'ACCOUNT_AUTH':
        return { bg: '#fee2e2', color: '#b91c1c', border: '#fecaca' };
      case 'SUBSCRIPTION_MAINTENANCE':
        return { bg: '#fef3c7', color: '#b45309', border: '#fde68a' };
      case 'SERVICE_RECORD':
        return { bg: '#dcfce7', color: '#15803d', border: '#bbf7d0' };
      default:
        return { bg: '#f3f4f6', color: '#374151', border: '#e5e7eb' };
    }
  };

  const hasUnread = data?.content.some((n) => !n.isRead);

  return (
    <div style={{ padding: '1.5rem 0', display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
      {/* 標題區 */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <h2 style={{ margin: 0, fontSize: '1.75rem', color: 'var(--color-on-surface)' }}>通知中心</h2>
          <p style={{ margin: '0.25rem 0 0 0', fontSize: '0.875rem', color: 'var(--color-on-surface-variant)' }}>
            查看與您角色相關的最新系統與交易通知
          </p>
        </div>
        <button
          className="btn-primary"
          style={{
            padding: '8px 16px',
            fontSize: '0.875rem',
            opacity: hasUnread ? 1 : 0.6,
            cursor: hasUnread ? 'pointer' : 'not-allowed'
          }}
          disabled={!hasUnread || markAllAsReadMutation.isPending}
          onClick={() => markAllAsReadMutation.mutate()}
          data-testid={`${currentRole}-notifications-mark-all-read`}
        >
          {markAllAsReadMutation.isPending ? '處理中...' : '全部標示已讀'}
        </button>
      </div>

      {/* 通知列表 */}
      {isLoading ? (
        <div style={{ textAlign: 'center', padding: '3rem', color: 'var(--color-on-surface-variant)' }}>
          載入通知中...
        </div>
      ) : isError ? (
        <div style={{ textAlign: 'center', padding: '3rem', color: 'var(--color-error)' }}>
          載入通知失敗，請稍後再試。
        </div>
      ) : !data || data.content.length === 0 ? (
        <div
          className="card-layered"
          style={{
            textAlign: 'center',
            padding: '4rem 2rem',
            color: 'var(--color-on-surface-variant)',
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            gap: '1rem'
          }}
        >
          <div style={{ fontSize: '3rem' }}>🔔</div>
          <div style={{ fontWeight: 600 }}>目前沒有任何通知</div>
          <div style={{ fontSize: '0.875rem' }}>當系統有最新異動時，您會在此收到推播通知。</div>
        </div>
      ) : (
        <div
          style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}
          data-testid={`${currentRole}-notifications-list`}
        >
          {data.content.map((noti) => {
            const styles = getCategoryStyles(noti.category);
            return (
              <div
                key={noti.id}
                onClick={() => handleNotificationClick(noti)}
                className="card-layered"
                data-testid={`${currentRole}-notifications-item-${noti.id}`}
                style={{
                  cursor: 'pointer',
                  padding: '1.25rem',
                  position: 'relative',
                  borderLeft: noti.isRead ? '4px solid transparent' : '4px solid var(--color-primary)',
                  backgroundColor: noti.isRead ? 'var(--color-surface-lowest)' : 'var(--color-surface-low)',
                  transition: 'transform 0.2s ease, box-shadow 0.2s ease',
                  display: 'flex',
                  gap: '1rem',
                  alignItems: 'flex-start'
                }}
                onMouseEnter={(e) => {
                  e.currentTarget.style.transform = 'translateY(-2px)';
                  e.currentTarget.style.boxShadow = '0 12px 30px rgba(0,0,0,0.08)';
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.transform = 'translateY(0)';
                  e.currentTarget.style.boxShadow = 'var(--shadow-ambient)';
                }}
              >
                {/* 狀態紅點 */}
                {!noti.isRead && (
                  <span
                    style={{
                      position: 'absolute',
                      top: '12px',
                      right: '12px',
                      width: '8px',
                      height: '8px',
                      borderRadius: '50%',
                      backgroundColor: 'var(--color-primary)'
                    }}
                  />
                )}

                {/* 內容區 */}
                <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', flexWrap: 'wrap' }}>
                    <span
                      style={{
                        padding: '2px 8px',
                        borderRadius: '4px',
                        fontSize: '0.75rem',
                        fontWeight: 600,
                        backgroundColor: styles.bg,
                        color: styles.color,
                        border: `1px solid ${styles.border}`
                      }}
                    >
                      {getCategoryLabel(noti.category)}
                    </span>
                    <span style={{ fontSize: '0.75rem', color: 'var(--color-on-surface-variant)' }}>
                      {new Date(noti.createdAt).toLocaleString()}
                    </span>
                  </div>

                  <h4 style={{ margin: 0, fontSize: '1.1rem', color: 'var(--color-on-surface)' }}>{noti.title}</h4>
                  <p style={{ margin: 0, fontSize: '0.9rem', color: 'var(--color-on-surface-variant)', lineHeight: 1.5 }}>
                    {noti.content}
                  </p>
                </div>
              </div>
            );
          })}

          {/* 分頁按鈕 */}
          {data.totalPages > 1 && (
            <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', gap: '1rem', marginTop: '1rem' }}>
              <button
                className="btn-primary"
                style={{ padding: '6px 12px', fontSize: '0.8rem' }}
                disabled={page === 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                data-testid={`${currentRole}-notifications-prev-page`}
              >
                上一頁
              </button>
              <span style={{ fontSize: '0.875rem', color: 'var(--color-on-surface-variant)' }}>
                {page + 1} / {data.totalPages} 頁
              </span>
              <button
                className="btn-primary"
                style={{ padding: '6px 12px', fontSize: '0.8rem' }}
                disabled={page >= data.totalPages - 1}
                onClick={() => setPage((p) => p + 1)}
                data-testid={`${currentRole}-notifications-next-page`}
              >
                下一頁
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
};
