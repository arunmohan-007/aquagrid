import { useMemo, useState } from 'react';
import {
  Alert,
  Badge,
  Box,
  Button,
  Chip,
  MenuItem,
  Paper,
  Stack,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  Tabs,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/AddOutlined';
import CheckIcon from '@mui/icons-material/CheckOutlined';
import RemoveIcon from '@mui/icons-material/RemoveOutlined';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useDevicePicker } from '@/features/devices/hooks/useDevices';
import type { DeviceType } from '@/features/devices/types';
import {
  useCategories,
  useCreateParameter,
  useDataTypes,
  useDeviceTypeSummaries,
  useParameters,
  usePendingDiscoveryCount,
  useSetParameterActive,
  useUpdateParameter,
} from '../hooks/useDeviceDataConfig';
import { PARAMETER_COLUMNS, humanise } from '../labels';
import { ParameterFormDialog } from '../components/ParameterFormDialog';
import { DiscoveredParametersPanel } from '../components/DiscoveredParametersPanel';
import { toProblem } from '@/lib/api/problem';
import type {
  DeviceParameter,
  DiscoveredParameter,
  ParameterScope,
} from '../types';

/**
 * Device Data Configuration.
 *
 * The screen for saying what a registered device's readings <em>mean</em> — their units, their
 * plausible ranges, and whether each one reaches a dashboard, an alarm or a report. It deliberately
 * does not register devices: that is the Device Registry's job, and this page selects from what is
 * already there.
 *
 * Two tabs, and the pairing is the design. The catalogue tab is what an administrator has said; the
 * discovered tab is what the devices are actually sending that nobody has said anything about. Read
 * apart, the first looks complete and the second looks like a defect list. Read together they are
 * the two halves of one question — and putting the pending count on the tab is what stops the second
 * half going unread, which is the failure mode that would make "we keep everything" worthless.
 */
