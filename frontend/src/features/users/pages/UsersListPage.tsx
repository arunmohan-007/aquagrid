import { useState, type ChangeEvent } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import {
  Alert,
  Box,
  Button,
  Card,
  IconButton,
  InputAdornment,
  MenuItem,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Toolbar,
  Typography,
} from '@mui/material';
import SearchIcon from '@mui/icons-material/SearchOutlined';
import AddIcon from '@mui/icons-material/PersonAddOutlined';
import PersonAddAlt1Icon from '@mui/icons-material/PersonAddAlt1Outlined';
import ChevronLeftIcon from '@mui/icons-material/ChevronLeftOutlined';
import ChevronRightIcon from '@mui/icons-material/ChevronRightOutlined';
import { useUserList } from '../hooks/useUsers';
import { UserStatusChip } from '../components/UserStatusChip';
import type { UserStatus } from '../types';
import { InviteUserDialog } from '../components/InviteUserDialog';
import { CreateUserDialog } from '../components/CreateUserDialog';

const STATUS_FILTERS: Array<{ value: '' | UserStatus; label: string }> = [
  { value: '', label: 'All statuses' },
  { value: 'ACTIVE', label: 'Active' },
  { value: 'PENDING', label: 'Pending' },
  { value: 'DISABLED', label: 'Disabled' },
  { value: 'LOCKED', label: 'Locked' },
];

const PAGE_SIZE = 20;

/**
 * The tenant's user directory.
 *
 * Search and status filter debounce into the query key, so React Query treats each distinct
 * combination as its own cache entry — paging back to a previous view is instant, and the URL
 * never needs to carry state because the query cache is the source of truth.
 */
export default function UsersListPage() {
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState<'' | UserStatus>('');
  const [page, setPage] = useState(0);
  const [inviteOpen, setInviteOpen] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);

  const { data, isLoading, error, isFetching } = useUserList({
    search: search.trim() || undefined,
    status: status || undefined,
    page,
    size: PAGE_SIZE,
  });

  const users = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;

  return (
    <Stack spacing={2.5}>
      <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={2}>
        <Box>
          <Typography variant="h1">Users</Typography>
          <Typography variant="body2" color="text.secondary">
            Manage accounts, roles and invitations in your organisation.
          </Typography>
        </Box>
        <Stack direction="row" spacing={1.5}>
          <Button variant="outlined" startIcon={<PersonAddAlt1Icon />} onClick={() => setCreateOpen(true)}>
            Create user
          </Button>
          <Button variant="contained" startIcon={<AddIcon />} onClick={() => setInviteOpen(true)}>
            Invite user
          </Button>
        </Stack>
      </Stack>

      {error ? (
        <Alert severity="error" variant="outlined">
          Could not load users. {(error as Error).message}
        </Alert>
      ) : null}

      <Card variant="outlined">
        <Toolbar disableGutters sx={{ gap: 1.5, px: 2, py: 1.5, flexWrap: 'wrap' }}>
          <TextField
            size="small"
            placeholder="Search name, email or username"
            value={search}
            onChange={(event: ChangeEvent<HTMLInputElement>) => {
              setPage(0);
              setSearch(event.target.value);
            }}
            sx={{ minWidth: 260, flexGrow: 1, maxWidth: 420 }}
            InputProps={{
              startAdornment: (
                <InputAdornment position="start">
                  <SearchIcon fontSize="small" />
                </InputAdornment>
              ),
            }}
          />
          <TextField
            select
            size="small"
            value={status}
            onChange={(event) => {
              setPage(0);
              setStatus(event.target.value as '' | UserStatus);
            }}
            sx={{ minWidth: 160 }}
            label="Status"
          >
            {STATUS_FILTERS.map((option) => (
              <MenuItem key={option.value} value={option.value}>
                {option.label}
              </MenuItem>
            ))}
          </TextField>
        </Toolbar>

        <TableContainer>
          <Table size="small" aria-label="Users">
            <TableHead>
              <TableRow>
                <TableCell>Name</TableCell>
                <TableCell>Username</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>Roles</TableCell>
                <TableCell>MFA</TableCell>
                <TableCell>Last sign-in</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {isLoading ? (
                <TableRow>
                  <TableCell colSpan={6} align="center" sx={{ py: 4 }}>
                    <Typography variant="body2" color="text.secondary">
                      Loading…
                    </Typography>
                  </TableCell>
                </TableRow>
              ) : users.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={6} align="center" sx={{ py: 4 }}>
                    <Typography variant="body2" color="text.secondary">
                      No users match your search.
                    </Typography>
                  </TableCell>
                </TableRow>
              ) : (
                users.map((user) => (
                  <TableRow
                    key={user.id}
                    hover
                    component={RouterLink}
                    to={user.id}
                    sx={{ textDecoration: 'none', cursor: 'pointer' }}
                  >
                    <TableCell>
                      <Typography variant="body2" fontWeight={600}>
                        {user.fullName}
                      </Typography>
                      <Typography variant="caption" color="text.secondary">
                        {user.email}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                        {user.username}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <UserStatusChip status={user.status} />
                    </TableCell>
                    <TableCell>
                      <Typography variant="caption" color="text.secondary">
                        {user.roles.length ? user.roles.map((r) => r.replaceAll('_', ' ')).join(', ') : '—'}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="caption" color={user.mfaEnabled ? 'success.main' : 'text.secondary'}>
                        {user.mfaEnabled ? 'On' : 'Off'}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="caption" color="text.secondary">
                        {user.lastLoginAt ? new Date(user.lastLoginAt).toLocaleString() : 'Never'}
                      </Typography>
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </TableContainer>

        <Toolbar disableGutters sx={{ justifyContent: 'flex-end', px: 2, py: 1.25, gap: 1 }}>
          <Typography variant="caption" color="text.secondary">
            {isFetching ? 'Updating…' : `Page ${page + 1} of ${Math.max(totalPages, 1)}`}
          </Typography>
          <IconButton size="small" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))} aria-label="Previous page">
            <ChevronLeftIcon fontSize="small" />
          </IconButton>
          <IconButton size="small" disabled={page >= totalPages - 1} onClick={() => setPage((p) => p + 1)} aria-label="Next page">
            <ChevronRightIcon fontSize="small" />
          </IconButton>
        </Toolbar>
      </Card>

      <InviteUserDialog open={inviteOpen} onClose={() => setInviteOpen(false)} />
      <CreateUserDialog open={createOpen} onClose={() => setCreateOpen(false)} />
    </Stack>
  );
}
