import { Routes, Route, Navigate, useParams } from 'react-router-dom';
import App from './App';
import RequireAuth from './components/auth/RequireAuth';
import RequireRole from './components/auth/RequireRole';
import LoginPage from './pages/auth/LoginPage';
import RegisterPage from './pages/auth/RegisterPage';
import ForgotPasswordPage from './pages/auth/ForgotPasswordPage';
import ResetPasswordPage from './pages/auth/ResetPasswordPage';
import DemoHome from './pages/DemoHome';
import PublicSitterProfilePage from './pages/public/PublicSitterProfilePage';

import SitterOrders from './pages/sitter/SitterOrders';
import SitterLedger from './pages/sitter/SitterLedger';
import SitterOrderDetail from './pages/sitter/SitterOrderDetail';
import PublicBookingPage from './pages/client/PublicBookingPage';
import CareNoteManager from './pages/sitter/CareNoteManager';
import CareNoteView from './pages/client/CareNoteView';
import VisitPanel from './pages/sitter/VisitPanel';
import SitterVisitsDashboard from './pages/sitter/SitterVisitsDashboard';
import OwnerVisitsToday from './pages/client/OwnerVisitsToday';
import CareLogViewPage from './pages/shared/CareLogViewPage';
import LegacyVisitLinkPage from './pages/shared/LegacyVisitLinkPage';
import OwnerOrders from './pages/client/OwnerOrders';
import FavoriteSitters from './pages/client/FavoriteSitters';
import OwnerOrderDetail from './pages/client/OwnerOrderDetail';
import AdminOrderPage from './pages/admin/AdminOrderPage';
import AdminTasksPage from './pages/admin/AdminTasksPage';
import OrderModificationWizard from './pages/client/OrderModificationWizard';
import ModificationDetailPage from './pages/shared/ModificationDetailPage';
import SitterPlans from './pages/sitter/SitterPlans';
import SitterQuestionManager from './pages/sitter/SitterQuestionManager';
import SitterTrustCircle from './pages/sitter/SitterTrustCircle';
import SitterReferral from './pages/sitter/SitterReferral';
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
import AccountSettings from './pages/shared/AccountSettings';

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

function VisitPanelRoute() {
  const { orderId, visitId } = useParams<{ orderId: string; visitId: string }>();
  // key：換行程時重新掛載，未暫存的日誌文字不會帶到下一筆行程
  return <VisitPanel key={visitId} orderId={orderId!} visitId={visitId!} />;
}

function CareLogViewRoute() {
  const { orderId, visitId } = useParams<{ orderId: string; visitId: string }>();
  return <CareLogViewPage key={visitId} orderId={orderId!} visitId={visitId!} />;
}

function OwnerOrderDetailRoute() {
  const { orderId } = useParams<{ orderId: string }>();
  return <OwnerOrderDetail key={orderId} orderId={orderId!} />;
}

function AdminOrderRoute() {
  const { orderId } = useParams<{ orderId: string }>();
  return <AdminOrderPage key={orderId} orderId={orderId!} />;
}

/** 舊路由轉址（SD-ORDER §9.1：`/sitter/eval/:orderId`、`/admin/resolve/:orderId` 等改到新路由） */
function RedirectWithOrderId({ to }: { to: (orderId: string) => string }) {
  const { orderId } = useParams<{ orderId: string }>();
  return <Navigate to={to(orderId!)} replace />;
}

function OrderModificationWizardRoute() {
  const { orderId } = useParams<{ orderId: string }>();
  return <OrderModificationWizard orderId={orderId!} />;
}

function SitterOrderDetailRoute() {
  const { orderId } = useParams<{ orderId: string }>();
  return <SitterOrderDetail key={orderId} orderId={orderId!} />;
}

function ModificationDetailRoute() {
  const { orderId, modificationId } = useParams<{ orderId: string; modificationId: string }>();
  return (
    <ModificationDetailPage
      key={modificationId}
      orderId={orderId!}
      modificationId={modificationId!}
    />
  );
}

function SitterReferralRoute() {
  const { ownerId } = useParams<{ ownerId: string }>();
  return <SitterReferral ownerId={ownerId!} />;
}

function AdminKycDetailRoute() {
  const { kycRecordId } = useParams<{ kycRecordId: string }>();
  return <AdminKycDetail kycRecordId={kycRecordId!} />;
}

