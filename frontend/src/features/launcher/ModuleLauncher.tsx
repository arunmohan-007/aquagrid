import { useNavigate } from 'react-router-dom';
import {
  Avatar,
  Box,
  Card,
  CardActionArea,
  CardContent,
  Chip,
  Grid,
  IconButton,
  Stack,
  Tooltip,
  Typography,
} from '@mui/material';
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
import UsersIcon from '@mui/icons-material/GroupOutlined';
import RolesIcon from '@mui/icons-material/BadgeOutlined';
import SettingsIcon from '@mui/icons-material/SettingsOutlined';
import MaintenanceIcon from '@mui/icons-material/BuildCircleOutlined';
import DashboardIcon from '@mui/icons-material/SpaceDashboardOutlined';
import LogoutIcon from '@mui/icons-material/LogoutOutlined';
import WaterIcon from '@mui/icons-material/WaterDropOutlined';
import type { ReactNode } from 'react';
import { useAuth } from '@/lib/auth/AuthProvider';
import { tokenStore } from '@/lib/auth/tokenStore';

/**
 * The fullscreen module launcher — the screen you land on after login.
 *
 * No sidebar, no app shell, no chrome beyond a slim frosted top bar for the brand and the user.
 * The whole viewport is a grid of large, attractive module cards over an animated aqua
 * backdrop. Clicking a card enters that module (which renders inside the AppShell with a back
 * button).
 *
 * This is the "home screen" pattern: the operator sees everything the platform does at a glance,
 * picks where to go, and a persistent Back control returns here. Unbuilt modules are visible but
 * dimmed with a "Coming soon" badge so the product's shape is honest.
 *
 * Every accent here is drawn from the blue→cyan→teal "aqua" system. No module uses red, amber or
 * orange — those hues are reserved for alarm severity and must never appear as a brand affordance.
 */

interface ModuleDef {
  label: string;
  description: string;
  icon: ReactNode;
  to: string;
  permission: string | null;
  soon?: boolean;
}

