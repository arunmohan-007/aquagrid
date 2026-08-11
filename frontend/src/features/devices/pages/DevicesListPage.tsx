import { useState, type ChangeEvent } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  Chip,
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
import AddIcon from '@mui/icons-material/AddOutlined';
import ChevronLeftIcon from '@mui/icons-material/ChevronLeftOutlined';
import ChevronRightIcon from '@mui/icons-material/ChevronRightOutlined';
import { useDeviceList } from '../hooks/useDevices';
import { DeviceStatusChip } from '../components/DeviceStatusChip';
import { DeviceFormDialog } from '../components/DeviceFormDialog';
import type { CommunicationType, Device, DeviceProtocol, DeviceSource, DeviceType } from '../types';
import {
  COMMUNICATION_TYPE_LABELS,
  DEVICE_PROTOCOL_LABELS,
  DEVICE_PROTOCOLS,
  DEVICE_SOURCES,
  DEVICE_SOURCE_LABELS,
  DEVICE_TYPES,
  DEVICE_TYPE_LABELS,
} from '../labels';

const TYPE_FILTERS: Array<{ value: '' | DeviceType; label: string }> = [
  { value: '', label: 'All types' },
  ...DEVICE_TYPES.map((value) => ({ value, label: DEVICE_TYPE_LABELS[value] })),
];

const COMM_FILTERS: Array<{ value: '' | CommunicationType; label: string }> = [
  { value: '', label: 'All networks' },
  ...(Object.keys(COMMUNICATION_TYPE_LABELS) as CommunicationType[])
    .map((value) => ({ value, label: COMMUNICATION_TYPE_LABELS[value] })),
];

const SOURCE_FILTERS: Array<{ value: '' | DeviceSource; label: string }> = [
  { value: '', label: 'All sources' },
  ...DEVICE_SOURCES.map((value) => ({ value, label: DEVICE_SOURCE_LABELS[value] })),
];

const PROTOCOL_FILTERS: Array<{ value: '' | DeviceProtocol; label: string }> = [
  { value: '', label: 'All protocols' },
  ...DEVICE_PROTOCOLS.map((value) => ({ value, label: DEVICE_PROTOCOL_LABELS[value] })),
];

/**
 * The device registry.
 *
 * The Network column shows the device's address alongside its technology, because "which devices
 * are on NB-IoT and what IMEI is each one at" is the question an operator chasing a connectivity
 * fault actually asks. The address is derived server-side from whichever field the technology uses
 * to identify a device, so one column answers it for every transport.
 *
 * Source is marked on the row rather than given a column: all but a handful of devices are real,
 * and a column that reads "Live Device" four hundred times to flag six is noise that trains the eye
 * to skip exactly the thing it exists to show. The badge appears only on the exception — and it
 * appears next to the name, because mistaking synthetic readings for measurements is the error the
 * registry has to make hard.
 */
