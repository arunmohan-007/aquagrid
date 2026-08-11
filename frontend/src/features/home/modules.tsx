import type { ReactNode } from 'react';
import DashboardIcon from '@mui/icons-material/SpaceDashboardOutlined';
import ProfileIcon from '@mui/icons-material/PersonOutlineOutlined';
import MapIcon from '@mui/icons-material/MapOutlined';
import AssetsIcon from '@mui/icons-material/Inventory2Outlined';
import ImportIcon from '@mui/icons-material/UploadFileOutlined';
import SchemaIcon from '@mui/icons-material/SchemaOutlined';
import LayersIcon from '@mui/icons-material/LayersOutlined';
import PaletteIcon from '@mui/icons-material/PaletteOutlined';
import DevicesIcon from '@mui/icons-material/SensorsOutlined';
import DataConfigIcon from '@mui/icons-material/TuneOutlined';
import ReceiverIcon from '@mui/icons-material/SettingsInputAntennaOutlined';
import SimulatorIcon from '@mui/icons-material/ScienceOutlined';
import TelemetryIcon from '@mui/icons-material/ShowChartOutlined';
import ReportsIcon from '@mui/icons-material/SummarizeOutlined';
import AlarmsIcon from '@mui/icons-material/NotificationsNoneOutlined';
import UsersIcon from '@mui/icons-material/GroupOutlined';
import RolesIcon from '@mui/icons-material/BadgeOutlined';
import WorkOrdersIcon from '@mui/icons-material/HandymanOutlined';
import MaintenanceIcon from '@mui/icons-material/BuildCircleOutlined';
import AnalyticsIcon from '@mui/icons-material/InsightsOutlined';
import SettingsIcon from '@mui/icons-material/SettingsOutlined';

/**
 * The module registry — the single source of truth for what the product contains.
 *
 * This is consumed by the AppShell's breadcrumb (longest-prefix match against the current path)
 * to label the active module. The launcher at `/home` renders its own separate card list; this
 * registry exists so the breadcrumb does not duplicate that routing knowledge.
 *
 * Each entry declares the permission that gates it. `soon` marks a module whose backend exists
 * (or is planned) but whose UI does not, so the shape of the platform is visible without
 * pretending the pages are there.
 *
 * No per-module accent colour lives here anymore: the launcher draws every module from the
 * blue→cyan→teal "aqua" gradient system, and `palette.ts` reserves saturated red, amber and
 * orange for alarm severity — a decorative orange tile would compete with the one thing an
 * operator must be able to read from colour alone.
 */

export interface ModuleDef {
  /** Stable key, also used as the React key. */
  id: string;
  label: string;
  description: string;
  icon: ReactNode;
  to: string;
  /** `null` means every authenticated user may open it. */
  permission: string | null;
  soon?: boolean;
}

