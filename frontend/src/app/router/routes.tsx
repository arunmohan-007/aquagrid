import { lazy, Suspense } from 'react';
import { createBrowserRouter, Navigate, Outlet } from 'react-router-dom';
import { Box, CircularProgress } from '@mui/material';
import { ProtectedRoute } from './ProtectedRoute';
import { AppShell } from '@/layouts/AppShell';

/*
 * Every page is code-split.
 *
 * After login the user lands on the fullscreen ModuleLauncher (/home) — no sidebar, just attractive
 * module cards. Clicking a card enters that module inside the AppShell, which has a Home button to
 * return to the launcher. This is the "home screen + module" pattern used by every modern platform.
 */
const ModuleLauncher = lazy(() => import('@/features/launcher/ModuleLauncher'));
const LoginPage = lazy(() => import('@/features/auth/pages/LoginPage'));
const MfaChallengePage = lazy(() => import('@/features/auth/pages/MfaChallengePage'));
const ForgotPasswordPage = lazy(() => import('@/features/auth/pages/ForgotPasswordPage'));
const ResetPasswordPage = lazy(() => import('@/features/auth/pages/ResetPasswordPage'));
const ChangePasswordPage = lazy(() => import('@/features/auth/pages/ChangePasswordPage'));
const SecurityPage = lazy(() => import('@/features/auth/pages/SecurityPage'));
const DashboardPage = lazy(() => import('@/features/dashboard/pages/DashboardPage'));
const ProfilePage = lazy(() => import('@/features/profile/pages/ProfilePage'));
const UsersListPage = lazy(() => import('@/features/users/pages/UsersListPage'));
const UserDetailPage = lazy(() => import('@/features/users/pages/UserDetailPage'));
const RolesPage = lazy(() => import('@/features/users/pages/RolesPage'));
const GisMapPage = lazy(() => import('@/features/gis/pages/GisMapPage'));
const AssetsListPage = lazy(() => import('@/features/assets/pages/AssetsListPage'));
const ImportHubPage = lazy(() => import('@/features/import/pages/ImportHubPage'));
const DataManagementPage = lazy(
  () => import('@/features/data-management/pages/DataManagementPage'),
);
const LayerManagementPage = lazy(() => import('@/features/layers/pages/LayerManagementPage'));
const LayerStylesPage = lazy(() => import('@/features/layers/pages/LayerStylesPage'));
const AssetDetailPage = lazy(() => import('@/features/assets/pages/AssetDetailPage'));
const DevicesListPage = lazy(() => import('@/features/devices/pages/DevicesListPage'));
const DeviceDataConfigPage = lazy(
  () => import('@/features/device-data-config/pages/DeviceDataConfigPage'),
);
const ReceiverPage = lazy(() => import('@/features/receiver/pages/ReceiverPage'));
const SimulatorPage = lazy(() => import('@/features/simulator/pages/SimulatorPage'));
const TelemetryPage = lazy(() => import('@/features/telemetry/pages/TelemetryPage'));
const ReportsPage = lazy(() => import('@/features/reports/pages/ReportsPage'));
const ForbiddenPage = lazy(() => import('@/features/errors/ForbiddenPage'));
const NotFoundPage = lazy(() => import('@/features/errors/NotFoundPage'));

function RouteFallback() {
  return (
    <Box className="flex h-screen items-center justify-center">
      <CircularProgress size={28} />
    </Box>
  );
}

function SuspenseOutlet() {
  return (
    <Suspense fallback={<RouteFallback />}>
      <Outlet />
    </Suspense>
  );
}

