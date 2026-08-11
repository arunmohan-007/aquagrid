import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  MenuItem,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import DataObjectIcon from '@mui/icons-material/DataObjectOutlined';
import {
  useDiscoveredParameters,
  useDiscoverySamples,
  useIgnoreDiscovery,
  useRestoreDiscovery,
} from '../hooks/useDeviceDataConfig';
import { DISCOVERED_COLUMNS, DISCOVERY_STATUS_LABELS, formatTime, humanise } from '../labels';
import { RawPayloadDialog } from './RawPayloadDialog';
import type { DiscoveredParameter, DiscoveryStatus } from '../types';

/**
 * Parameters devices have sent that the catalogue does not describe.
 *
 * This screen is what makes "accept everything" actionable rather than merely tolerant. Storing an
 * unknown field means nothing is lost; it does not mean anyone finds out — an unconfigured parameter
 * is on no dashboard, in no report and outside every alarm rule, which from the operator's chair is
 * indistinguishable from a field the device never sent.
 *
 * Two things the wording here works hard at. **Ignore deletes nothing**, and the screen says so
 * where the button is, because an action called "Ignore" in a data platform reads like a delete and
 * would otherwise be avoided by exactly the people who should use it. And the occurrence count is
 * shown prominently, because "seen 4 times" and "seen 40,000 times" call for very different
 * decisions about the same field.
 */

interface Props {
  deviceId?: string | undefined;
  deviceType?: string | undefined;
  canManage: boolean;
  onConfigure: (discovery: DiscoveredParameter) => void;
}

export function DiscoveredParametersPanel({
  deviceId,
  deviceType,
  canManage,
  onConfigure,
}: Props) {
  const [status, setStatus] = useState<DiscoveryStatus | ''>('PENDING');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const [samplesFor, setSamplesFor] = useState<DiscoveredParameter | null>(null);

  const query = {
    deviceId,
    deviceType,
    status: status || undefined,
    search: search.trim() || undefined,
    page,
    size,
  };
  const { data, isLoading } = useDiscoveredParameters(query);
  const { data: samples = [], isLoading: samplesLoading } = useDiscoverySamples(samplesFor?.id);
  const ignore = useIgnoreDiscovery();
  const restore = useRestoreDiscovery();

  const rows = data?.content ?? [];

  return (
    <Stack spacing={2}>
      <Alert severity="info" icon={<DataObjectIcon fontSize="small" />}>
        Every field below was received and <strong>is already stored</strong> in full. Configuring
        one does not recover it — nothing was lost — it decides what the platform does with it from
        now on, and makes the history already collected readable.
      </Alert>

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
        <TextField
          select
          size="small"
          label="Status"
          value={status}
          sx={{ minWidth: 200 }}
          onChange={(event) => {
            setStatus(event.target.value as DiscoveryStatus | '');
            setPage(0);
          }}
        >
          <MenuItem value="">All</MenuItem>
          {(Object.keys(DISCOVERY_STATUS_LABELS) as DiscoveryStatus[]).map((value) => (
            <MenuItem key={value} value={value}>
              {DISCOVERY_STATUS_LABELS[value]}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          size="small"
          label="Search parameter or device"
          value={search}
          sx={{ minWidth: 260 }}
          onChange={(event) => {
            setSearch(event.target.value);
            setPage(0);
          }}
        />
      </Stack>

      <TableContainer component={Paper} variant="outlined">
        <Table size="small" stickyHeader>
          <TableHead>
            <TableRow>
              {DISCOVERED_COLUMNS.map((column) => (
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
              <TableRow key={row.id} hover>
                <TableCell>
                  <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                    {row.parameterName}
                  </Typography>
                </TableCell>
                <TableCell>{row.deviceCode ?? '—'}</TableCell>
                <TableCell>{humanise(row.deviceType)}</TableCell>
                <TableCell>
                  <Typography variant="body2" sx={{ fontFamily: 'monospace' }} noWrap>
                    {row.sampleValue ?? '—'}
                  </Typography>
                </TableCell>
                <TableCell>
                  <Tooltip title="Inferred from an observed value. A starting point, not a decision.">
                    <Chip size="small" variant="outlined" label={humanise(row.detectedDataType)} />
                  </Tooltip>
                </TableCell>
                <TableCell>{formatTime(row.firstSeenAt)}</TableCell>
                <TableCell>{formatTime(row.lastSeenAt)}</TableCell>
                <TableCell align="right">{row.occurrences.toLocaleString()}</TableCell>
                <TableCell>
                  <Chip
                    size="small"
                    variant={row.status === 'PENDING' ? 'filled' : 'outlined'}
                    color={row.status === 'CONFIGURED' ? 'primary' : 'default'}
                    label={DISCOVERY_STATUS_LABELS[row.status]}
                  />
                </TableCell>
                <TableCell align="right">
                  <Stack direction="row" spacing={0.5} justifyContent="flex-end">
                    <Button size="small" onClick={() => setSamplesFor(row)}>
                      Raw data
                    </Button>
                    {canManage && row.status === 'PENDING' ? (
                      <>
                        <Button size="small" variant="outlined" onClick={() => onConfigure(row)}>
                          Configure
                        </Button>
                        <Tooltip title="Removes it from this list. The field keeps arriving and keeps being stored.">
                          <Button
                            size="small"
                            color="inherit"
                            onClick={() => ignore.mutate({ id: row.id })}
                          >
                            Ignore
                          </Button>
                        </Tooltip>
                      </>
                    ) : null}
                    {canManage && row.status === 'IGNORED' ? (
                      <Button size="small" onClick={() => restore.mutate(row.id)}>
                        Restore
                      </Button>
                    ) : null}
                  </Stack>
                </TableCell>
              </TableRow>
            ))}

            {!isLoading && rows.length === 0 ? (
              <TableRow>
                <TableCell colSpan={DISCOVERED_COLUMNS.length}>
                  <Box sx={{ py: 4, textAlign: 'center' }}>
                    <Typography variant="body2" color="text.secondary">
                      {status === 'PENDING'
                        ? 'Nothing undescribed. Every field the devices are sending has a definition.'
                        : 'No discovered parameters match these filters.'}
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

      <RawPayloadDialog
        open={Boolean(samplesFor)}
        onClose={() => setSamplesFor(null)}
        title={`Payloads carrying ${samplesFor?.parameterName ?? ''}`}
        payloads={samples}
        highlightKey={samplesFor?.parameterName}
        loading={samplesLoading}
      />
    </Stack>
  );
}
