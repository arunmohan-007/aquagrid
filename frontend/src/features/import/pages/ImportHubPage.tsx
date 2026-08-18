import { useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  InputBase,
  LinearProgress,
  MenuItem,
  Select,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Toolbar,
  Typography,
} from '@mui/material';
import { alpha } from '@mui/material/styles';
import SearchIcon from '@mui/icons-material/SearchOutlined';
import ChevronIcon from '@mui/icons-material/ChevronRightOutlined';
import ChevronLeftIcon from '@mui/icons-material/ChevronLeftOutlined';
import CloseIcon from '@mui/icons-material/CloseOutlined';
import PipeIcon from '@mui/icons-material/TimelineOutlined';
import PointIcon from '@mui/icons-material/PlaceOutlined';
import FacilityIcon from '@mui/icons-material/DomainOutlined';
import BoundaryIcon from '@mui/icons-material/HexagonOutlined';
import FileIcon from '@mui/icons-material/InsertDriveFileOutlined';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { CATEGORIES, FORMATS } from '../catalogue';
import type { CategoryDef, DataTypeDef, ImportFormat } from '../catalogue';
import {
  importApi,
  type ColumnAnalysis,
  type ImportJobStatus,
  type ImportPreview,
  type ImportRunSummary,
  type TargetField,
} from '../importApi';
import { missingProjection, prepareUpload, type PreparedUpload } from '../shapefile';
import { problemMessage } from '@/lib/api/problem';

const CATEGORY_ICONS: Record<CategoryDef['icon'], ReactNode> = {
  pipe: <PipeIcon />,
  point: <PointIcon />,
  facility: <FacilityIcon />,
  boundary: <BoundaryIcon />,
};

/**
 * The Data Import Hub.
 *
 * Four columns, left to right: category, data type, format, parameters. Each choice narrows the
 * next — picking "DMA Boundary" removes CSV from the format column, because a CSV cannot carry a
 * polygon. Constraining the path is the point: bulk import is where a bad file quietly becomes a
 * thousand bad assets, and the cheapest place to stop that is before the upload.
 *
 * Shapefiles are converted to GeoJSON in the browser before upload, so the server keeps a
 * two-format ingest surface. See `shapefile.ts`.
 */
export default function ImportHubPage() {
  const [category, setCategory] = useState<CategoryDef | null>(null);
  const [dataType, setDataType] = useState<DataTypeDef | null>(null);
  const [format, setFormat] = useState<ImportFormat | null>(null);

  const pick = (next: CategoryDef) => {
    setCategory(next);
    setDataType(null);
    setFormat(null);
  };

  return (
    <Stack spacing={2.5}>
      <Stack direction="row" alignItems="baseline" spacing={2} flexWrap="wrap" useFlexGap>
        <Stack direction="row" alignItems="center" spacing={1.25}>
          <Box sx={{ width: 4, height: 26, borderRadius: 1, bgcolor: 'primary.main' }} />
          <Typography variant="h1">Data Import Hub</Typography>
        </Stack>
        <Typography variant="body2" color="text.secondary" sx={{ ml: 'auto' }}>
          Pick a category, then a data type, then a format.
        </Typography>
      </Stack>

      <Box
        sx={{
          display: 'grid',
          gap: 2,
          gridTemplateColumns: {
            xs: '1fr',
            md: 'repeat(2, minmax(0, 1fr))',
            lg: 'minmax(0, 1fr) minmax(0, 1fr) minmax(0, 0.8fr) minmax(0, 1.4fr)',
          },
          alignItems: 'start',
        }}
      >
        <StepColumn step={1} title="Data Category" searchable>
          {(filter) => (
            <Stack spacing={1}>
              {CATEGORIES.filter((c) => matches(c.title, filter)).map((c) => (
                <ChoiceRow
                  key={c.id}
                  selected={category?.id === c.id}
                  icon={CATEGORY_ICONS[c.icon]}
                  title={c.title}
                  caption={c.caption}
                  onClick={() => pick(c)}
                />
              ))}
            </Stack>
          )}
        </StepColumn>

        <StepColumn step={2} title="Data Type" searchable>
          {(filter) =>
            !category ? (
              <Hint>Choose a category first.</Hint>
            ) : (
              <Stack spacing={1}>
                {category.types
                  .filter((t) => matches(t.title, filter))
                  .map((t) => (
                    <ChoiceRow
                      key={t.id}
                      selected={dataType?.id === t.id}
                      icon={<FileIcon />}
                      title={t.title}
                      caption={t.caption}
                      onClick={() => {
                        setDataType(t);
                        setFormat(null);
                      }}
                    />
                  ))}
              </Stack>
            )
          }
        </StepColumn>

        <StepColumn step={3} title="Data Format">
          {() =>
            !dataType ? (
              <Hint>Choose a data type first.</Hint>
            ) : (
              <Stack spacing={1}>
                {dataType.formats.map((id) => (
                  <ChoiceRow
                    key={id}
                    selected={format === id}
                    icon={<FileIcon />}
                    title={FORMATS[id].title}
                    caption={FORMATS[id].caption}
                    onClick={() => setFormat(id)}
                  />
                ))}
                {dataType.geometry !== 'point' ? (
                  <Hint>
                    CSV is not offered: {dataType.geometry} geometry cannot be expressed as
                    longitude and latitude columns.
                  </Hint>
                ) : null}
              </Stack>
            )
          }
        </StepColumn>

        <StepColumn step={4} title="Import Parameters">
          {() =>
            !dataType || !format ? (
              <Hint>Choose a format to configure the import.</Hint>
            ) : (
              <ImportPanel
                key={`${dataType.id}-${format}`}
                dataType={dataType}
                format={format}
              />
            )
          }
        </StepColumn>
      </Box>

      <ImportHistoryPanel />
    </Stack>
  );
}

