import { Routes, Route, Navigate, useParams } from 'react-router-dom';
import App from './App';
import RequireAuth from './components/auth/RequireAuth';
import RequireRole from './components/auth/RequireRole';
import LoginPage from './pages/auth/LoginPage';
import DemoHome from './pages/DemoHome';

import SitterOrders from './pages/sitter/SitterOrders';
import SitterLedger from './pages/sitter/SitterLedger';
import OrderEvalView from './pages/sitter/OrderEvalView';
import PublicBookingPage from './pages/client/PublicBookingPage';
import CareNoteManager from './pages/sitter/CareNoteManager';
import CareNoteView from './pages/client/CareNoteView';
import VisitReportManager from './pages/sitter/VisitReportManager';
import VisitReportView from './pages/client/VisitReportView';
import OwnerOrders from './pages/client/OwnerOrders';
import OwnerOrderDetail from './pages/client/OwnerOrderDetail';
import AdminResolvePanel from './pages/admin/AdminResolvePanel';
import OrderModificationWizard from './pages/client/OrderModificationWizard';
import SitterModificationQuote from './pages/sitter/SitterModificationQuote';
import OwnerModificationConfirm from './pages/client/OwnerModificationConfirm';
import SitterPlans from './pages/sitter/SitterPlans';
import PetManager from './pages/client/PetManager';
import { GatekeeperSettings } from './pages/sitter/GatekeeperSettings';
import SitterPaymentInfoSettings from './pages/sitter/SitterPaymentInfoSettings';
import SitterKycSubmit from './pages/sitter/SitterKycSubmit';
import AdminKycList from './pages/admin/AdminKycList';
import AdminTrustScores from './pages/admin/AdminTrustScores';
import AdminKycDetail from './pages/admin/AdminKycDetail';
import SitterProfileSettings from './pages/sitter/SitterProfileSettings';
import AdminForbiddenKeywords from './pages/admin/AdminForbiddenKeywords';
import AdminSubscriptionPage from './pages/admin/AdminSubscriptionPage';
import { NotificationsPage } from './pages/shared/NotificationsPage';
import { PreferencesPage } from './pages/shared/PreferencesPage';

// 以下頁面需要從網址取得目標資源 id (取代原本 App.tsx 的 mockParams 寫死值)

function BookingRoute() {
  const { sitterId } = useParams<{ sitterId: string }>();
  return <PublicBookingPage sitterId={sitterId} />;
}

function CareNoteManagerRoute() {
  const { sitterId, ownerId } = useParams<{ sitterId: string; ownerId: string }>();
  return <CareNoteManager sitterId={sitterId!} ownerId={ownerId!} />;
}

function CareNoteViewRoute() {
  const { sitterId, ownerId } = useParams<{ sitterId: string; ownerId: string }>();
  return <CareNoteView sitterId={sitterId!} ownerId={ownerId!} />;
}

function VisitReportManagerRoute() {
  const { visitId } = useParams<{ visitId: string }>();
  return <VisitReportManager visitId={visitId!} />;
}

function VisitReportViewRoute() {
  const { visitId } = useParams<{ visitId: string }>();
  return <VisitReportView visitId={visitId!} />;
}

function OwnerOrderDetailRoute() {
  const { orderId } = useParams<{ orderId: string }>();
  return <OwnerOrderDetail orderId={orderId!} />;
}

function AdminResolvePanelRoute() {
  const { orderId } = useParams<{ orderId: string }>();
  return <AdminResolvePanel orderId={orderId!} />;
}

function OrderModificationWizardRoute() {
  const { orderId } = useParams<{ orderId: string }>();
  return <OrderModificationWizard orderId={orderId!} />;
}

function SitterModificationQuoteRoute() {
  const { orderId } = useParams<{ orderId: string }>();
  return <SitterModificationQuote orderId={orderId!} />;
}

function OrderEvalViewRoute() {
  const { orderId } = useParams<{ orderId: string }>();
  return <OrderEvalView orderId={orderId!} />;
}

function OwnerModificationConfirmRoute() {
  const { orderId } = useParams<{ orderId: string }>();
  return <OwnerModificationConfirm orderId={orderId!} />;
}

function AdminKycDetailRoute() {
  const { kycRecordId } = useParams<{ kycRecordId: string }>();
  return <AdminKycDetail kycRecordId={kycRecordId!} />;
}

function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />

      <Route element={<RequireAuth />}>
        <Route element={<App />}>
          <Route path="/" element={<Navigate to="/demo" replace />} />
          <Route path="/demo" element={<DemoHome />} />

          <Route element={<RequireRole roles={['sitter']} />}>
            <Route path="/sitter/orders" element={<SitterOrders />} />
            <Route path="/sitter/ledger" element={<SitterLedger />} />
            <Route path="/sitter/eval/:orderId" element={<OrderEvalViewRoute />} />
            <Route path="/sitter/plans" element={<SitterPlans />} />
            <Route path="/sitter/gatekeeper" element={<GatekeeperSettings />} />
            <Route path="/sitter/payment-settings" element={<SitterPaymentInfoSettings />} />
            <Route path="/sitter/kyc" element={<SitterKycSubmit />} />
            <Route path="/sitter/profile-settings" element={<SitterProfileSettings />} />
            <Route path="/sitter/orders/:orderId/quote" element={<SitterModificationQuoteRoute />} />
            <Route path="/care-notes/manage/:sitterId/:ownerId" element={<CareNoteManagerRoute />} />
            <Route path="/visit-reports/manage/:visitId" element={<VisitReportManagerRoute />} />
          </Route>

          <Route element={<RequireRole roles={['client']} />}>
            <Route path="/booking" element={<BookingRoute />} />
            <Route path="/booking/:sitterId" element={<BookingRoute />} />
            <Route path="/pets" element={<PetManager />} />
            <Route path="/owner/orders" element={<OwnerOrders />} />
            <Route
              path="/owner/orders/:orderId/modification-confirm"
              element={<OwnerModificationConfirmRoute />}
            />
            <Route path="/care-notes/view/:sitterId/:ownerId" element={<CareNoteViewRoute />} />
            <Route path="/visit-reports/view/:visitId" element={<VisitReportViewRoute />} />
          </Route>

          {/* 訂單詳情頁後端為 hasAnyRole('OWNER','SITTER')，飼主/保母皆可查看，非飼主獨有 */}
          <Route element={<RequireRole roles={['client', 'sitter']} />}>
            <Route path="/owner/orders/:orderId" element={<OwnerOrderDetailRoute />} />
            <Route path="/orders/:orderId/modify" element={<OrderModificationWizardRoute />} />
          </Route>

          <Route element={<RequireRole roles={['admin']} />}>
            <Route path="/admin/resolve/:orderId" element={<AdminResolvePanelRoute />} />
            <Route path="/admin/kyc" element={<AdminKycList />} />
            <Route path="/admin/trust-scores" element={<AdminTrustScores />} />
            <Route path="/admin/kyc/:kycRecordId" element={<AdminKycDetailRoute />} />
            <Route path="/admin/forbidden-keywords" element={<AdminForbiddenKeywords />} />
            <Route path="/admin/subscription" element={<AdminSubscriptionPage />} />
          </Route>

          <Route path="/notifications" element={<NotificationsPage />} />
          <Route path="/preferences" element={<PreferencesPage />} />
        </Route>
      </Route>
    </Routes>
  );
}

export default AppRoutes;
