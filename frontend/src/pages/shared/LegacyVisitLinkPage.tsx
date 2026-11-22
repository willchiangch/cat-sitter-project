import React from 'react';
import { useNavigate } from 'react-router-dom';
import Button from '../../components/ui/Button';
import { PageContainer } from '../../components/orders/common';
import { useRole } from '../../contexts/useRole';

/**
 * 舊日誌連結 `/visit-reports/manage/:visitId`、`/visit-reports/view/:visitId` 的提示頁（SD-FULFILL §9.1，
 * 使用者 2026-11-22 決定不轉址）：不呼叫任何 API；一顆按鈕依目前角色導向行程列表 / 我的訂單。
 * 角色守門沿用路由的 RequireRole。
 */
const LegacyVisitLinkPage: React.FC = () => {
  const navigate = useNavigate();
  const { currentRole } = useRole();
  const target = currentRole === 'sitter' ? '/sitter/visits' : '/owner/orders';
  return (
    <PageContainer testId="legacy-visit-link">
      <p className="muted">此連結已更新，請從行程或訂單詳情進入照護日誌。</p>
      <div className="btn-row">
        <Button
          className="btn-primary"
          onClick={() => navigate(target)}
          data-testid="legacy-visit-link-btn-back"
        >
          {currentRole === 'sitter' ? '前往行程' : '前往我的訂單'}
        </Button>
      </div>
    </PageContainer>
  );
};

export default LegacyVisitLinkPage;
