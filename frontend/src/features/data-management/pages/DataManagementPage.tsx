import { useMemo, useState } from 'react';
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
  TableSortLabel,
  TextField,
  Toolbar,
  Tooltip,
  Typography,
} from '@mui/material';
import { alpha } from '@mui/material/styles';
import AddIcon from '@mui/icons-material/AddOutlined';
import EditIcon from '@mui/icons-material/EditOutlined';
import RetireIcon from '@mui/icons-material/BlockOutlined';
import RestoreIcon from '@mui/icons-material/RestoreOutlined';
import RefreshIcon from '@mui/icons-material/RefreshOutlined';
import SearchIcon from '@mui/icons-material/SearchOutlined';
import ExportIcon from '@mui/icons-material/FileDownloadOutlined';
import HistoryIcon from '@mui/icons-material/HistoryOutlined';
import ChevronLeftIcon from '@mui/icons-material/ChevronLeftOutlined';
import ChevronRightIcon from '@mui/icons-material/ChevronRightOutlined';
import LayersIcon from '@mui/icons-material/LayersOutlined';
import { useAuth } from '@/lib/auth/AuthProvider';
import { problemMessage } from '@/lib/api/problem';
import { dataManagementApi, downloadBlob } from '../api/dataManagementApi';
import { useAttributes, useDataTypes, useLayers } from '../hooks/useDataManagement';
import { useResizableColumns } from '../hooks/useResizableColumns';
import { COLUMNS, formatTimestamp, lockReason, shortActor } from '../labels';
import { AttributeFormDialog } from '../components/AttributeFormDialog';
import { AttributeHistoryDialog } from '../components/AttributeHistoryDialog';
import { RetireAttributeDialog } from '../components/RetireAttributeDialog';
import type { AttributeQuery, LayerAttribute, MetadataLayer } from '../types';

const PAGE_SIZE = 25;

/** The tri-state filters. Undefined is "do not filter", which is not the same as false. */
const TRISTATE = [
  { value: '', label: 'Any' },
  { value: 'true', label: 'Yes' },
  { value: 'false', label: 'No' },
];

function triValue(raw: string): boolean | undefined {
  return raw === '' ? undefined : raw === 'true';
}

/**
 * Data Management — the master catalogue of every layer and every field on it.
 *
 * This is the screen that makes the platform's field definitions data rather than code. Import,
 * export and the dynamic forms to come read from the same rows this grid edits, so adding
 * `consumer_no` to Water Meters here puts it in the import mapper and the export in the same
 * minute, with no release in either tier.
 *
 * Layout is a layer rail beside the grid rather than a layer dropdown above it. Choosing a layer is
 * not a filter among several — it is the question the whole screen answers, and the rail also
 * carries each layer's field count, which is how an administrator sees at a glance which layers
 * have been curated and which are still running on the seeded defaults.
 *
 * Retired attributes are shown, dimmed, rather than hidden. Deactivation is this module's only
 * delete and it removes nothing; a grid that hid its own soft deletes would give no way to tell
 * "never existed" from "retired last March", and no way to bring one back.
 */