function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route path="/forgot-password" element={<ForgotPasswordPage />} />
      <Route path="/reset-password" element={<ResetPasswordPage />} />
      <Route path="/sitter/:sitterId/profile" element={<PublicSitterProfilePage />} />

      <Route element={<RequireAuth />}>
        <Route element={<App />}>
          <Route path="/" element={<Navigate to="/demo" replace />} />
          <Route path="/demo" element={<DemoHome />} />

          <Route element={<RequireRole roles={['sitter']} />}>
            <Route path="/sitter/orders" element={<SitterOrders />} />
            <Route path="/sitter/ledger" element={<SitterLedger />} />
            <Route path="/sitter/orders/:orderId" element={<SitterOrderDetailRoute />} />
            <Route
              path="/sitter/eval/:orderId"
              element={<RedirectWithOrderId to={(id) => `/sitter/orders/${id}`} />}
            />
            <Route path="/sitter/plans" element={<SitterPlans />} />
            <Route path="/sitter/questions" element={<SitterQuestionManager />} />
            <Route path="/sitter/trust-circle" element={<SitterTrustCircle />} />
            <Route path="/sitter/referral/:ownerId" element={<SitterReferralRoute />} />
            <Route path="/sitter/gatekeeper" element={<GatekeeperSettings />} />
            <Route path="/sitter/payment-settings" element={<SitterPaymentInfoSettings />} />
            <Route path="/sitter/kyc" element={<SitterKycSubmit />} />
            <Route path="/sitter/profile-settings" element={<SitterProfileSettings />} />
            <Route
              path="/sitter/orders/:orderId/quote"
              element={<RedirectWithOrderId to={(id) => `/sitter/orders/${id}`} />}
            />
            <Route
              path="/care-notes/manage/:sitterId/:ownerId"
              element={<CareNoteManagerRoute />}
            />
            <Route path="/sitter/visits" element={<SitterVisitsDashboard />} />
            <Route path="/sitter/orders/:orderId/visits/:visitId" element={<VisitPanelRoute />} />
            <Route path="/visit-reports/manage/:visitId" element={<LegacyVisitLinkPage />} />
          </Route>

          <Route element={<RequireRole roles={['client']} />}>
            <Route path="/booking" element={<BookingRoute />} />
            <Route path="/booking/:sitterId" element={<BookingRoute />} />
            <Route path="/pets" element={<PetManager />} />
            <Route path="/owner/orders" element={<OwnerOrders />} />
            <Route path="/owner/favorites" element={<FavoriteSitters />} />
            <Route path="/owner/orders/:orderId" element={<OwnerOrderDetailRoute />} />
            <Route
              path="/owner/orders/:orderId/modification-confirm"
              element={<RedirectWithOrderId to={(id) => `/owner/orders/${id}`} />}
            />
            <Route path="/care-notes/view/:sitterId/:ownerId" element={<CareNoteViewRoute />} />
            <Route path="/owner/visits" element={<OwnerVisitsToday />} />
            <Route path="/visit-reports/view/:visitId" element={<LegacyVisitLinkPage />} />
          </Route>

          {/* 變更精靈與協商頁飼主 / 保母共用（SD-ORDER §9.1）；訂單詳情改為各角色各自路由 */}
          <Route element={<RequireRole roles={['client', 'sitter']} />}>
            <Route path="/orders/:orderId/modify" element={<OrderModificationWizardRoute />} />
            <Route
              path="/orders/:orderId/modifications/:modificationId"
              element={<ModificationDetailRoute />}
            />
          </Route>

          {/* 照護日誌檢視：飼主與可讀範圍 ADMIN（F13，SD-FULFILL §9.1） */}
          <Route element={<RequireRole roles={['client', 'admin']} />}>
            <Route
              path="/orders/:orderId/visits/:visitId/care-log"
              element={<CareLogViewRoute />}
            />
          </Route>

          <Route element={<RequireRole roles={['admin']} />}>
            <Route path="/admin/orders/:orderId" element={<AdminOrderRoute />} />
            <Route path="/admin/tasks" element={<AdminTasksPage />} />
            <Route
              path="/admin/resolve/:orderId"
              element={<RedirectWithOrderId to={(id) => `/admin/orders/${id}`} />}
            />
            <Route path="/admin/kyc" element={<AdminKycList />} />
            <Route path="/admin/trust-scores" element={<AdminTrustScores />} />
            <Route path="/admin/kyc/:kycRecordId" element={<AdminKycDetailRoute />} />
            <Route path="/admin/forbidden-keywords" element={<AdminForbiddenKeywords />} />
            <Route path="/admin/subscription" element={<AdminSubscriptionPage />} />
          </Route>

          <Route path="/notifications" element={<NotificationsPage />} />
          <Route path="/preferences" element={<PreferencesPage />} />
          <Route path="/account-settings" element={<AccountSettings />} />
        </Route>
      </Route>
    </Routes>
  );
}

export default AppRoutes;