export const MODULES: ModuleDef[] = [
  {
    id: 'dashboard',
    label: 'Dashboard',
    description: 'Operational status of the network at a glance',
    icon: <DashboardIcon />,
    to: '/dashboard',
    permission: null,
  },
  {
    /*
     * Not a launcher card — Profile is an account screen, reached from the avatar menu. It is
     * registered here only so the AppShell breadcrumb can label /profile.
     */
    id: 'profile',
    label: 'Profile',
    description: 'Your account, organisation, roles and permissions',
    icon: <ProfileIcon />,
    to: '/profile',
    permission: null,
  },
  {
    id: 'gis-map',
    label: 'GIS Map',
    description: 'Interactive network map with layers, measurement and tracing',
    icon: <MapIcon />,
    to: '/map',
    permission: 'gis:map:view',
  },
  {
    id: 'assets',
    label: 'Asset Register',
    description: 'Meters, valves, pipes and tanks — the full spatial inventory',
    icon: <AssetsIcon />,
    to: '/assets',
    permission: 'gis:asset:read',
  },
  {
    id: 'import',
    label: 'Import Hub',
    description: 'Bulk-import pipes, points, facilities and boundaries',
    icon: <ImportIcon />,
    to: '/import',
    permission: 'gis:asset:create',
  },
  {
    id: 'data-management',
    label: 'Data Management',
    description: 'The master field catalogue — what every layer holds, and what import and export read',
    icon: <SchemaIcon />,
    to: '/data-management',
    permission: 'gis:metadata:read',
  },
  /*
   * GIS Management. Two entries rather than one, mirroring the two permissions: the registry decides
   * what the product contains, the styles decide what it looks like, and the people who do those two
   * jobs are not always the same people.
   */
  {
    id: 'layer-management',
    label: 'Layer Management',
    description: 'The GIS layer registry — geometry, CRS, extent, capabilities and lifecycle',
    icon: <LayersIcon />,
    to: '/gis-layers',
    permission: 'gis:layer:read',
  },
  {
    id: 'layer-styles',
    label: 'Layer Styles',
    description: 'How each layer is drawn — symbols, attribute-based rules, labels and zoom ranges',
    icon: <PaletteIcon />,
    to: '/layer-styles',
    permission: 'gis:style:read',
  },
  {
    id: 'users',
    label: 'Users',
    description: 'Accounts, invitations and the full user lifecycle',
    icon: <UsersIcon />,
    to: '/users',
    permission: 'identity:user:read',
  },
  {
    id: 'roles',
    label: 'Roles & Permissions',
    description: 'Role catalogue, permission matrix and custom role design',
    icon: <RolesIcon />,
    to: '/roles',
    permission: 'identity:role:read',
  },
  {
    id: 'devices',
    label: 'Devices',
    description: 'Device registry — register meters, sensors and controllers on any network',
    icon: <DevicesIcon />,
    to: '/devices',
    permission: 'iot:device:read',
  },
  {
    id: 'device-data-config',
    label: 'Device Data Configuration',
    description:
      'What each device is expected to send — units, ranges and what reaches dashboards, alarms and reports',
    icon: <DataConfigIcon />,
    to: '/device-data-config',
    permission: 'iot:data-config:read',
  },
  {
    id: 'telemetry',
    label: 'Device Telemetry',
    description: 'Meter readings and device information — latest values, grouped, with history',
    icon: <TelemetryIcon />,
    to: '/telemetry',
    permission: 'iot:device:read',
  },
  {
    id: 'reports',
    label: 'Reports',
    description: 'Timestamped readings, by device, device type or network — Excel or PDF',
    icon: <ReportsIcon />,
    to: '/reports',
    permission: 'report:report:generate',
  },
  {
    id: 'receiver',
    label: 'Receiver',
    description: 'Live telemetry ingestion — every packet, with device and network timestamps',
    icon: <ReceiverIcon />,
    to: '/receiver',
    permission: 'iot:receiver:read',
  },
  {
    id: 'simulator',
    label: 'Simulator',
    description: 'A virtual fleet for inspection and validation, on the real telemetry pipeline',
    icon: <SimulatorIcon />,
    to: '/simulator',
    permission: 'iot:simulator:run',
  },
  {
    id: 'alarms',
    label: 'Alarms',
    description: 'Live alarm console, acknowledgement and escalation',
    icon: <AlarmsIcon />,
    to: '/alarms',
    permission: 'ops:alarm:read',
    soon: true,
  },
  {
    id: 'work-orders',
    label: 'Work Orders',
    description: 'Field jobs, assignment, SLA tracking and close-out',
    icon: <WorkOrdersIcon />,
    to: '/work-orders',
    permission: 'ops:work-order:read',
    soon: true,
  },
  {
    id: 'maintenance',
    label: 'Maintenance',
    description: 'Preventive schedules, meter reading rounds and downtime',
    icon: <MaintenanceIcon />,
    to: '/maintenance',
    permission: 'ops:work-order:read',
    soon: true,
  },
  {
    id: 'analytics',
    label: 'Analytics',
    description: 'Water balance, non-revenue water and consumption insight',
    icon: <AnalyticsIcon />,
    to: '/analytics',
    permission: 'analytics:dashboard:view',
    soon: true,
  },
  {
    id: 'settings',
    label: 'Settings',
    description: 'Tenant configuration, feature flags and preferences',
    icon: <SettingsIcon />,
    to: '/settings',
    permission: 'admin:settings:manage',
    soon: true,
  },
];