export default function DataManagementPage() {
  const { hasPermission } = useAuth();
  const canManage = hasPermission('gis:metadata:manage');

  const { data: layers, isLoading: layersLoading, error: layersError } = useLayers();
  const { data: dataTypes } = useDataTypes();

  const [layerId, setLayerId] = useState<string | undefined>();
  const [search, setSearch] = useState('');
  const [dataType, setDataType] = useState('');
  const [mandatory, setMandatory] = useState('');
  const [editable, setEditable] = useState('');
  const [active, setActive] = useState('');
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState('sortOrder');
  const [direction, setDirection] = useState<'ASC' | 'DESC'>('ASC');

  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<LayerAttribute | null>(null);
  const [retiring, setRetiring] = useState<LayerAttribute | null>(null);
  const [historyFor, setHistoryFor] = useState<LayerAttribute | null>(null);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);

  const query: AttributeQuery = useMemo(
    () => ({
      layerId,
      search: search.trim() || undefined,
      dataType: dataType || undefined,
      mandatory: triValue(mandatory),
      editable: triValue(editable),
      active: triValue(active),
      page,
      size: PAGE_SIZE,
      sort,
      direction,
    }),
    [layerId, search, dataType, mandatory, editable, active, page, sort, direction],
  );

  const { data, isLoading, error, isFetching, refetch } = useAttributes(query);
  const attributes = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const { widths, beginResize } = useResizableColumns(COLUMNS);

  const selectedLayer = layers?.find((layer) => layer.id === layerId);

  const resetPage = <T,>(setter: (value: T) => void) => (value: T) => {
    setPage(0);
    setter(value);
  };

  const toggleSort = (column: string) => {
    setPage(0);
    if (sort === column) {
      setDirection((current) => (current === 'ASC' ? 'DESC' : 'ASC'));
    } else {
      setSort(column);
      setDirection('ASC');
    }
  };

  const runExport = async () => {
    setExporting(true);
    setExportError(null);
    try {
      // The same filters the grid is showing, so "export this" means what is on screen rather than
      // the whole catalogue — which is the difference between a data dictionary for one layer and
      // a two-hundred-row file nobody reads.
      const blob = await dataManagementApi.exportCatalogue({
        layerId,
        search: search.trim() || undefined,
        dataType: dataType || undefined,
        mandatory: triValue(mandatory),
        editable: triValue(editable),
        active: triValue(active),
      });
      const name = selectedLayer ? selectedLayer.code : 'all-layers';
      downloadBlob(blob, `attribute-catalogue-${name}.csv`);
    } catch (cause) {
      setExportError(problemMessage(cause, 'Could not export the catalogue.'));
    } finally {
      setExporting(false);
    }
  };

  const openNew = () => {
    setEditing(null);
    setFormOpen(true);
  };
  const openEdit = (attribute: LayerAttribute) => {
    setEditing(attribute);
    setFormOpen(true);
  };

  return (
    <Stack spacing={2.5}>
      <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={2}>
        <Box>
          <Stack direction="row" alignItems="center" spacing={1.25}>
            <Box sx={{ width: 4, height: 26, borderRadius: 1, bgcolor: 'primary.main' }} />
            <Typography variant="h1">Data Management</Typography>
          </Stack>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
            The master catalogue of every layer and every field on it. Import, export and asset forms
            all read these definitions — a field added here needs no release.
          </Typography>
        </Box>
        {canManage ? (
          <Button variant="contained" startIcon={<AddIcon />} onClick={openNew} disabled={!layers?.length}>
            Add attribute
          </Button>
        ) : null}
      </Stack>

      {layersError ? (
        <Alert severity="error" variant="outlined">
          Could not load the layer catalogue. {(layersError as Error).message}
        </Alert>
      ) : null}
      {error ? (
        <Alert severity="error" variant="outlined">
          Could not load attributes. {(error as Error).message}
        </Alert>
      ) : null}
      {exportError ? (
        <Alert severity="error" variant="outlined" onClose={() => setExportError(null)}>
          {exportError}
        </Alert>
      ) : null}

      <Box
        sx={{
          display: 'grid',
          gap: 2,
          gridTemplateColumns: { xs: '1fr', lg: 'minmax(220px, 260px) minmax(0, 1fr)' },
          alignItems: 'start',
        }}
      >
        <LayerRail
          layers={layers ?? []}
          loading={layersLoading}
          selectedId={layerId}
          onSelect={(id) => {
            setPage(0);
            setLayerId(id);
          }}
        />

        <Card variant="outlined" sx={{ minWidth: 0 }}>
          <Toolbar disableGutters sx={{ gap: 1.5, px: 2, py: 1.5, flexWrap: 'wrap' }}>
            <TextField
              size="small"
              placeholder="Search field name, label or description"
              value={search}
              onChange={(e) => resetPage(setSearch)(e.target.value)}
              sx={{ minWidth: 240, flexGrow: 1, maxWidth: 340 }}
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
              label="Data type"
              value={dataType}
              onChange={(e) => resetPage(setDataType)(e.target.value)}
              sx={{ minWidth: 150 }}
            >
              <MenuItem value="">All types</MenuItem>
              {(dataTypes ?? []).map((type) => (
                <MenuItem key={type.code} value={type.code}>
                  {type.label}
                </MenuItem>
              ))}
            </TextField>
            <TriFilter label="Mandatory" value={mandatory} onChange={resetPage(setMandatory)} />
            <TriFilter label="Editable" value={editable} onChange={resetPage(setEditable)} />
            <TriFilter label="Active" value={active} onChange={resetPage(setActive)} />

            <Box sx={{ flexGrow: 1 }} />
            <Tooltip title="Refresh">
              <span>
                <IconButton size="small" onClick={() => refetch()} disabled={isFetching} aria-label="Refresh">
                  <RefreshIcon fontSize="small" />
                </IconButton>
              </span>
            </Tooltip>
            <Button
              size="small"
              variant="outlined"
              startIcon={<ExportIcon />}
              onClick={runExport}
              disabled={exporting}
            >
              Export
            </Button>
          </Toolbar>

          <TableContainer sx={{ maxHeight: 640 }}>
            <Table size="small" stickyHeader sx={{ tableLayout: 'fixed', width: 'max-content' }}
                   aria-label="Layer attributes">
              <TableHead>
                <TableRow>
                  {COLUMNS.map((column) => (
                    <TableCell
                      key={column.id}
                      align={column.align ?? 'left'}
                      sx={{
                        width: widths[column.id],
                        minWidth: widths[column.id],
                        maxWidth: widths[column.id],
                        position: 'relative',
                        whiteSpace: 'nowrap',
                        userSelect: 'none',
                      }}
                    >
                      {column.sort ? (
                        <TableSortLabel
                          active={sort === column.sort}
                          direction={sort === column.sort && direction === 'DESC' ? 'desc' : 'asc'}
                          onClick={() => toggleSort(column.sort!)}
                        >
                          {column.label}
                        </TableSortLabel>
                      ) : (
                        column.label
                      )}
                      {/* The resize handle. Sits inside the header cell but its listeners live on
                          the window, so a fast drag cannot outrun it. */}
                      <Box
                        role="separator"
                        aria-orientation="vertical"
                        aria-label={`Resize ${column.label}`}
                        onMouseDown={(event) => beginResize(column.id, event)}
                        sx={{
                          position: 'absolute',
                          top: 0,
                          right: 0,
                          height: '100%',
                          width: 8,
                          cursor: 'col-resize',
                          '&:hover': {
                            bgcolor: (t) => alpha(t.palette.primary.main, 0.25),
                          },
                        }}
                      />
                    </TableCell>
                  ))}
                  <TableCell sx={{ width: 130, minWidth: 130, whiteSpace: 'nowrap' }} align="right">
                    Actions
                  </TableCell>
                </TableRow>
              </TableHead>

              <TableBody>
                {isLoading ? (
                  <MessageRow>Loading…</MessageRow>
                ) : attributes.length === 0 ? (
                  <MessageRow>
                    {selectedLayer
                      ? `No attributes on ${selectedLayer.title} match these filters.`
                      : 'No attributes match these filters.'}
                  </MessageRow>
                ) : (
                  attributes.map((attribute) => (
                    <AttributeRow
                      key={attribute.id}
                      attribute={attribute}
                      widths={widths}
                      canManage={canManage}
                      onEdit={() => openEdit(attribute)}
                      onRetire={() => setRetiring(attribute)}
                      onHistory={() => setHistoryFor(attribute)}
                    />
                  ))
                )}
              </TableBody>
            </Table>
          </TableContainer>

          <Toolbar disableGutters sx={{ justifyContent: 'flex-end', px: 2, py: 1.25, gap: 1 }}>
            <Typography variant="caption" color="text.secondary">
              {isFetching
                ? 'Updating…'
                : `${data?.totalElements ?? 0} field${data?.totalElements === 1 ? '' : 's'} · page ${page + 1} of ${Math.max(totalPages, 1)}`}
            </Typography>
            <IconButton
              size="small"
              disabled={page === 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              aria-label="Previous page"
            >
              <ChevronLeftIcon fontSize="small" />
            </IconButton>
            <IconButton
              size="small"
              disabled={page >= totalPages - 1}
              onClick={() => setPage((p) => p + 1)}
              aria-label="Next page"
            >
              <ChevronRightIcon fontSize="small" />
            </IconButton>
          </Toolbar>
        </Card>
      </Box>

      <AttributeFormDialog
        open={formOpen}
        onClose={() => setFormOpen(false)}
        layers={layers ?? []}
        defaultLayerId={layerId}
        editing={editing}
      />
      <RetireAttributeDialog attribute={retiring} onClose={() => setRetiring(null)} />
      <AttributeHistoryDialog attribute={historyFor} onClose={() => setHistoryFor(null)} />
    </Stack>
  );
}