export default function DeviceDataConfigPage() {
  const { hasPermission } = useAuth();
  const canManage = hasPermission('iot:data-config:manage');

  const [tab, setTab] = useState<'catalogue' | 'discovered'>('catalogue');

  // Filters
  const [scope, setScope] = useState<ParameterScope | ''>('');
  const [deviceType, setDeviceType] = useState('');
  const [deviceId, setDeviceId] = useState('');
  const [search, setSearch] = useState('');
  const [dataType, setDataType] = useState('');
  const [category, setCategory] = useState('');
  const [active, setActive] = useState<'' | 'true' | 'false'>('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);

  // Dialog
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<DeviceParameter | null>(null);
  const [fromDiscovery, setFromDiscovery] = useState<DiscoveredParameter | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  const { data: deviceTypes = [] } = useDeviceTypeSummaries();
  const { data: dataTypes = [] } = useDataTypes();
  const { data: categories = [] } = useCategories();
  const { data: pending } = usePendingDiscoveryCount();
  // The device picker reads the existing register rather than listing devices of its own. A second
  // device list here would be the drift this module is built to avoid, one level up. It narrows
  // with the device-type filter, which is also what keeps the picker's bound from biting.
  const { data: devicePage } = useDevicePicker((deviceType || undefined) as DeviceType | undefined);

  const query = {
    scope: scope || undefined,
    deviceType: deviceType || undefined,
    deviceId: deviceId || undefined,
    search: search.trim() || undefined,
    dataType: dataType || undefined,
    category: category || undefined,
    active: active === '' ? undefined : active === 'true',
    page,
    size,
  };
  const { data, isLoading } = useParameters(query);
  const createParameter = useCreateParameter();
  const updateParameter = useUpdateParameter();
  const setActiveState = useSetParameterActive();

  const deviceTypeOptions = useMemo(
    () => deviceTypes.map((type) => ({ value: type.value, label: type.label })),
    [deviceTypes],
  );

  const rows = data?.content ?? [];
  const devices = devicePage?.content ?? [];

  const openCreate = () => {
    setEditing(null);
    setFromDiscovery(null);
    setFormError(null);
    setFormOpen(true);
  };

  const openEdit = (parameter: DeviceParameter) => {
    setEditing(parameter);
    setFromDiscovery(null);
    setFormError(null);
    setFormOpen(true);
  };

  const openFromDiscovery = (discovery: DiscoveredParameter) => {
    setEditing(null);
    setFromDiscovery(discovery);
    setFormError(null);
    setFormOpen(true);
    setTab('catalogue');
  };

  /*
   * The server answers an unconfirmed breaking change with a sentence describing exactly what it
   * will do. Surfacing that verbatim is the point: a generic "Are you sure?" tells an operator
   * nothing about whether historical readings are rewritten, which is the thing they need to know.
   */
  const handleCreate = async (payload: Parameters<typeof createParameter.mutateAsync>[0]) => {
    setFormError(null);
    try {
      return await createParameter.mutateAsync(payload);
    } catch (error) {
      setFormError(toProblem(error).detail ?? 'Could not save the parameter.');
      throw error;
    }
  };

  const handleUpdate = async (
    id: string,
    payload: Parameters<typeof updateParameter.mutateAsync>[0]['payload'],
  ) => {
    setFormError(null);
    try {
      return await updateParameter.mutateAsync({ id, payload });
    } catch (error) {
      setFormError(toProblem(error).detail ?? 'Could not save the parameter.');
      throw error;
    }
  };

  return (
    <Stack spacing={2.5} sx={{ p: { xs: 2, md: 3 } }}>
      <Stack direction="row" justifyContent="space-between" alignItems="flex-start" flexWrap="wrap">
        <Box>
          <Typography variant="h2">Device Data Configuration</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 720, mt: 0.5 }}>
            What each device is expected to send, and what the platform does with it. Configuration
            decides how data is <em>used</em> — never whether it is accepted: every parameter a
            device sends is stored in full, configured or not.
          </Typography>
        </Box>
        {canManage && tab === 'catalogue' ? (
          <Button variant="contained" startIcon={<AddIcon />} onClick={openCreate}>
            Add parameter
          </Button>
        ) : null}
      </Stack>

      <Tabs value={tab} onChange={(_event, next) => setTab(next)}>
        <Tab value="catalogue" label="Configured parameters" />
        <Tab
          value="discovered"
          label={
            <Badge
              color="primary"
              badgeContent={pending?.pending ?? 0}
              max={999}
              sx={{ '& .MuiBadge-badge': { right: -16, top: 2 } }}
            >
              Discovered
            </Badge>
          }
        />
      </Tabs>

      {tab === 'discovered' ? (
        <DiscoveredParametersPanel
          deviceId={deviceId || undefined}
          deviceType={deviceType || undefined}
          canManage={canManage}
          onConfigure={openFromDiscovery}
        />
      ) : (
        <>
          {/* ---- Filters ---- */}
          <Stack direction="row" spacing={1.5} flexWrap="wrap" useFlexGap>
            <TextField
              select
              size="small"
              label="Device type"
              value={deviceType}
              sx={{ minWidth: 210 }}
              onChange={(event) => {
                setDeviceType(event.target.value);
                setPage(0);
              }}
            >
              <MenuItem value="">All device types</MenuItem>
              {deviceTypes.map((type) => (
                <MenuItem key={type.value} value={type.value}>
                  {type.label}
                  <Typography variant="caption" color="text.secondary" sx={{ ml: 1 }}>
                    {type.activeParameters} param · {type.deviceCount} device
                    {type.deviceCount === 1 ? '' : 's'}
                  </Typography>
                </MenuItem>
              ))}
            </TextField>

            <TextField
              select
              size="small"
              label="Device"
              value={deviceId}
              sx={{ minWidth: 230 }}
              onChange={(event) => {
                setDeviceId(event.target.value);
                setPage(0);
              }}
              helperText={
                deviceId ? 'Showing inherited template plus this device’s overrides' : undefined
              }
            >
              <MenuItem value="">All devices</MenuItem>
              {devices.map((device) => (
                <MenuItem key={device.id} value={device.id}>
                  {device.deviceCode} — {device.name}
                </MenuItem>
              ))}
            </TextField>

            <TextField
              select
              size="small"
              label="Scope"
              value={scope}
              sx={{ minWidth: 160 }}
              onChange={(event) => {
                setScope(event.target.value as ParameterScope | '');
                setPage(0);
              }}
            >
              <MenuItem value="">Any</MenuItem>
              <MenuItem value="DEVICE_TYPE">Device type template</MenuItem>
              <MenuItem value="DEVICE">Device override</MenuItem>
            </TextField>

            <TextField
              select
              size="small"
              label="Data type"
              value={dataType}
              sx={{ minWidth: 160 }}
              onChange={(event) => {
                setDataType(event.target.value);
                setPage(0);
              }}
            >
              <MenuItem value="">Any</MenuItem>
              {dataTypes.map((type) => (
                <MenuItem key={type.value} value={type.value}>
                  {type.label}
                </MenuItem>
              ))}
            </TextField>

            <TextField
              select
              size="small"
              label="Category"
              value={category}
              sx={{ minWidth: 170 }}
              onChange={(event) => {
                setCategory(event.target.value);
                setPage(0);
              }}
            >
              <MenuItem value="">Any</MenuItem>
              {categories.map((item) => (
                <MenuItem key={item.value} value={item.value}>
                  {item.label}
                </MenuItem>
              ))}
            </TextField>

            <TextField
              select
              size="small"
              label="Status"
              value={active}
              sx={{ minWidth: 140 }}
              onChange={(event) => {
                setActive(event.target.value as '' | 'true' | 'false');
                setPage(0);
              }}
            >
              <MenuItem value="">All</MenuItem>
              <MenuItem value="true">Active</MenuItem>
              <MenuItem value="false">Retired</MenuItem>
            </TextField>

            <TextField
              size="small"
              label="Search"
              value={search}
              sx={{ minWidth: 220 }}
              onChange={(event) => {
                setSearch(event.target.value);
                setPage(0);
              }}
            />
          </Stack>

          {formError ? <Alert severity="warning">{formError}</Alert> : null}

          {/* ---- Grid ---- */}
          <TableContainer component={Paper} variant="outlined">
            <Table size="small" stickyHeader>
              <TableHead>
                <TableRow>
                  {PARAMETER_COLUMNS.map((column) => (
                    <TableCell
                      key={column.id}
                      align={column.align ?? 'left'}
                      sx={{ width: column.width, whiteSpace: 'nowrap' }}
                    >
                      {column.label}
                    </TableCell>
                  ))}
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((row) => (
                  <TableRow key={row.id} hover sx={{ opacity: row.active ? 1 : 0.55 }}>
                    <TableCell>
                      <Stack direction="row" spacing={1} alignItems="center">
                        <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                          {row.parameterName}
                        </Typography>
                        {row.scope === 'DEVICE' ? (
                          <Tooltip title="Overrides this device's type template">
                            <Chip size="small" label="Override" variant="outlined" />
                          </Tooltip>
                        ) : null}
                        {row.payloadKey !== row.parameterName ? (
                          <Tooltip title={`Matched in the payload as "${row.payloadKey}"`}>
                            <Chip size="small" label={row.payloadKey} variant="outlined" />
                          </Tooltip>
                        ) : null}
                      </Stack>
                    </TableCell>
                    <TableCell>{row.displayName}</TableCell>
                    <TableCell>{humanise(row.dataType)}</TableCell>
                    <TableCell>{row.unit ?? '—'}</TableCell>
                    <TableCell>{humanise(row.category)}</TableCell>
                    <TableCell align="right">{formatRange(row)}</TableCell>
                    <TableCell align="center">
                      <BoolMark on={row.mandatory} />
                    </TableCell>
                    <TableCell align="center">
                      <BoolMark on={row.dashboardVisible} />
                    </TableCell>
                    <TableCell align="center">
                      <BoolMark on={row.useForAlarm} />
                    </TableCell>
                    <TableCell align="center">
                      <BoolMark on={row.useForReports} />
                    </TableCell>
                    <TableCell>
                      <Chip
                        size="small"
                        variant={row.active ? 'filled' : 'outlined'}
                        label={row.active ? 'Active' : 'Retired'}
                      />
                    </TableCell>
                    <TableCell align="right">
                      {canManage ? (
                        <Stack direction="row" spacing={0.5} justifyContent="flex-end">
                          <Button size="small" onClick={() => openEdit(row)}>
                            Edit
                          </Button>
                          <Tooltip
                            title={
                              row.active
                                ? 'Stops it being validated, charted and reported. The device keeps sending it and the platform keeps storing it.'
                                : 'Returns it to service. The readings stored while it was retired come back with it.'
                            }
                          >
                            <Button
                              size="small"
                              color="inherit"
                              onClick={() =>
                                setActiveState.mutate({ id: row.id, active: !row.active })
                              }
                            >
                              {row.active ? 'Retire' : 'Restore'}
                            </Button>
                          </Tooltip>
                        </Stack>
                      ) : null}
                    </TableCell>
                  </TableRow>
                ))}

                {!isLoading && rows.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={PARAMETER_COLUMNS.length}>
                      <Box sx={{ py: 5, textAlign: 'center' }}>
                        <Typography variant="body2" color="text.secondary">
                          No parameters configured for this selection.
                        </Typography>
                        <Typography variant="caption" color="text.secondary">
                          Readings are still being received and stored — they are simply recorded
                          without a unit or a verdict. The Discovered tab lists what is arriving.
                        </Typography>
                      </Box>
                    </TableCell>
                  </TableRow>
                ) : null}
              </TableBody>
            </Table>
          </TableContainer>

          <TablePagination
            component="div"
            count={data?.totalElements ?? 0}
            page={page}
            rowsPerPage={size}
            rowsPerPageOptions={[10, 25, 50, 100]}
            onPageChange={(_event, next) => setPage(next)}
            onRowsPerPageChange={(event) => {
              setSize(Number(event.target.value));
              setPage(0);
            }}
          />
        </>
      )}

      <ParameterFormDialog
        open={formOpen}
        onClose={() => setFormOpen(false)}
        parameter={editing}
        fromDiscovery={fromDiscovery}
        defaultScope={deviceId ? 'DEVICE' : 'DEVICE_TYPE'}
        defaultDeviceType={deviceType || undefined}
        defaultDeviceId={deviceId || undefined}
        deviceTypeOptions={deviceTypeOptions}
        onCreate={handleCreate}
        onUpdate={handleUpdate}
        error={formError}
      />
    </Stack>
  );
}

/** A tick or a dash. A checkbox here would look editable in a row that is not. */
function BoolMark({ on }: { on: boolean }) {
  return on ? (
    <CheckIcon fontSize="small" color="primary" />
  ) : (
    <RemoveIcon fontSize="small" sx={{ opacity: 0.35 }} />
  );
}

function formatRange(row: DeviceParameter): string {
  if (row.minValue == null && row.maxValue == null) return '—';
  const min = row.minValue == null ? '−∞' : String(row.minValue);
  const max = row.maxValue == null ? '∞' : String(row.maxValue);
  return `${min} … ${max}`;
}