// --- Step scaffolding -------------------------------------------------------------------------

function StepColumn({
  step,
  title,
  searchable,
  children,
}: {
  step: number;
  title: string;
  searchable?: boolean;
  children: (filter: string) => ReactNode;
}) {
  const [filter, setFilter] = useState('');

  return (
    <Box
      sx={{
        borderRadius: 3,
        border: 1,
        borderColor: 'divider',
        bgcolor: 'background.paper',
        overflow: 'hidden',
        minHeight: 120,
      }}
    >
      <Stack
        direction="row"
        alignItems="center"
        spacing={1.25}
        sx={{ px: 1.75, py: 1.5, borderBottom: 1, borderColor: 'divider' }}
      >
        <Box
          sx={{
            width: 24,
            height: 24,
            borderRadius: 1,
            display: 'grid',
            placeItems: 'center',
            bgcolor: 'primary.main',
            color: 'primary.contrastText',
            fontSize: 12,
            fontWeight: 700,
          }}
        >
          {step}
        </Box>
        <Typography sx={{ flexGrow: 1, fontSize: 14.5, fontWeight: 700 }}>{title}</Typography>
      </Stack>

      {searchable ? (
        <Stack
          direction="row"
          alignItems="center"
          spacing={1}
          sx={{ mx: 1.75, mt: 1.75, px: 1.25, py: 0.5, borderRadius: 2, border: 1, borderColor: 'divider' }}
        >
          <SearchIcon sx={{ fontSize: 18, color: 'text.disabled' }} />
          <InputBase
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            placeholder="Search…"
            inputProps={{ 'aria-label': `Search ${title}` }}
            sx={{ fontSize: 13.5, flexGrow: 1 }}
          />
        </Stack>
      ) : null}

      <Box sx={{ p: 1.75 }}>{children(filter)}</Box>
    </Box>
  );
}

function ChoiceRow({
  selected,
  icon,
  title,
  caption,
  onClick,
}: {
  selected: boolean;
  icon: ReactNode;
  title: string;
  caption: string;
  onClick: () => void;
}) {
  return (
    <Box
      component="button"
      type="button"
      onClick={onClick}
      aria-pressed={selected}
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1.25,
        width: '100%',
        textAlign: 'left',
        cursor: 'pointer',
        px: 1.25,
        py: 1.25,
        borderRadius: 2,
        border: 1,
        borderColor: selected ? 'primary.main' : 'divider',
        bgcolor: (t) => (selected ? alpha(t.palette.primary.main, 0.1) : 'transparent'),
        color: 'text.primary',
        fontFamily: 'inherit',
        '&:hover': { bgcolor: 'action.hover' },
      }}
    >
      <Box
        aria-hidden
        sx={{
          width: 32,
          height: 32,
          borderRadius: 1.5,
          display: 'grid',
          placeItems: 'center',
          flexShrink: 0,
          bgcolor: (t) => alpha(t.palette.primary.main, selected ? 0.18 : 0.08),
          color: 'primary.main',
          '& .MuiSvgIcon-root': { fontSize: 18 },
        }}
      >
        {icon}
      </Box>
      <Box sx={{ minWidth: 0, flexGrow: 1 }}>
        <Typography sx={{ fontSize: 13.5, fontWeight: 600, color: selected ? 'primary.main' : 'inherit' }}>
          {title}
        </Typography>
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', lineHeight: 1.4 }}>
          {caption}
        </Typography>
      </Box>
      <ChevronIcon sx={{ fontSize: 18, color: 'text.disabled', flexShrink: 0 }} />
    </Box>
  );
}

