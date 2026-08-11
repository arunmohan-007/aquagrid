import { useState } from 'react';
import { Link as RouterLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
  AppBar,
  Avatar,
  Box,
  Button,
  Chip,
  Divider,
  IconButton,
  ListItemIcon,
  Menu,
  MenuItem,
  Stack,
  Toolbar,
  Tooltip,
  Typography,
} from '@mui/material';
import WaterIcon from '@mui/icons-material/WaterDropOutlined';
import LogoutIcon from '@mui/icons-material/LogoutOutlined';
import AppsIcon from '@mui/icons-material/GridViewOutlined';
import ProfileIcon from '@mui/icons-material/PersonOutlineOutlined';
import BackIcon from '@mui/icons-material/ArrowBackOutlined';
import { useAuth } from '@/lib/auth/AuthProvider';
import { MODULES } from '@/features/home/modules';

/**
 * Chrome for the authenticated product.
 *
 * There is deliberately no navigation drawer. Navigation lives on the launcher at `/home`,
 * where each module is a self-describing card; this bar carries only what must be reachable
 * from every screen — a route back to the launcher and the account menu. (The colour-scheme
 * toggle is gone: the product ships dark-only.)
 *
 * The current module's name is shown beside the brand so a user who arrived by deep link, or
 * who has several tabs open, can tell where they are without a highlighted sidebar item.
 */
export function AppShell() {
  const location = useLocation();

  const onLauncher = location.pathname === '/home';
  /*
   * Longest matching path prefix, so /assets/:id still resolves to the Asset Register.
   * Compared on the path alone, and tie-broken toward the shorter `to` so an entry that
   * carries a query string can never out-rank the plain route it decorates.
   */
  const activeModule = MODULES.map((mod) => ({ mod, path: mod.to.split('?')[0] ?? mod.to }))
    .filter(
      ({ path }) => location.pathname === path || location.pathname.startsWith(`${path}/`),
    )
    .sort((a, b) => b.path.length - a.path.length || a.mod.to.length - b.mod.to.length)[0]?.mod;

  return (
    <Box className="flex min-h-screen flex-col">
      {/* Keyboard users reach content without tabbing the whole bar on every page. */}
      <a href="#main-content" className="ag-skip-link">
        Skip to main content
      </a>

      <AppBar
        position="fixed"
        elevation={0}
        color="transparent"
        sx={{
          borderBottom: 1,
          // Gradient hairline: the border is the aqua gradient faded to faint, reading as depth.
          borderColor: 'transparent',
          /*
           * `height` must be the string '1px'. In `sx`, height/width are sizing properties and a
           * bare number <= 1 is read as a fraction, so `height: 1` compiles to `height: 100%` —
           * which turns this hairline into a full-bleed overlay across the whole bar. It paints
           * above the Toolbar's non-positioned children, so it silently ate every click on the
           * Home button and the account menu. `pointerEvents: 'none'` is the second guard: this
           * is decoration and must never be a hit target whatever its computed size.
           */
          '::after': {
            content: '""',
            position: 'absolute',
            left: 0,
            right: 0,
            bottom: 0,
            height: '1px',
            pointerEvents: 'none',
            background:
              'linear-gradient(90deg, transparent, rgba(59,130,246,0.45), rgba(34,211,238,0.45), transparent)',
          },
        }}
      >
        <Toolbar sx={{ gap: 1 }}>
          {/*
            The way out of a module. It sits first in the bar — where a back control is looked
            for — and carries a visible word, because an unlabelled icon parked beside the avatar
            reads as decoration and leaves the operator feeling stranded in the module.
          */}
          {!onLauncher ? (
            <Button
              component={RouterLink}
              to="/home"
              startIcon={<BackIcon />}
              size="small"
              // The word is hidden below `sm`, so the accessible name is stated explicitly.
              aria-label="Home"
              sx={{
                flexShrink: 0,
                color: 'text.secondary',
                borderRadius: 2,
                px: { xs: 1, sm: 1.5 },
                '& .MuiButton-startIcon': { mr: { xs: 0, sm: 0.75 } },
                '&:hover': { color: 'primary.main', bgcolor: 'rgba(59,130,246,0.10)' },
              }}
            >
              <Box component="span" sx={{ display: { xs: 'none', sm: 'inline' } }}>
                Home
              </Box>
            </Button>
          ) : null}

          <Stack
            direction="row"
            spacing={1.25}
            alignItems="center"
            sx={{ flexGrow: 1, minWidth: 0 }}
          >
            <Stack
              direction="row"
              spacing={1}
              alignItems="center"
              component={RouterLink}
              to="/home"
              sx={{ textDecoration: 'none', color: 'inherit', minWidth: 0 }}
            >
              <WaterIcon
                color="primary"
                sx={{ filter: 'drop-shadow(0 0 6px rgba(59,130,246,0.55))' }}
              />
              <Typography
                variant="h4"
                component="span"
                sx={{ fontWeight: 700, display: { xs: 'none', sm: 'block' } }}
              >
                AquaGrid
              </Typography>
            </Stack>

            {activeModule && !onLauncher ? (
              <>
                <Typography component="span" color="text.disabled" aria-hidden>
                  /
                </Typography>
                <Typography variant="body2" color="text.secondary" noWrap>
                  {activeModule.label}
                </Typography>
              </>
            ) : null}
          </Stack>

          <AccountMenu />
        </Toolbar>
      </AppBar>

      <Box
        component="main"
        id="main-content"
        className="flex-1"
        sx={{ bgcolor: 'background.default' }}
      >
        <Toolbar />
        <Box sx={{ p: { xs: 2, sm: 3 }, maxWidth: 1440, mx: 'auto' }}>
          <Outlet />
        </Box>
      </Box>
    </Box>
  );
}