const MODULES: ModuleDef[] = [
  {
    label: 'Dashboard',
    description: 'Operational status of the network at a glance',
    icon: <DashboardIcon sx={{ fontSize: 40 }} />,
    to: '/dashboard',
    permission: null,
  },
  {
    label: 'GIS Map',
    description: 'Interactive network map with live assets and tracing',
    icon: <MapIcon sx={{ fontSize: 40 }} />,
    to: '/map',
    permission: 'gis:map:view',
  },
  {
    label: 'Asset Register',
    description: 'Meters, valves, pipes, tanks — the full inventory',
    icon: <AssetsIcon sx={{ fontSize: 40 }} />,
    to: '/assets',
    permission: 'gis:asset:read',
  },
  {
    label: 'Import Hub',
    description: 'Bulk-import any layer from CSV, Shapefile or GeoJSON',
    icon: <ImportIcon sx={{ fontSize: 40 }} />,
    to: '/import',
    permission: 'gis:asset:create',
  },
  {
    label: 'Data Management',
    description: 'The master field catalogue every import and export reads',
    icon: <SchemaIcon sx={{ fontSize: 40 }} />,
    to: '/data-management',
    permission: 'gis:metadata:read',
  },
  {
    label: 'Layer Management',
    description: 'The GIS layer registry — geometry, CRS, extent and capabilities',
    icon: <LayersIcon sx={{ fontSize: 40 }} />,
    to: '/gis-layers',
    permission: 'gis:layer:read',
  },
  {
    label: 'Layer Styles',
    description: 'How each layer is drawn — symbols, attribute rules and labels',
    icon: <PaletteIcon sx={{ fontSize: 40 }} />,
    to: '/layer-styles',
    permission: 'gis:style:read',
  },
  {
    label: 'Devices',
    description: 'Device registry — meters, sensors and controllers on any network',
    icon: <DevicesIcon sx={{ fontSize: 40 }} />,
    to: '/devices',
    permission: 'iot:device:read',
  },
  {
    label: 'Device Data Configuration',
    description: 'What each device sends — units, ranges, dashboards, alarms and reports',
    icon: <DataConfigIcon sx={{ fontSize: 40 }} />,
    to: '/device-data-config',
    permission: 'iot:data-config:read',
  },
  {
    label: 'Device Telemetry',
    description: 'Meter readings and device information, with history',
    icon: <TelemetryIcon sx={{ fontSize: 40 }} />,
    to: '/telemetry',
    permission: 'iot:device:read',
  },
  {
    label: 'Reports',
    description: 'Timestamped readings — by device, device type or network — Excel or PDF',
    icon: <ReportsIcon sx={{ fontSize: 40 }} />,
    to: '/reports',
    permission: 'report:report:generate',
  },
  {
    label: 'Receiver',
    description: 'Live telemetry ingestion — every packet, with device and network timestamps',
    icon: <ReceiverIcon sx={{ fontSize: 40 }} />,
    to: '/receiver',
    permission: 'iot:receiver:read',
  },
  {
    label: 'Simulator',
    description: 'A virtual fleet for inspection and validation, on the real pipeline',
    icon: <SimulatorIcon sx={{ fontSize: 40 }} />,
    to: '/simulator',
    permission: 'iot:simulator:run',
  },
  {
    label: 'Users',
    description: 'Accounts, invitations and lifecycle management',
    icon: <UsersIcon sx={{ fontSize: 40 }} />,
    to: '/users',
    permission: 'identity:user:read',
  },
  {
    label: 'Roles',
    description: 'Role catalogue, permissions and custom roles',
    icon: <RolesIcon sx={{ fontSize: 40 }} />,
    to: '/roles',
    permission: 'identity:role:read',
  },
  {
    label: 'Maintenance',
    description: 'Work orders, schedules and downtime records',
    icon: <MaintenanceIcon sx={{ fontSize: 40 }} />,
    to: '/maintenance',
    permission: 'ops:work-order:read',
    soon: true,
  },
  {
    label: 'Settings',
    description: 'Tenant configuration and preferences',
    icon: <SettingsIcon sx={{ fontSize: 40 }} />,
    to: '/settings',
    permission: 'admin:settings:manage',
    soon: true,
  },
];

/*
 * One aqua gradient, rotated per module by index so the cards form a continuous spectrum
 * (blue → cyan → teal) across the grid without ever leaving the brand family. A single source
 * gradient keeps the palette coherent; the rotation is the only per-module variable.
 */
const AQUA_STOPS = ['#3B82F6', '#22D3EE', '#2DD4BF'];
function aquaGradient(index: number): string {
  const n = AQUA_STOPS.length;
  // Walk the stops with a phase offset so adjacent cards differ.
  const a = AQUA_STOPS[index % n];
  const b = AQUA_STOPS[(index + 1) % n];
  const c = AQUA_STOPS[(index + 2) % n];
  return `linear-gradient(135deg, ${a}, ${b}, ${c})`;
}