export default function DevicesListPage() {
  const [search, setSearch] = useState('');
  const [type, setType] = useState<'' | DeviceType>('');
  const [transport, setTransport] = useState<'' | CommunicationType>('');
  const [source, setSource] = useState<'' | DeviceSource>('');
  const [protocol, setProtocol] = useState<'' | DeviceProtocol>('');
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<Device | null>(null);
  const [formOpen, setFormOpen] = useState(false);

  const { data, isLoading, error, isFetching } = useDeviceList(
    search, type || undefined, transport || undefined, source || undefined,
    protocol || undefined, page,
  );
  const devices = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;

  const openNew = () => {
    setEditing(null);
    setFormOpen(true);
  };
  const openEdit = (device: Device) => {
    setEditing(device);
    setFormOpen(true);
  };

  return (
    <Stack spacing={2.5}>
      <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={2}>
        <Box>
          <Typography variant="h1">Devices</Typography>
          <Typography variant="body2" color="text.secondary">
            The device registry — every meter, sensor and controller reporting to the platform.
          </Typography>
        </Box>
        <Button variant="contained" startIcon={<AddIcon />} onClick={openNew}>
          Register device
        </Button>
      </Stack>

      {error ? (
        <Alert severity="error" variant="outlined">
          Could not load devices. {(error as Error).message}
        </Alert>
      ) : null}

      <Card variant="outlined">
        <Toolbar disableGutters sx={{ gap: 1.5, px: 2, py: 1.5, flexWrap: 'wrap' }}>
          <TextField
            size="small"
            placeholder="Search ID, name, serial or address"
            value={search}
            onChange={(e: ChangeEvent<HTMLInputElement>) => {
              setPage(0);
              setSearch(e.target.value);
            }}
            sx={{ minWidth: 240, flexGrow: 1, maxWidth: 380 }}
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
            value={type}
            onChange={(e) => {
              setPage(0);
              setType(e.target.value as '' | DeviceType);
            }}
            sx={{ minWidth: 160 }}
            label="Type"
          >
            {TYPE_FILTERS.map((option) => (
              <MenuItem key={option.value} value={option.value}>{option.label}</MenuItem>
            ))}
          </TextField>
          <TextField
            select
            size="small"
            value={transport}
            onChange={(e) => {
              setPage(0);
              setTransport(e.target.value as '' | CommunicationType);
            }}
            sx={{ minWidth: 160 }}
            label="Network"
          >
            {COMM_FILTERS.map((option) => (
              <MenuItem key={option.value} value={option.value}>{option.label}</MenuItem>
            ))}
          </TextField>
          <TextField
            select
            size="small"
            value={protocol}
            onChange={(e) => {
              setPage(0);
              setProtocol(e.target.value as '' | DeviceProtocol);
            }}
            sx={{ minWidth: 140 }}
            label="Protocol"
          >
            {PROTOCOL_FILTERS.map((option) => (
              <MenuItem key={option.value} value={option.value}>{option.label}</MenuItem>
            ))}
          </TextField>
          <TextField
            select
            size="small"
            value={source}
            onChange={(e) => {
              setPage(0);
              setSource(e.target.value as '' | DeviceSource);
            }}
            sx={{ minWidth: 160 }}
            label="Source"
          >
            {SOURCE_FILTERS.map((option) => (
              <MenuItem key={option.value} value={option.value}>{option.label}</MenuItem>
            ))}
          </TextField>
        </Toolbar>

        <TableContainer>
          <Table size="small" aria-label="Devices">
            <TableHead>
              <TableRow>
                <TableCell>Device ID</TableCell>
                <TableCell>Name</TableCell>
                <TableCell>Type</TableCell>
                <TableCell>Network</TableCell>
                <TableCell>Protocol</TableCell>
                <TableCell>Asset</TableCell>
                <TableCell>Status</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {isLoading ? (
                <TableRow>
                  <TableCell colSpan={7} align="center" sx={{ py: 4 }}>
                    <Typography variant="body2" color="text.secondary">Loading…</Typography>
                  </TableCell>
                </TableRow>
              ) : devices.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={7} align="center" sx={{ py: 4 }}>
                    <Typography variant="body2" color="text.secondary">
                      No devices match your search.
                    </Typography>
                  </TableCell>
                </TableRow>
              ) : (
                devices.map((device) => (
                  <TableRow
                    key={device.id}
                    hover
                    onClick={() => openEdit(device)}
                    sx={{ cursor: 'pointer' }}
                  >
                    <TableCell>
                      <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                        {device.deviceCode}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Stack direction="row" spacing={1} alignItems="center">
                        <span>{device.name}</span>
                        {device.deviceSource === 'SIMULATOR' ? (
                          <Chip
                            label="Simulated"
                            size="small"
                            variant="outlined"
                            sx={{ height: 18, fontSize: 10 }}
                          />
                        ) : null}
                        {device.deviceSource === 'API_TEST' ? (
                          <Chip
                            label="API test"
                            size="small"
                            variant="outlined"
                            sx={{ height: 18, fontSize: 10 }}
                          />
                        ) : null}
                      </Stack>
                    </TableCell>
                    <TableCell>
                      <Typography variant="caption" color="text.secondary">
                        {DEVICE_TYPE_LABELS[device.deviceType] ?? device.deviceType}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="caption" color="text.secondary">
                        {COMMUNICATION_TYPE_LABELS[device.communicationType]
                          ?? device.communicationType}
                      </Typography>
                      {device.networkAddress ? (
                        <Typography variant="caption" display="block" sx={{ fontFamily: 'monospace' }}>
                          {device.networkAddress}
                        </Typography>
                      ) : null}
                    </TableCell>
                    <TableCell>
                      <Typography variant="caption" color="text.secondary">
                        {DEVICE_PROTOCOL_LABELS[device.protocol] ?? device.protocol}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="caption" color="text.secondary">
                        {device.assetNumber ?? '—'}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <DeviceStatusChip status={device.status} />
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

      <DeviceFormDialog open={formOpen} onClose={() => setFormOpen(false)} editing={editing} />
    </Stack>
  );
}