export const router = createBrowserRouter([
  {
    element: <SuspenseOutlet />,
    children: [
      // --- Public ---------------------------------------------------------------------
      { path: '/login', element: <LoginPage /> },
      { path: '/mfa', element: <MfaChallengePage /> },
      { path: '/forgot-password', element: <ForgotPasswordPage /> },
      { path: '/reset-password', element: <ResetPasswordPage /> },

      /*
       * Authenticated but OUTSIDE the app shell. A user who must change their password has
       * no business seeing the navigation for a product they cannot yet use.
       */
      {
        element: <ProtectedRoute />,
        children: [{ path: '/change-password', element: <ChangePasswordPage /> }],
      },

      // --- Fullscreen module launcher (no sidebar) -------------------------------------
      // This is the landing page after login — large attractive cards, no chrome.
      {
        element: <ProtectedRoute />,
        children: [
          { index: true, element: <Navigate to="/home" replace /> },
          { path: '/home', element: <ModuleLauncher /> },
        ],
      },

      // --- Authenticated modules (inside AppShell with Home button) ---------------------
      {
        element: <ProtectedRoute />,
        children: [
          {
            element: <AppShell />,
            children: [
              { path: '/dashboard', element: <DashboardPage /> },
              { path: '/profile', element: <ProfilePage /> },
              { path: '/security', element: <SecurityPage /> },
              // Users & roles (Module 2).
              {
                path: '/users',
                element: <ProtectedRoute anyOf={['identity:user:read']} />,
                children: [
                  { index: true, element: <UsersListPage /> },
                  { path: ':userId', element: <UserDetailPage /> },
                ],
              },
              { path: '/roles', element: <ProtectedRoute anyOf={['identity:role:read']} />,
                children: [{ index: true, element: <RolesPage /> }],
              },
              // GIS map (Module 4) — fullscreen with slide-out layers panel.
              {
                path: '/map',
                element: <ProtectedRoute anyOf={['gis:map:view']} />,
                children: [{ index: true, element: <GisMapPage /> }],
              },
              // Device registry (Module 6). Registration is gated separately, inside the page.
              {
                path: '/devices',
                element: <ProtectedRoute anyOf={['iot:device:read']} />,
                children: [{ index: true, element: <DevicesListPage /> }],
              },
              /*
               * Device Data Configuration — what a registered device's readings mean. Gated on
               * iot:data-config:read, not iot:device:read, for the reason V1109 gives: registering
               * hardware and defining the semantics of everything it reports are different jobs. A
               * commissioning engineer who registers meters all week should not thereby be able to
               * redefine what "volume" means for the fleet. The page hides its write controls
               * without iot:data-config:manage, which the seed grants only to tenant administrators.
               */
              {
                path: '/device-data-config',
                element: <ProtectedRoute anyOf={['iot:data-config:read']} />,
                children: [{ index: true, element: <DeviceDataConfigPage /> }],
              },
              /*
               * Device telemetry (Module 7). Same permission as the register — the catalogue
               * describes iot:device:read as "View devices and telemetry" — because this is the
               * register's own data seen from the other side, not the ingestion pipeline's
               * internals. Someone who may see what a meter reads is not thereby entitled to
               * every gateway's packet payloads, which is what /receiver gates separately.
               */
              {
                path: '/telemetry',
                element: <ProtectedRoute anyOf={['iot:device:read']} />,
                children: [{ index: true, element: <TelemetryPage /> }],
              },
              /*
               * Reports (Module 25). Gated on report:report:generate, not iot:device:read: reading
               * one device's telemetry on screen and extracting the whole fleet's history to a
               * file that leaves the platform are different acts with different blast radii, and
               * the permission catalogue already separates them.
               */
              {
                path: '/reports',
                element: <ProtectedRoute anyOf={['report:report:generate']} />,
                children: [{ index: true, element: <ReportsPage /> }],
              },
              /*
               * Receiver console (Module 18). Gated on iot:receiver:read, not iot:device:read:
               * this is the ingestion pipeline's own telemetry — packet payloads, source addresses,
               * authentication outcomes — and someone entitled to see the device register is not
               * thereby entitled to see what every gateway on the network is sending.
               */
              {
                path: '/receiver',
                element: <ProtectedRoute anyOf={['iot:receiver:read']} />,
                children: [{ index: true, element: <ReceiverPage /> }],
              },
              /*
               * Simulator console (Module 17). Gated on iot:simulator:run, which is a write
               * permission even for the status view — every route behind this screen can author
               * telemetry, and the read is fleet-wide operational detail rather than a dashboard.
               * The page itself handles the deployment where the simulator is switched off.
               */
              {
                path: '/simulator',
                element: <ProtectedRoute anyOf={['iot:simulator:run']} />,
                children: [{ index: true, element: <SimulatorPage /> }],
              },
              // Asset register (Module 23).
              {
                path: '/assets',
                element: <ProtectedRoute anyOf={['gis:asset:read']} />,
                children: [
                  { index: true, element: <AssetsListPage /> },
                  { path: ':assetId', element: <AssetDetailPage /> },
                ],
              },
              /*
               * Data Import Hub — its own module, not a mode of the register. Bulk ingest has a
               * four-step flow (category → layer → format → column mapping) that has nothing to
               * do with browsing existing assets, and creating assets is a distinct permission.
               */
              {
                path: '/import',
                element: <ProtectedRoute anyOf={['gis:asset:create']} />,
                children: [{ index: true, element: <ImportHubPage /> }],
              },
              /*
               * Data Management — the attribute catalogue every import, export and future dynamic
               * form reads. Gated on gis:metadata:read, which is separate from gis:asset:read for
               * the same reason the register and the importer are separate screens: reading an
               * asset's values and defining what values an asset may have are different jobs. The
               * page hides its write controls without gis:metadata:manage, which the seed grants
               * only to the administrator roles.
               */
              {
                path: '/data-management',
                element: <ProtectedRoute anyOf={['gis:metadata:read']} />,
                children: [{ index: true, element: <DataManagementPage /> }],
              },
              /*
               * GIS Management — the layer registry and its styles.
               *
               * Two routes rather than one screen with tabs, because they are two jobs with two
               * permissions: gis:layer:manage decides what the product contains, gis:style:manage
               * decides what it looks like. A cartographer who should be able to recolour the
               * network has no business retiring the pipeline layer, and the routes say so.
               *
               * Neither page holds an attribute editor. "Manage attributes" navigates to
               * /data-management for the selected layer — fields are that module's job, before and
               * after this one existed.
               */
              {
                path: '/gis-layers',
                element: <ProtectedRoute anyOf={['gis:layer:read']} />,
                children: [{ index: true, element: <LayerManagementPage /> }],
              },
              {
                path: '/layer-styles',
                element: <ProtectedRoute anyOf={['gis:style:read']} />,
                children: [{ index: true, element: <LayerStylesPage /> }],
              },
            ],
          },
        ],
      },

      // --- Errors ---------------------------------------------------------------------
      { path: '/forbidden', element: <ForbiddenPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]);