// --- Layer rail ------------------------------------------------------------------------------

function LayerRail({
  layers,
  loading,
  selectedId,
  onSelect,
}: {
  layers: MetadataLayer[];
  loading: boolean;
  selectedId?: string | undefined;
  onSelect: (id: string | undefined) => void;
}) {
  return (
    <Card variant="outlined" sx={{ overflow: 'hidden' }}>
      <Stack
        direction="row"
        alignItems="center"
        spacing={1}
        sx={{ px: 1.75, py: 1.5, borderBottom: 1, borderColor: 'divider' }}
      >
        <LayersIcon sx={{ fontSize: 18, color: 'primary.main' }} />
        <Typography sx={{ fontSize: 14, fontWeight: 700 }}>Layers</Typography>
      </Stack>
      <Stack sx={{ p: 1, maxHeight: 640, overflowY: 'auto' }} spacing={0.5}>
        <RailRow
          title="All layers"
          caption={loading ? 'Loading…' : `${layers.length} in the catalogue`}
          selected={!selectedId}
          onClick={() => onSelect(undefined)}
        />
        {layers.map((layer) => (
          <RailRow
            key={layer.id}
            title={layer.title}
            caption={`${layer.attributeCount} field${layer.attributeCount === 1 ? '' : 's'}`}
            selected={selectedId === layer.id}
            onClick={() => onSelect(layer.id)}
          />
        ))}
      </Stack>
    </Card>
  );
}

