import React, { useState, useEffect, useRef } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { useRole } from '../../contexts/RoleContext';
import {
  useUnreadCountQuery,
  useNotificationsQuery,
  useMarkAsReadMutation,
  useMarkAllAsReadMutation
} from '../../hooks/useNotifications';
import { getSitterKycStatus } from '../../api/kycApi';
import type { NotificationDto } from '../../api/notificationApi';

const AppHeader: React.FC = () => {
  const { currentRole } = useRole();
  const navigate = useNavigate();
  const location = useLocation();
  const currentViewName = location.pathname;
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const [kycStatus, setKycStatus] = useState<string | null>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);

  const roleParam =
    currentRole === 'client' ? 'OWNER' : currentRole === 'sitter' ? 'SITTER' : undefined;

  // 1. 查詢未讀通知數 (Polling 60秒， stales 30秒)
  const { data: unreadCount = 0 } = useUnreadCountQuery(roleParam);

  // 2. 查詢最近的通知列表 (Dropdown 只顯示前 10 筆)
  const { data: notificationsData } = useNotificationsQuery(0, 10, undefined, roleParam);
  const recentNotifications = notificationsData?.content || [];

  const markAsReadMutation = useMarkAsReadMutation(roleParam);
  const markAllAsReadMutation = useMarkAllAsReadMutation(roleParam);

  // 3. 獲取保母 KYC 狀態 (僅當角色為 sitter 時)
  useEffect(() => {
    if (currentRole === 'sitter') {
      getSitterKycStatus()
        .then((data) => {
          setKycStatus(data.kycStatus);
        })
        .catch(() => {
          setKycStatus('UNVERIFIED');
        });
    } else {
      setKycStatus(null);
    }
  }, [currentRole, currentViewName]); // 每次視圖或角色變更時重新檢查

  // 4. 點選外部關閉 Dropdown
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setDropdownOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const handleNotificationClick = async (noti: NotificationDto) => {
    setDropdownOpen(false);
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

  const getKycBannerConfig = () => {
    switch (kycStatus) {
      case 'UNVERIFIED':
        return {
          text: '⚠️ 您尚未完成保母實名認證，將限制開啟接單狀態。',
          actionText: '立即填寫',
          color: '#b54708',
          bg: '#fffaeb',
          border: '#fedf89'
        };
      case 'PENDING_REVIEW':
        return {
          text: '⏳ 您的認證資料正在審核中，通過後將開放接單。',
          actionText: '查看申請',
          color: '#027a48',
          bg: '#ecfdf3',
          border: '#abefc6'
        };
      case 'REJECTED':
        return {
          text: '❌ 您的實名認證未通過，請點選重新提交文件。',
          actionText: '重新認證',
          color: '#b42318',
          bg: '#fef3f2',
          border: '#fee4e2'
        };
      case 'SUSPENDED':
        return {
          text: '🚫 您的保母接單帳號已被系統限制停權。',
          actionText: '查看詳情',
          color: '#363f72',
          bg: '#f8f9fc',
          border: '#e4e7ec'
        };
      default:
        return null;
    }
  };

  const bannerConfig = getKycBannerConfig();

  return (
    <div style={{ position: 'sticky', top: 0, zIndex: 100, display: 'flex', flexDirection: 'column', width: '100%' }}>
      {/* 頂部導覽列 */}
      <header
        className="glass-panel"
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          height: '64px',
          padding: '0 1.5rem',
          borderBottom: '1px solid var(--color-outline-variant)'
        }}
      >
        {/* Logo */}
        <div
          onClick={() => navigate('/demo')}
          style={{
            fontFamily: 'var(--font-display)',
            fontWeight: 700,
            fontSize: '1.25rem',
            color: 'var(--color-primary)',
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            gap: '0.5rem'
          }}
        >
          🐾 WhiskerWatch
        </div>

        {/* 右側小鈴鐺 */}
        <div style={{ position: 'relative' }} ref={dropdownRef}>
          <button
            onClick={() => setDropdownOpen(!dropdownOpen)}
            data-testid={`${currentRole}-header-bell`}
            style={{
              background: 'none',
              border: 'none',
              cursor: 'pointer',
              fontSize: '1.5rem',
              padding: '6px',
              borderRadius: '50%',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              position: 'relative',
              transition: 'background-color 0.2s',
              outline: 'none'
            }}
            onMouseEnter={(e) => (e.currentTarget.style.backgroundColor = 'var(--color-surface-low)')}
            onMouseLeave={(e) => (e.currentTarget.style.backgroundColor = 'transparent')}
          >
            🔔
            {unreadCount > 0 && (
              <span
                style={{
                  position: 'absolute',
                  top: '2px',
                  right: '2px',
                  backgroundColor: '#b02500',
                  color: 'white',
                  borderRadius: '50%',
                  fontSize: '0.65rem',
                  fontWeight: 'bold',
                  minWidth: '16px',
                  height: '16px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  padding: '2px',
                  boxShadow: '0 2px 6px rgba(0,0,0,0.15)'
                }}
              >
                {unreadCount > 99 ? '99+' : unreadCount}
              </span>
            )}
          </button>

          {/* 下拉選單 Dropdown */}
          {dropdownOpen && (
            <div
              className="card-layered"
              data-testid={`${currentRole}-header-bell-dropdown`}
              style={{
                position: 'absolute',
                top: '48px',
                right: '0',
                width: '320px',
                maxHeight: '400px',
                display: 'flex',
                flexDirection: 'column',
                padding: '0.75rem 0',
                animation: 'fade-in 0.2s ease',
                zIndex: 200,
                border: '1px solid var(--color-outline-variant)'
              }}
            >
              {/* Dropdown Header */}
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  padding: '0.5rem 1rem 0.75rem 1rem',
                  borderBottom: '1px solid var(--color-surface-low)'
                }}
              >
                <span style={{ fontWeight: 700, fontSize: '0.9rem' }}>最新通知</span>
                {unreadCount > 0 && (
                  <button
                    onClick={() => markAllAsReadMutation.mutate()}
                    data-testid={`${currentRole}-header-bell-readall`}
                    style={{
                      background: 'none',
                      border: 'none',
                      color: 'var(--color-primary)',
                      fontSize: '0.8rem',
                      fontWeight: 600,
                      cursor: 'pointer'
                    }}
                  >
                    全部標示已讀
                  </button>
                )}
              </div>

              {/* Dropdown 內容清單 */}
              <div style={{ overflowY: 'auto', flex: 1, maxHeight: '280px' }}>
                {recentNotifications.length === 0 ? (
                  <div style={{ padding: '2rem', textAlign: 'center', color: 'var(--color-on-surface-variant)', fontSize: '0.875rem' }}>
                    目前沒有通知 🐾
                  </div>
                ) : (
                  recentNotifications.map((noti) => (
                    <div
                      key={noti.id}
                      onClick={() => handleNotificationClick(noti)}
                      data-testid={`${currentRole}-header-bell-item-${noti.id}`}
                      style={{
                        padding: '0.75rem 1rem',
                        borderBottom: '1px solid var(--color-surface-low)',
                        cursor: 'pointer',
                        display: 'flex',
                        flexDirection: 'column',
                        gap: '0.25rem',
                        backgroundColor: noti.isRead ? 'transparent' : 'var(--color-surface-low)',
                        transition: 'background-color 0.2s'
                      }}
                      onMouseEnter={(e) => (e.currentTarget.style.backgroundColor = 'var(--color-surface-high)')}
                      onMouseLeave={(e) =>
                        (e.currentTarget.style.backgroundColor = noti.isRead ? 'transparent' : 'var(--color-surface-low)')
                      }
                    >
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                        <span style={{ fontWeight: noti.isRead ? 500 : 700, fontSize: '0.85rem', color: 'var(--color-on-surface)' }}>
                          {noti.title}
                        </span>
                        {!noti.isRead && (
                          <span
                            style={{
                              width: '6px',
                              height: '6px',
                              borderRadius: '50%',
                              backgroundColor: 'var(--color-primary)'
                            }}
                          />
                        )}
                      </div>
                      <span style={{ fontSize: '0.75rem', color: 'var(--color-on-surface-variant)', lineHeight: 1.3 }}>
                        {noti.content}
                      </span>
                      <span style={{ fontSize: '0.65rem', color: '#888', alignSelf: 'flex-end' }}>
                        {new Date(noti.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                      </span>
                    </div>
                  ))
                )}
              </div>

              {/* Dropdown Footer */}
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  padding: '0.75rem 1rem 0.25rem 1rem',
                  borderTop: '1px solid var(--color-surface-low)',
                  fontSize: '0.8rem'
                }}
              >
                <button
                  onClick={() => {
                    setDropdownOpen(false);
                    navigate('/notifications');
                  }}
                  data-testid={`${currentRole}-header-bell-viewall`}
                  style={{
                    background: 'none',
                    border: 'none',
                    color: 'var(--color-primary)',
                    fontWeight: 600,
                    cursor: 'pointer'
                  }}
                >
                  進入通知中心
                </button>
                <button
                  onClick={() => {
                    setDropdownOpen(false);
                    navigate('/preferences');
                  }}
                  data-testid={`${currentRole}-header-bell-preferences`}
                  style={{
                    background: 'none',
                    border: 'none',
                    color: 'var(--color-on-surface-variant)',
                    fontWeight: 600,
                    cursor: 'pointer'
                  }}
                >
                  偏好設定
                </button>
              </div>
            </div>
          )}
        </div>
      </header>

      {/* KYC 置頂 Banner 警示 (僅在保母角色，且 status !== VERIFIED 時呈現) */}
      {bannerConfig && (
        <div
          data-testid={`${currentRole}-kyc-banner`}
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            padding: '10px 1.5rem',
            backgroundColor: bannerConfig.bg,
            borderBottom: `1px solid ${bannerConfig.border}`,
            fontSize: '0.8rem',
            color: bannerConfig.color,
            fontWeight: 500,
            animation: 'fade-in 0.3s ease'
          }}
        >
          <span>{bannerConfig.text}</span>
          <button
            onClick={() => navigate('/sitter/kyc')}
            data-testid={`${currentRole}-kyc-banner-action`}
            style={{
              backgroundColor: bannerConfig.color,
              color: 'white',
              border: 'none',
              borderRadius: '4px',
              padding: '4px 8px',
              cursor: 'pointer',
              fontWeight: 600,
              fontSize: '0.75rem'
            }}
          >
            {bannerConfig.actionText}
          </button>
        </div>
      )}
    </div>
  );
};

export default AppHeader;