function Hint({ children }: { children: ReactNode }) {
  return (
    <Typography variant="body2" color="text.secondary" sx={{ fontSize: 13 }}>
      {children}
    </Typography>
  );
}

function matches(text: string, filter: string) {
  return !filter.trim() || text.toLowerCase().includes(filter.trim().toLowerCase());
}

// --- Step 4: file, mapping, run ---------------------------------------------------------------

function ImportPanel({ dataType, format }: { dataType: DataTypeDef; format: ImportFormat }) {
  const qc = useQueryClient();
  const [file, setFile] = useState<File | null>(null);
  const [prepared, setPrepared] = useState<PreparedUpload | null>(null);
  const [analysis, setAnalysis] = useState<ColumnAnalysis | null>(null);
  const [mapping, setMapping] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [warning, setWarning] = useState<string | null>(null);
  const [jobId, setJobId] = useState<string | null>(null);
  const [status, setStatus] = useState<ImportJobStatus | null>(null);
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const poll = useRef<number | null>(null);

  useEffect(() => () => { if (poll.current) window.clearInterval(poll.current); }, []);

  /*
   * The mappable fields, from the Data Management catalogue rather than from a table in this
   * client. The server applies the layer and geometry filters — it is the one that knows a polygon
   * layer has no longitude to map, and the one that knows which attributes were created last week.
   *
   * Cached for the session: the catalogue changes when an administrator changes it, and the Data
   * Management screen invalidates this key when they do.
   */
  const { data: catalogueFields, isLoading: fieldsLoading, error: fieldsError } = useQuery({
    queryKey: ['import', 'target-fields', dataType.assetType, dataType.geometry],
    queryFn: () => importApi.targetFields(dataType.assetType, dataType.geometry),
    staleTime: 5 * 60_000,
  });

  const targetFields = useMemo<TargetField[]>(() => catalogueFields ?? [], [catalogueFields]);

  const onFile = async (picked: File | null) => {
    setFile(picked);
    setAnalysis(null);
    setMapping({});
    setError(null);
    setWarning(null);
    setPrepared(null);
    setPreview(null);
    if (!picked) return;

    setBusy(true);
    try {
      if (format === 'SHAPEFILE' && (await missingProjection(picked))) {
        setWarning(
          'No .prj found in the archive. If the data is not already in EPSG:4326 the assets will ' +
            'land in the wrong place.',
        );
      }
      const ready = await prepareUpload(picked, format);
      setPrepared(ready);
      const result = await importApi.analyze(ready.blob, ready.filename);
      setAnalysis(result);
      setMapping(autoMap(result.columns, targetFields));
    } catch (err) {
      setError(err instanceof Error ? err.message : problemMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const usedMapping = () =>
    Object.fromEntries(Object.entries(mapping).filter(([, target]) => target && target !== 'ignore'));

  /**
   * Resolves the file against existing assets first. A plain new-data import (nothing to replace,
   * no in-file duplicates) runs immediately — the confirmation only appears when there is something
   * for the operator to actually decide about.
   */
  const requestImport = async () => {
    if (!prepared) return;
    setBusy(true);
    setError(null);
    try {
      const result = await importApi.preview(prepared.blob, prepared.filename, usedMapping(), dataType.assetType);
      if (result.toReplace > 0 || result.duplicatesSkipped > 0) {
        setPreview(result);
      } else {
        await run();
      }
    } catch (err) {
      setError(problemMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const run = async () => {
    if (!prepared) return;
    setPreview(null);
    setBusy(true);
    setError(null);
    try {
      const result = await importApi.start(prepared.blob, prepared.filename, usedMapping(), dataType.assetType);
      setJobId(result.jobId);
      poll.current = window.setInterval(async () => {
        try {
          const current = await importApi.status(result.jobId);
          setStatus(current);
          if (!current.state.startsWith('RUNNING')) {
            if (poll.current) window.clearInterval(poll.current);
            qc.invalidateQueries({ queryKey: ['assets'] });
            qc.invalidateQueries({ queryKey: ['import', 'history'] });
          }
        } catch {
          if (poll.current) window.clearInterval(poll.current);
        }
      }, 1500);
    } catch (err) {
      setError(problemMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const mappedCount = Object.values(mapping).filter((t) => t && t !== 'ignore').length;
  const needsCoordinates = dataType.geometry === 'point' && format === 'CSV';
  const hasCoordinates =
    Object.values(mapping).includes('lon') && Object.values(mapping).includes('lat');
  const blocked = mappedCount === 0 || (needsCoordinates && !hasCoordinates);

  if (jobId) {
    const done = status && !status.state.startsWith('RUNNING');
    const settled = (status?.imported ?? 0) + (status?.replaced ?? 0)
      + (status?.skipped ?? 0) + (status?.failed ?? 0);
    const pct = status && status.total > 0 ? Math.round((settled / status.total) * 100) : 0;
    return (
      <Stack spacing={1.5}>
        <Typography sx={{ fontSize: 14, fontWeight: 600 }}>
          {done ? 'Import finished' : 'Importing…'}
        </Typography>
        <LinearProgress variant="determinate" value={pct} />
        <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap>
          <CountChip label="Imported" value={status?.imported ?? 0} color="success" />
          <CountChip label="Replaced" value={status?.replaced ?? 0} color="info" />
          <CountChip label="Skipped" value={status?.skipped ?? 0} color="warning" />
          <CountChip label="Failed" value={status?.failed ?? 0} color="error" />
          {status?.total ? (
            <Typography variant="caption" color="text.secondary" sx={{ alignSelf: 'center', pl: 0.5 }}>
              of {status.total} rows
            </Typography>
          ) : null}
        </Stack>
        {status?.rows?.length ? (
          <Alert severity="warning" variant="outlined" sx={{ maxHeight: 220, overflow: 'auto', p: 0 }}>
            <RowDetailList rows={status.rows} />
          </Alert>
        ) : null}
        {done ? (
          <Button variant="outlined" onClick={() => { setJobId(null); setStatus(null); onFile(null); }}>
            Import another file
          </Button>
        ) : null}
      </Stack>
    );
  }

  return (
    <Stack spacing={1.75}>
      <Box>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.5 }}>
          <Typography sx={{ fontSize: 14.5, fontWeight: 700 }}>{dataType.title}</Typography>
          <Chip label={FORMATS[format].title} size="small" variant="outlined" sx={{ height: 20, fontSize: 11 }} />
          <Chip label={dataType.geometry} size="small" variant="outlined" sx={{ height: 20, fontSize: 11 }} />
        </Stack>
        <Typography variant="body2" color="text.secondary">
          {FORMATS[format].caption}
          {format === 'SHAPEFILE' ? ' — converted to GeoJSON in your browser before upload.' : ''}
        </Typography>
      </Box>

      <Button variant="outlined" component="label" disabled={busy}>
        {file ? file.name : 'Choose file'}
        <input
          hidden
          type="file"
          accept={FORMATS[format].accept}
          onChange={(e) => onFile(e.target.files?.[0] ?? null)}
        />
      </Button>

      {busy ? (
        <Stack direction="row" spacing={1} alignItems="center">
          <CircularProgress size={16} />
          <Typography variant="body2" color="text.secondary">Reading file…</Typography>
        </Stack>
      ) : null}

      {warning ? <Alert severity="warning" variant="outlined">{warning}</Alert> : null}
      {error ? <Alert severity="error" variant="outlined">{error}</Alert> : null}
      {fieldsError ? (
        <Alert severity="error" variant="outlined">
          Could not load this layer&apos;s fields from Data Management, so there is nothing to map
          columns onto. {problemMessage(fieldsError)}
        </Alert>
      ) : null}
      {!fieldsLoading && !fieldsError && targetFields.length === 0 ? (
        <Alert severity="warning" variant="outlined">
          {dataType.title} has no active fields in Data Management. Add at least one before
          importing.
        </Alert>
      ) : null}

      {prepared?.featureCount ? (
        <Alert severity="success" variant="outlined">
          Shapefile read — {prepared.featureCount.toLocaleString()} features found.
        </Alert>
      ) : null}

      {analysis ? (
        <>
          <Typography sx={{ fontSize: 13, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.04em' }}>
            Map columns
          </Typography>
          <Stack spacing={1}>
            {analysis.columns.map((column) => (
              <Stack key={column} direction="row" spacing={1} alignItems="center">
                <Box sx={{ minWidth: 0, flex: '1 1 40%' }}>
                  <Typography noWrap sx={{ fontSize: 13, fontWeight: 600 }}>{column}</Typography>
                  <Typography noWrap variant="caption" color="text.secondary">
                    {analysis.sampleRows[0]?.[column] ?? '—'}
                  </Typography>
                </Box>
                <Select
                  size="small"
                  value={mapping[column] ?? 'ignore'}
                  onChange={(e) => setMapping((m) => ({ ...m, [column]: e.target.value }))}
                  sx={{ flex: '1 1 60%', fontSize: 13 }}
                  inputProps={{ 'aria-label': `Target field for ${column}` }}
                >
                  {/* "Ignore" is the client's own option, not a catalogue field: dropping a column
                      is a decision about this import, not a property of the layer. */}
                  <MenuItem value="ignore" sx={{ fontSize: 13 }}>Ignore this column</MenuItem>
                  {targetFields.map((f) => (
                    <MenuItem key={f.fieldName} value={f.fieldName} sx={{ fontSize: 13 }}>
                      {f.displayName}
                      {f.mandatory ? ' *' : ''}
                    </MenuItem>
                  ))}
                </Select>
              </Stack>
            ))}
          </Stack>

          {needsCoordinates && !hasCoordinates ? (
            <Alert severity="info" variant="outlined">
              A CSV of points needs both a longitude and a latitude column mapped.
            </Alert>
          ) : null}

          <Button variant="contained" disabled={busy || blocked} onClick={requestImport}>
            Import {mappedCount} mapped column{mappedCount === 1 ? '' : 's'}
          </Button>
        </>
      ) : null}

      {preview ? (
        <ImportConfirmDialog
          preview={preview}
          busy={busy}
          onReject={() => setPreview(null)}
          onConfirm={run}
        />
      ) : null}
    </Stack>
  );
}

/**
 * Stands between "preview resolved" and "anything gets written". Rejecting cancels the import
 * outright — the operator fixes the file or the mapping and starts over, rather than the wizard
 * guessing which rows they actually meant to keep.
 */
function ImportConfirmDialog({
  preview,
  busy,
  onReject,
  onConfirm,
}: {
  preview: ImportPreview;
  busy: boolean;
  onReject: () => void;
  onConfirm: () => void;
}) {
  return (
    <Dialog open onClose={busy ? undefined : onReject} maxWidth="xs" fullWidth>
      <DialogTitle>Confirm this import</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={1.5}>
          {preview.toReplace > 0 ? (
            <Alert severity="warning" variant="outlined">
              {preview.toReplace} row{preview.toReplace === 1 ? '' : 's'} already exist{preview.toReplace === 1 ? 's' : ''} on
              file and will be <strong>replaced</strong> with the values in this file.
            </Alert>
          ) : null}
          {preview.duplicatesSkipped > 0 ? (
            <Alert severity="info" variant="outlined">
              {preview.duplicatesSkipped} row{preview.duplicatesSkipped === 1 ? '' : 's'} repeat{preview.duplicatesSkipped === 1 ? 's' : ''} an
              asset code or duplicate-check value already used earlier in this file and will be
              skipped.
            </Alert>
          ) : null}
          <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap>
            <CountChip label="New" value={preview.toCreate} color="success" />
            <CountChip label="Replaced" value={preview.toReplace} color="info" />
            <CountChip label="Skipped (duplicate)" value={preview.duplicatesSkipped} color="warning" />
            <Typography variant="caption" color="text.secondary" sx={{ alignSelf: 'center', pl: 0.5 }}>
              of {preview.totalRows} rows
            </Typography>
          </Stack>
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 2 }}>
        <Button onClick={onReject} disabled={busy}>Reject</Button>
        <Button variant="contained" onClick={onConfirm} disabled={busy}>
          {preview.toReplace > 0 ? 'Replace and import' : 'Import'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function CountChip({
  label,
  value,
  color,
}: {
  label: string;
  value: number;
  color: 'success' | 'info' | 'warning' | 'error';
}) {
  return (
    <Chip
      size="small"
      variant={value > 0 ? 'filled' : 'outlined'}
      color={value > 0 ? color : 'default'}
      label={`${value} ${label}`}
      sx={{ fontSize: 12, fontWeight: 600 }}
    />
  );
}

const OUTCOME_COLOR: Record<string, 'info' | 'warning' | 'error'> = {
  REPLACED: 'info',
  SKIPPED: 'warning',
  FAILED: 'error',
};

/**
 * Per-row detail for the rows worth explaining — a plain new-row import has nothing to say beyond
 * the imported count, so only replaced/skipped/failed rows appear here.
 */
function RowDetailList({ rows }: { rows: { row: number; outcome: string; message: string }[] }) {
  return (
    <Stack spacing={0} divider={<Box sx={{ borderBottom: 1, borderColor: 'divider' }} />}>
      {rows.map((r) => (
        <Stack key={`${r.row}-${r.outcome}`} direction="row" spacing={1} alignItems="baseline" sx={{ px: 1, py: 0.5 }}>
          <Typography variant="caption" sx={{ minWidth: 44, fontWeight: 700 }}>
            Row {r.row}
          </Typography>
          <Chip
            size="small"
            color={OUTCOME_COLOR[r.outcome] ?? 'default'}
            label={r.outcome}
            sx={{ height: 18, fontSize: 10.5 }}
          />
          <Typography variant="caption" color="text.secondary">{r.message}</Typography>
        </Stack>
      ))}
    </Stack>
  );
}

const HISTORY_PAGE_SIZE = 10;

/**
 * Past import runs, persisted server-side — still here after the tab that started an import is
 * long closed. Each row is the run's counts; "View detail" pulls the per-row outcomes for the
 * rows that were replaced, skipped or failed.
 */
function ImportHistoryPanel() {
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<ImportRunSummary | null>(null);

  const { data, isLoading, isFetching, error } = useQuery({
    queryKey: ['import', 'history', page],
    queryFn: () => importApi.history(page, HISTORY_PAGE_SIZE),
  });

  const runs = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;

  return (
    <Card variant="outlined">
      <Toolbar disableGutters sx={{ px: 1.75, py: 1.25, borderBottom: 1, borderColor: 'divider', gap: 1 }}>
        <Typography sx={{ fontSize: 14.5, fontWeight: 700, flexGrow: 1 }}>Import History</Typography>
        <Typography variant="caption" color="text.secondary">
          {isFetching ? 'Updating…' : `Page ${page + 1} of ${Math.max(totalPages, 1)}`}
        </Typography>
        <IconButton size="small" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))} aria-label="Previous page">
          <ChevronLeftIcon fontSize="small" />
        </IconButton>
        <IconButton size="small" disabled={page >= totalPages - 1} onClick={() => setPage((p) => p + 1)} aria-label="Next page">
          <ChevronIcon fontSize="small" />
        </IconButton>
      </Toolbar>

      {error ? (
        <Alert severity="error" variant="outlined" sx={{ m: 1.75 }}>
          Could not load import history. {problemMessage(error)}
        </Alert>
      ) : null}

      {!isLoading && !error && runs.length === 0 ? (
        <Typography variant="body2" color="text.secondary" sx={{ p: 1.75 }}>
          No imports yet.
        </Typography>
      ) : null}

      {runs.length > 0 ? (
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>File</TableCell>
                <TableCell>Type</TableCell>
                <TableCell>Started</TableCell>
                <TableCell>By</TableCell>
                <TableCell>Status</TableCell>
                <TableCell align="right">Total</TableCell>
                <TableCell align="right">Imported</TableCell>
                <TableCell align="right">Replaced</TableCell>
                <TableCell align="right">Skipped</TableCell>
                <TableCell align="right">Failed</TableCell>
                <TableCell />
              </TableRow>
            </TableHead>
            <TableBody>
              {runs.map((run) => (
                <TableRow key={run.id} hover>
                  <TableCell sx={{ maxWidth: 220 }}>
                    <Typography noWrap variant="body2">{run.fileName ?? '—'}</Typography>
                  </TableCell>
                  <TableCell>
                    <Typography variant="body2">{run.assetType}</Typography>
                    <Typography variant="caption" color="text.secondary">{run.format}</Typography>
                  </TableCell>
                  <TableCell>
                    <Typography variant="body2">{new Date(run.startedAt).toLocaleString()}</Typography>
                  </TableCell>
                  <TableCell>
                    <Typography variant="body2">{run.actorUsername ?? '—'}</Typography>
                  </TableCell>
                  <TableCell>
                    <Chip
                      size="small"
                      label={run.state}
                      color={run.state === 'COMPLETED' ? 'success' : run.state === 'FAILED' ? 'error' : 'default'}
                      sx={{ height: 20, fontSize: 11 }}
                    />
                  </TableCell>
                  <TableCell align="right">{run.total}</TableCell>
                  <TableCell align="right">{run.imported}</TableCell>
                  <TableCell align="right">{run.replaced}</TableCell>
                  <TableCell align="right">{run.skipped}</TableCell>
                  <TableCell align="right">{run.failed}</TableCell>
                  <TableCell align="right">
                    <Button size="small" onClick={() => setSelected(run)}>Details</Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      ) : null}

      {selected ? <RunDetailDialog run={selected} onClose={() => setSelected(null)} /> : null}
    </Card>
  );
}

function RunDetailDialog({
  run,
  onClose,
}: {
  run: ImportRunSummary;
  onClose: () => void;
}) {
  const { data, isLoading, error } = useQuery({
    queryKey: ['import', 'history', 'detail', run.id],
    queryFn: () => importApi.historyDetail(run.id),
  });

  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        <Box sx={{ flexGrow: 1, minWidth: 0 }}>
          <Typography noWrap sx={{ fontSize: 15, fontWeight: 700 }}>{run.fileName ?? 'Import'}</Typography>
          <Typography variant="caption" color="text.secondary">
            {new Date(run.startedAt).toLocaleString()} · {run.actorUsername ?? 'unknown'}
          </Typography>
        </Box>
        <IconButton size="small" onClick={onClose} aria-label="Close">
          <CloseIcon fontSize="small" />
        </IconButton>
      </DialogTitle>
      <DialogContent dividers>
        <Stack spacing={1.5}>
          <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap>
            <CountChip label="Imported" value={run.imported} color="success" />
            <CountChip label="Replaced" value={run.replaced} color="info" />
            <CountChip label="Skipped" value={run.skipped} color="warning" />
            <CountChip label="Failed" value={run.failed} color="error" />
            <Typography variant="caption" color="text.secondary" sx={{ alignSelf: 'center', pl: 0.5 }}>
              of {run.total} rows
            </Typography>
          </Stack>
          {run.errorMessage ? (
            <Alert severity="error" variant="outlined">{run.errorMessage}</Alert>
          ) : null}
          {isLoading ? (
            <Stack direction="row" spacing={1} alignItems="center">
              <CircularProgress size={16} />
              <Typography variant="body2" color="text.secondary">Loading row detail…</Typography>
            </Stack>
          ) : null}
          {error ? (
            <Alert severity="error" variant="outlined">Could not load row detail. {problemMessage(error)}</Alert>
          ) : null}
          {data?.rows?.length ? (
            <Box sx={{ maxHeight: 320, overflow: 'auto', border: 1, borderColor: 'divider', borderRadius: 1 }}>
              <RowDetailList rows={data.rows} />
            </Box>
          ) : !isLoading ? (
            <Typography variant="body2" color="text.secondary">
              Every row was imported as a new asset — nothing to detail beyond the count above.
            </Typography>
          ) : null}
        </Stack>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Pre-selects the obvious matches so a well-formed file needs no manual mapping.
 *
 * This suggests, it does not decide — every choice stays visible and changeable in the dropdowns.
 * That is the difference between a helpful default and the silent mis-mapping the two-phase flow
 * exists to prevent.
 *
 * The alias dictionary that used to live here is gone: each field now arrives from the catalogue
 * carrying its own aliases, so a field created this morning is auto-matched by its own name and
 * label without this file being taught about it. What is left is the matching rule — compare on
 * letters and digits only, first field wins, one field per column.
 */
function autoMap(columns: string[], targets: TargetField[]): Record<string, string> {
  const normalise = (value: string) => value.toLowerCase().replace(/[^a-z0-9]/g, '');
  const result: Record<string, string> = {};
  const taken = new Set<string>();

  for (const column of columns) {
    const key = normalise(column);
    const hit = targets.find(
      (target) =>
        !taken.has(target.fieldName) &&
        target.aliases.some((alias) => normalise(alias) === key),
    );
    result[column] = hit?.fieldName ?? 'ignore';
    if (hit) taken.add(hit.fieldName);
  }
  return result;
}