function RailRow({
  title,
  caption,
  selected,
  onClick,
}: {
  title: string;
  caption: string;
  selected: boolean;
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
        justifyContent: 'space-between',
        gap: 1,
        width: '100%',
        textAlign: 'left',
        cursor: 'pointer',
        px: 1.25,
        py: 0.9,
        borderRadius: 1.5,
        border: 1,
        borderColor: selected ? 'primary.main' : 'transparent',
        bgcolor: (t) => (selected ? alpha(t.palette.primary.main, 0.1) : 'transparent'),
        color: 'text.primary',
        fontFamily: 'inherit',
        '&:hover': { bgcolor: 'action.hover' },
      }}
    >
      <Typography
        noWrap
        sx={{ fontSize: 13.5, fontWeight: 600, color: selected ? 'primary.main' : 'inherit' }}
      >
        {title}
      </Typography>
      <Typography variant="caption" color="text.secondary" sx={{ flexShrink: 0 }}>
        {caption}
      </Typography>
    </Box>
  );
}

// --- Grid rows -------------------------------------------------------------------------------

function AttributeRow({
  attribute,
  widths,
  canManage,
  onEdit,
  onRetire,
  onHistory,
}: {
  attribute: LayerAttribute;
  widths: Record<string, number>;
  canManage: boolean;
  onEdit: () => void;
  onRetire: () => void;
  onHistory: () => void;
}) {
  const locked = lockReason(attribute);
  const isTypeTable = attribute.storage === 'TYPE_TABLE';
  // A TYPE_TABLE field whose value a user may set (e.g. a pipe's Diameter) still has an editable
  // catalogue definition — display name, description, sample, the editable/visible flags. Only a
  // computed one (e.g. Length (m), derived from the geometry) has nothing here worth changing.
  const editLocked = isTypeTable && !attribute.editable;

  const cells: Record<string, React.ReactNode> = {
    fieldName: (
      <Stack direction="row" spacing={0.75} alignItems="center" sx={{ minWidth: 0 }}>
        <Typography variant="body2" noWrap sx={{ fontFamily: 'monospace' }}>
          {attribute.fieldName}
        </Typography>
        {attribute.system ? (
          <Tooltip title={locked ?? ''}>
            <Chip label="System" size="small" variant="outlined" sx={{ height: 17, fontSize: 10 }} />
          </Tooltip>
        ) : null}
      </Stack>
    ),
    displayName: attribute.displayName,
    description: attribute.description ?? '—',
    dataType: (
      <Typography variant="caption" color="text.secondary" noWrap>
        {attribute.dataType.replace(/_/g, ' ')}
      </Typography>
    ),
    maxLength: attribute.maxLength ?? '—',
    numericPrecision: attribute.numericPrecision ?? '—',
    numericScale: attribute.numericScale ?? '—',
    defaultValue: attribute.defaultValue ?? '—',
    sampleValue: attribute.sampleValue ?? '—',
    mandatory: <YesNo value={attribute.mandatory} />,
    unique: <YesNo value={attribute.unique} />,
    duplicateCheck: <YesNo value={attribute.duplicateCheck} />,
    editable: <YesNo value={attribute.editable} />,
    visible: <YesNo value={attribute.visible} />,
    active: <YesNo value={attribute.active} />,
    createdBy: shortActor(attribute.createdBy),
    createdAt: formatTimestamp(attribute.createdAt),
    modifiedBy: shortActor(attribute.modifiedBy),
    modifiedAt: formatTimestamp(attribute.modifiedAt),
  };

  return (
    <TableRow
      hover
      /* Retired fields stay in the grid, dimmed. Hiding them would leave no way to tell a field
         that never existed from one retired last March — and no way to bring it back. */
      sx={{ opacity: attribute.active ? 1 : 0.5 }}
    >
      {COLUMNS.map((column) => (
        <TableCell
          key={column.id}
          align={column.align ?? 'left'}
          sx={{
            width: widths[column.id],
            minWidth: widths[column.id],
            maxWidth: widths[column.id],
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
          title={typeof cells[column.id] === 'string' ? String(cells[column.id]) : undefined}
        >
          {cells[column.id]}
        </TableCell>
      ))}
      <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
        <Tooltip title="History">
          <IconButton size="small" onClick={onHistory} aria-label={`History for ${attribute.fieldName}`}>
            <HistoryIcon fontSize="small" />
          </IconButton>
        </Tooltip>
        {canManage ? (
          <>
            <Tooltip title={editLocked ? (locked ?? '') : 'Edit'}>
              <span>
                <IconButton
                  size="small"
                  onClick={onEdit}
                  disabled={editLocked}
                  aria-label={`Edit ${attribute.fieldName}`}
                >
                  <EditIcon fontSize="small" />
                </IconButton>
              </span>
            </Tooltip>
            <Tooltip
              title={
                attribute.system || isTypeTable
                  ? (locked ?? '')
                  : attribute.active
                    ? 'Retire — nothing is deleted'
                    : 'Return to service'
              }
            >
              <span>
                <IconButton
                  size="small"
                  onClick={onRetire}
                  disabled={attribute.system || isTypeTable}
                  aria-label={
                    attribute.active
                      ? `Retire ${attribute.fieldName}`
                      : `Return ${attribute.fieldName} to service`
                  }
                >
                  {attribute.active ? (
                    <RetireIcon fontSize="small" />
                  ) : (
                    <RestoreIcon fontSize="small" />
                  )}
                </IconButton>
              </span>
            </Tooltip>
          </>
        ) : null}
      </TableCell>
    </TableRow>
  );
}

function YesNo({ value }: { value: boolean }) {
  return (
    <Typography variant="caption" color={value ? 'text.primary' : 'text.disabled'}>
      {value ? 'Yes' : 'No'}
    </Typography>
  );
}

function MessageRow({ children }: { children: React.ReactNode }) {
  return (
    <TableRow>
      <TableCell colSpan={COLUMNS.length + 1} align="center" sx={{ py: 4 }}>
        <Typography variant="body2" color="text.secondary">
          {children}
        </Typography>
      </TableCell>
    </TableRow>
  );
}

function TriFilter({
  label,
  value,
  onChange,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <TextField
      select
      size="small"
      label={label}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      sx={{ minWidth: 108 }}
    >
      {TRISTATE.map((option) => (
        <MenuItem key={option.value} value={option.value}>
          {option.label}
        </MenuItem>
      ))}
    </TextField>
  );
}