function AccountMenu() {
  const { user, signOut } = useAuth();
  const navigate = useNavigate();
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);

  const initials = (user?.fullName ?? '?')
    .split(' ')
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join('');

  return (
    <>
      <Tooltip title={user?.fullName ?? 'Account'}>
        <IconButton onClick={(event) => setAnchor(event.currentTarget)} aria-label="Account menu">
          <Avatar
            src={user?.avatarUrl}
            sx={{ width: 34, height: 34, bgcolor: 'primary.main', fontSize: 14 }}
          >
            {initials}
          </Avatar>
        </IconButton>
      </Tooltip>

      <Menu anchorEl={anchor} open={Boolean(anchor)} onClose={() => setAnchor(null)}>
        <Box sx={{ px: 2, py: 1.25, minWidth: 240 }}>
          <Typography variant="subtitle1">{user?.fullName}</Typography>
          <Typography variant="caption" color="text.secondary" display="block">
            {user?.email}
          </Typography>
          <Chip
            label={user?.organization.name}
            size="small"
            variant="outlined"
            sx={{ mt: 1, maxWidth: '100%' }}
          />
        </Box>
        <Divider />
        {/* Repeated here on purpose: the toolbar button is the primary route home, but a user
            who opened this menu looking for a way out should find one rather than hunt again. */}
        <MenuItem component={RouterLink} to="/home" onClick={() => setAnchor(null)}>
          <ListItemIcon>
            <AppsIcon fontSize="small" />
          </ListItemIcon>
          All modules
        </MenuItem>
        <Divider />
        <MenuItem component={RouterLink} to="/profile" onClick={() => setAnchor(null)}>
          <ListItemIcon>
            <ProfileIcon fontSize="small" />
          </ListItemIcon>
          Profile
        </MenuItem>
        <MenuItem component={RouterLink} to="/change-password" onClick={() => setAnchor(null)}>
          Change password
        </MenuItem>
        <MenuItem component={RouterLink} to="/security" onClick={() => setAnchor(null)}>
          Security &amp; sessions
        </MenuItem>
        <Divider />
        <MenuItem
          onClick={async () => {
            setAnchor(null);
            await signOut();
            navigate('/login', { replace: true });
          }}
        >
          <ListItemIcon>
            <LogoutIcon fontSize="small" />
          </ListItemIcon>
          Sign out
        </MenuItem>
      </Menu>
    </>
  );
}
