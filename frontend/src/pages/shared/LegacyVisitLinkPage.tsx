import React from 'react';
import { useNavigate } from 'react-router-dom';
import Button from '../../components/ui/Button';
import { PageContainer } from '../../components/orders/common';
import { useRole } from '../../contexts/RoleContext';

/**
 * 舊日誌連結 `/visit-reports/manage/:visitId`、`/visit-reports/view/:visitId`（舊通知）。
 * SD-FULFILL §9.1 要求「以 F2 / F13 查出 orderId 後轉址」，但 F2 / F13 路徑本身就需要 orderId、
 * 後端沒有以 visitId 反查訂單的端點，無法轉址；改為提示並導回行程 / 訂單列表（列入回報待釐清）。
 */
const LegacyVisitLinkPage: React.FC = () => {
  const navigate = useNavigate();
  const { currentRole } = useRole();
  const target = currentRole === 'sitter' ? '/sitter/visits' : '/owner/orders';
  return (
    <PageContainer testId="legacy-visit-link">
      <p className="muted">此連結已更新，請從行程或訂單詳情進入照護日誌。</p>
      <div className="btn-row">
        <Button className="btn-primary" onClick={() => navigate(target)}>
          {currentRole === 'sitter' ? '前往行程' : '前往我的訂單'}
        </Button>
      </div>
    </PageContainer>
  );
};

export default LegacyVisitLinkPage;