export default function ModuleLauncher() {
  const { user, hasPermission, signOut } = useAuth();
  const navigate = useNavigate();
  if (!user) return null;

  const initials = user.fullName
    .split(' ')
    .slice(0, 2)
    .map((p) => p.charAt(0).toUpperCase())
    .join('');

  const handleSignOut = async () => {
    await signOut();
    navigate('/login', { replace: true });
  };

  return (
    <Box sx={{ minHeight: '100vh', display: 'flex', flexDirection: 'column', bgcolor: 'background.default' }}>
      {/* Animated aqua backdrop: a deep base with two drifting radial accent glows. Cheap (CSS
          only) and collapses to static under prefers-reduced-motion via the theme guard. */}
      <Box
        aria-hidden
        sx={{
          position: 'fixed',
          inset: 0,
          zIndex: 0,
          pointerEvents: 'none',
          background:
            'radial-gradient(900px 500px at 12% -5%, rgba(59,130,246,0.18), transparent 60%),' +
            'radial-gradient(800px 520px at 95% 8%, rgba(34,211,238,0.14), transparent 60%),' +
            'radial-gradient(700px 600px at 70% 110%, rgba(45,212,191,0.10), transparent 60%)',
        }}
      />

      {/* Slim frosted top bar — brand + user + sign out. No sidebar, no theme toggle. */}
      <Stack
        direction="row"
        alignItems="center"
        justifyContent="space-between"
        sx={{
          position: 'relative',
          zIndex: 2,
          px: { xs: 2, sm: 4 },
          py: 1.5,
          backgroundColor: 'rgba(10,14,26,0.55)',
          backdropFilter: 'blur(12px) saturate(140%)',
          borderBottom: 1,
          borderColor: 'transparent',
          // '1px', not 1: in `sx` a bare number <= 1 is a fraction, so `height: 1` would compile
          // to 100% and blanket the bar — covering the avatar and the sign-out button.
          '::after': {
            content: '""',
            position: 'absolute',
            left: 0,
            right: 0,
            bottom: 0,
            height: '1px',
            pointerEvents: 'none',
            background:
              'linear-gradient(90deg, transparent, rgba(59,130,246,0.4), rgba(34,211,238,0.4), transparent)',
          },
        }}
      >
        <Stack direction="row" spacing={1.25} alignItems="center">
          <WaterIcon
            color="primary"
            sx={{ fontSize: 28, filter: 'drop-shadow(0 0 6px rgba(59,130,246,0.55))' }}
          />
          <Typography variant="h4" component="span" sx={{ fontWeight: 800 }}>
            AquaGrid
          </Typography>
          <Chip label={user.organization.name} size="small" variant="outlined" sx={{ ml: 1 }} />
        </Stack>

        <Stack direction="row" spacing={1} alignItems="center">
          {/* The avatar already looked clickable; it now goes where a user expects — their profile. */}
          <Tooltip title={`${user.fullName} — profile`}>
            <IconButton onClick={() => navigate('/profile')} aria-label="Profile" sx={{ p: 0.5 }}>
              <Avatar
                src={user.avatarUrl}
                sx={{ width: 36, height: 36, bgcolor: 'primary.main', fontSize: 14 }}
              >
                {initials}
              </Avatar>
            </IconButton>
          </Tooltip>
          <Tooltip title="Sign out">
            <IconButton onClick={handleSignOut} aria-label="Sign out">
              <LogoutIcon />
            </IconButton>
          </Tooltip>
        </Stack>
      </Stack>

      {/* Module card grid — the main content, centered, attractive */}
      <Box
        sx={{
          position: 'relative',
          zIndex: 1,
          flexGrow: 1,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          p: { xs: 2, sm: 4 },
        }}
      >
        <Stack spacing={4} sx={{ width: '100%', maxWidth: 1200 }}>
          {/* Welcome line — heading with a subtle aqua gradient text fill. */}
          <Stack spacing={0.5} alignItems="center">
            <Stack direction="row" spacing={1} alignItems="center">
              <DashboardIcon color="primary" />
              <Typography
                variant="h2"
                sx={{
                  fontWeight: 800,
                  backgroundImage: 'linear-gradient(120deg, #60A5FA, #22D3EE, #2DD4BF)',
                  backgroundClip: 'text',
                  WebkitBackgroundClip: 'text',
                  // The fill is the gradient; text colour becomes transparent to reveal it.
                  color: 'transparent',
                }}
              >
                Welcome, {user.fullName.split(' ')[0]}
              </Typography>
            </Stack>
            <Typography variant="body1" color="text.secondary">
              Select a module to get started
            </Typography>
          </Stack>

          {/* The cards */}
          <Grid container spacing={2.5} justifyContent="center">
            {MODULES.map((mod, index) => {
              const allowed = !mod.permission || hasPermission(mod.permission);
              if (!allowed && !mod.soon) return null;
              const gradient = aquaGradient(index);

              return (
                <Grid key={mod.label} size={{ xs: 12, sm: 6, md: 4, lg: 3 }}>
                  <Card
                    elevation={0}
                    sx={{
                      position: 'relative',
                      height: '100%',
                      minHeight: 180,
                      borderRadius: 4,
                      border: 1,
                      borderColor: 'divider',
                      // Glassmorphism: translucent surface over the aqua backdrop.
                      backgroundColor: 'rgba(19,26,42,0.6)',
                      backdropFilter: 'blur(10px) saturate(130%)',
                      transition: 'all 0.2s ease',
                      overflow: 'hidden',
                      '&:hover': mod.soon
                        ? {}
                        : {
                            borderColor: 'transparent',
                            transform: 'translateY(-4px)',
                            boxShadow: '0 18px 44px rgba(34,211,238,0.18), 0 8px 18px rgba(0,0,0,0.5)',
                            // Gradient hairline border surfaces on hover.
                            '::before': { opacity: 1 },
                          },
                      // Persistent faint gradient border; brightens on hover.
                      '::before': {
                        content: '""',
                        position: 'absolute',
                        inset: 0,
                        borderRadius: 4,
                        padding: 1,
                        background: gradient,
                        opacity: 0.35,
                        WebkitMask: 'linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0)',
                        WebkitMaskComposite: 'xor',
                        maskComposite: 'exclude',
                        pointerEvents: 'none',
                        transition: 'opacity 0.2s ease',
                      },
                    }}
                  >
                    <CardActionArea
                      onClick={() => {
                        if (!mod.soon) navigate(mod.to);
                      }}
                      disabled={mod.soon}
                      sx={{ height: '100%' }}
                    >
                      <CardContent>
                        <Stack spacing={2} alignItems="center" sx={{ textAlign: 'center', py: 2 }}>
                          {/* Gradient icon badge — the aqua gradient, glowing. */}
                          <Box
                            sx={{
                              width: 72,
                              height: 72,
                              borderRadius: '50%',
                              display: 'flex',
                              alignItems: 'center',
                              justifyContent: 'center',
                              background: gradient,
                              color: '#fff',
                              opacity: mod.soon ? 0.35 : 1,
                              boxShadow: mod.soon
                                ? 'none'
                                : '0 10px 28px rgba(34,211,238,0.35)',
                              transition: 'transform 0.2s ease',
                            }}
                          >
                            {mod.icon}
                          </Box>

                          <Stack spacing={0.5} alignItems="center">
                            <Stack direction="row" spacing={1} alignItems="center">
                              <Typography variant="h3">{mod.label}</Typography>
                              {mod.soon ? (
                                <Chip label="Soon" size="small" variant="outlined" sx={{ height: 18, fontSize: 10 }} />
                              ) : null}
                            </Stack>
                            <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 200 }}>
                              {mod.description}
                            </Typography>
                          </Stack>
                        </Stack>
                      </CardContent>
                    </CardActionArea>
                  </Card>
                </Grid>
              );
            })}
          </Grid>
        </Stack>
      </Box>

      {/* Footer */}
      <Box
        sx={{
          position: 'relative',
          zIndex: 1,
          textAlign: 'center',
          py: 2,
          borderTop: 1,
          borderColor: 'transparent',
          '::before': {
            content: '""',
            position: 'absolute',
            left: 0,
            right: 0,
            top: 0,
            height: '1px',
            pointerEvents: 'none',
            background:
              'linear-gradient(90deg, transparent, rgba(59,130,246,0.3), rgba(34,211,238,0.3), transparent)',
          },
        }}
      >
        <Typography variant="caption" color="text.secondary">
          AquaGrid Enterprise Water Management · {user.roles.length} role{user.roles.length === 1 ? '' : 's'} · {user.permissions.length} permission{user.permissions.length === 1 ? '' : 's'}
        </Typography>
      </Box>
    </Box>
  );
}

// Re-exported for the token store reset on sign-out (used by the launcher's sign-out handler).
export { tokenStore };
