import { useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  InputBase,
  LinearProgress,
  MenuItem,
  Select,
  Stack,
  Typography,
} from '@mui/material';
import { alpha } from '@mui/material/styles';
import SearchIcon from '@mui/icons-material/SearchOutlined';
import ChevronIcon from '@mui/icons-material/ChevronRightOutlined';
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

  const run = async () => {
    if (!prepared) return;
    setBusy(true);
    setError(null);
    try {
      const used = Object.fromEntries(
        Object.entries(mapping).filter(([, target]) => target && target !== 'ignore'),
      );
      const result = await importApi.start(prepared.blob, prepared.filename, used, dataType.assetType);
      setJobId(result.jobId);
      poll.current = window.setInterval(async () => {
        try {
          const current = await importApi.status(result.jobId);
          setStatus(current);
          if (!current.state.startsWith('RUNNING')) {
            if (poll.current) window.clearInterval(poll.current);
            qc.invalidateQueries({ queryKey: ['assets'] });
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
    const pct = status && status.total > 0
      ? Math.round(((status.imported + status.failed) / status.total) * 100)
      : 0;
    return (
      <Stack spacing={1.5}>
        <Typography sx={{ fontSize: 14, fontWeight: 600 }}>
          {done ? 'Import finished' : 'Importing…'}
        </Typography>
        <LinearProgress variant="determinate" value={pct} />
        <Typography variant="body2" color="text.secondary">
          {status?.imported ?? 0} imported · {status?.failed ?? 0} failed
          {status?.total ? ` of ${status.total}` : ''}
        </Typography>
        {status?.errors?.length ? (
          <Alert severity="warning" variant="outlined" sx={{ maxHeight: 180, overflow: 'auto' }}>
            {status.errors.slice(0, 20).map((e, i) => (
              /* A frozen slice of an append-only list, never reordered or filtered — and the same
                 message can legitimately repeat, so the text alone would not be a key. */
              // eslint-disable-next-line react/no-array-index-key
              <Typography key={i} variant="caption" display="block">{e}</Typography>
            ))}
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

          <Button variant="contained" disabled={busy || blocked} onClick={run}>
            Import {mappedCount} mapped column{mappedCount === 1 ? '' : 's'}
          </Button>
        </>
      ) : null}
    </Stack>
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
