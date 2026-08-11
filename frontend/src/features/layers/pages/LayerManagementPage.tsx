import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
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
  Tooltip,
  Typography,
} from '@mui/material';
import { alpha } from '@mui/material/styles';
import AddIcon from '@mui/icons-material/AddOutlined';
import EditIcon from '@mui/icons-material/EditOutlined';
import SchemaIcon from '@mui/icons-material/SchemaOutlined';
import PaletteIcon from '@mui/icons-material/PaletteOutlined';
import PreviewIcon from '@mui/icons-material/MapOutlined';
import ImportIcon from '@mui/icons-material/UploadFileOutlined';
import ExportIcon from '@mui/icons-material/FileDownloadOutlined';
import EnableIcon from '@mui/icons-material/PlayArrowOutlined';
import DisableIcon from '@mui/icons-material/PauseOutlined';
import ArchiveIcon from '@mui/icons-material/Inventory2Outlined';
import RefreshIcon from '@mui/icons-material/RefreshOutlined';
import SearchIcon from '@mui/icons-material/SearchOutlined';
import { useAuth } from '@/lib/auth/AuthProvider';
import { problemMessage } from '@/lib/api/problem';
import {
  useChangeLayerStatus,
  useGisLayers,
  useLayerCategories,
  useGeometryTypes,
} from '../hooks/useLayers';
import { LayerFormDialog } from '../components/LayerFormDialog';
import { LayerPreviewDialog } from '../components/LayerPreviewDialog';
import {
  FAMILY_LABELS,
  LAYER_COLUMNS,
  STATUS_HINTS,
  STATUS_LABELS,
  formatCount,
  systemLayerReason,
} from '../labels';
import type { GisLayer, LayerStatus } from '../types';

const STATUS_COLOURS: Record<LayerStatus, 'success' | 'warning' | 'default'> = {
  ACTIVE: 'success',
  INACTIVE: 'warning',
  ARCHIVED: 'default',
};

/**
 * Layer Management — the master registry of every GIS layer.
 *
 * The screen answers three questions an administrator actually has: what layers exist, what is in
 * them, and what each one is allowed to do. Feature count and extent are on the grid rather than
 * behind a click because "did that import land" is the reason this page gets opened, and both are
 * computed in PostGIS from indexed aggregates so a layer with two million service connections costs
 * what one with four tanks costs.
 *
 * There is no attribute editing here and there will not be. **Manage attributes** opens the existing
 * Data Management module for the selected layer; fields are its job, before and after this module
 * existed, and a second field editor is exactly the drift the platform has already paid to remove
 * once.
 *
 * Archived layers are hidden unless asked for. Every state is reversible and none of them deletes a
 * feature — but an archived layer is a decision someone made to retire, and listing it beside live
 * ones is how it gets re-enabled by accident.
 */
export default function LayerManagementPage() {
  const { hasPermission } = useAuth();
  const canManage = hasPermission('gis:layer:manage');
  const canStyle = hasPermission('gis:style:read');
  const canManageFields = hasPermission('gis:metadata:read');
  const navigate = useNavigate();

  const [status, setStatus] = useState<LayerStatus | ''>('');
  const [category, setCategory] = useState('');
  const [geometryType, setGeometryType] = useState('');
  const [search, setSearch] = useState('');
  const [editing, setEditing] = useState<GisLayer | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [previewing, setPreviewing] = useState<GisLayer | null>(null);

  const query = useMemo(
    () => ({
      status: status || undefined,
      category: category || undefined,
      geometryType: geometryType || undefined,
      search: search.trim() || undefined,
      withCounts: true,
    }),
    [status, category, geometryType, search],
  );

  const { data: layers, isLoading, error, refetch, isFetching } = useGisLayers(query);
  const { data: categories } = useLayerCategories();
  const { data: geometryTypes } = useGeometryTypes();
  const changeStatus = useChangeLayerStatus();

  const openCreate = () => {
    setEditing(null);
    setFormOpen(true);
  };

  const openEdit = (layer: GisLayer) => {
    setEditing(layer);
    setFormOpen(true);
  };

  return (
    <Stack spacing={2.5}>
      <Stack direction="row" alignItems="flex-start" justifyContent="space-between" spacing={2}>
        <Box>
          <Typography variant="h5" sx={{ fontWeight: 700 }}>
            Layer Management
          </Typography>
          <Typography variant="body2" sx={{ opacity: 0.75, mt: 0.5, maxWidth: 720 }}>
            The registry of every spatial layer the platform draws, catalogues, imports to and
            exports from. Fields belong to Data Management and appearance to Layer Styles; this is
            the layer itself.
          </Typography>
        </Box>
        <Stack direction="row" spacing={1}>
          <Tooltip title="Refresh">
            <span>
              <IconButton onClick={() => refetch()} disabled={isFetching}>
                <RefreshIcon />
              </IconButton>
            </span>
          </Tooltip>
          {canManage ? (
            <Button variant="contained" startIcon={<AddIcon />} onClick={openCreate}>
              Create layer
            </Button>
          ) : null}
        </Stack>
      </Stack>

      {changeStatus.error ? (
        <Alert severity="error" onClose={() => changeStatus.reset()}>
          {problemMessage(changeStatus.error)}
        </Alert>
      ) : null}

      <Card variant="outlined">
        <Toolbar sx={{ gap: 1.5, flexWrap: 'wrap', py: 1.5 }}>
          <TextField
            size="small"
            placeholder="Search layers"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            sx={{ minWidth: 220 }}
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
            label="Status"
            value={status}
            onChange={(e) => setStatus(e.target.value as LayerStatus | '')}
            sx={{ minWidth: 160 }}
            helperText={status === '' ? 'Archived hidden' : undefined}
          >
            <MenuItem value="">Active and disabled</MenuItem>
            <MenuItem value="ACTIVE">Active</MenuItem>
            <MenuItem value="INACTIVE">Disabled</MenuItem>
            <MenuItem value="ARCHIVED">Archived</MenuItem>
          </TextField>
          <TextField
            select
            size="small"
            label="Category"
            value={category}
            onChange={(e) => setCategory(e.target.value)}
            sx={{ minWidth: 160 }}
          >
            <MenuItem value="">All</MenuItem>
            {(categories ?? []).map((c) => (
              <MenuItem key={c} value={c}>
                {c}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            select
            size="small"
            label="Geometry"
            value={geometryType}
            onChange={(e) => setGeometryType(e.target.value)}
            sx={{ minWidth: 170 }}
          >
            <MenuItem value="">All</MenuItem>
            {(geometryTypes ?? []).map((t) => (
              <MenuItem key={t.value} value={t.value}>
                {t.label}
              </MenuItem>
            ))}
          </TextField>
        </Toolbar>

        {error ? (
          <Alert severity="error" sx={{ m: 2 }}>
            {problemMessage(error)}
          </Alert>
        ) : null}

        <TableContainer>
          <Table size="small" stickyHeader>
            <TableHead>
              <TableRow>
                {LAYER_COLUMNS.map((column) => (
                  <TableCell
                    key={column.id}
                    align={column.align ?? 'left'}
                    sx={{ width: column.width, fontWeight: 700, whiteSpace: 'nowrap' }}
                  >
                    {column.label}
                  </TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {isLoading ? (
                <TableRow>
                  <TableCell colSpan={LAYER_COLUMNS.length} sx={{ py: 4, textAlign: 'center' }}>
                    Loading the registry…
                  </TableCell>
                </TableRow>
              ) : null}

              {!isLoading && (layers ?? []).length === 0 ? (
                <TableRow>
                  <TableCell colSpan={LAYER_COLUMNS.length} sx={{ py: 4, textAlign: 'center' }}>
                    No layers match these filters.
                  </TableCell>
                </TableRow>
              ) : null}

              {(layers ?? []).map((layer) => (
                <TableRow
                  key={layer.id}
                  hover
                  sx={{
                    // Archived and disabled layers are dimmed rather than hidden: withdrawal never
                    // deletes, and a registry that hides its own soft deletes gives no way to tell
                    // "never existed" from "retired last March".
                    opacity: layer.status === 'ACTIVE' ? 1 : 0.6,
                  }}
                >
                  <TableCell>
                    <Stack direction="row" alignItems="center" spacing={1}>
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>
                        {layer.title}
                      </Typography>
                      {layer.system ? (
                        <Tooltip title={systemLayerReason(true) ?? ''}>
                          <Chip label="System" size="small" variant="outlined" />
                        </Tooltip>
                      ) : null}
                    </Stack>
                    {layer.description ? (
                      <Typography variant="caption" sx={{ opacity: 0.7 }}>
                        {layer.description}
                      </Typography>
                    ) : null}
                  </TableCell>
                  <TableCell>
                    <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                      {layer.code}
                    </Typography>
                  </TableCell>
                  <TableCell>{layer.category ?? '—'}</TableCell>
                  <TableCell>
                    <Stack spacing={0.25}>
                      <Typography variant="body2">{layer.geometryType}</Typography>
                      <Typography variant="caption" sx={{ opacity: 0.7 }}>
                        {FAMILY_LABELS[layer.geometryFamily]}
                      </Typography>
                    </Stack>
                  </TableCell>
                  <TableCell>{layer.crs}</TableCell>
                  <TableCell align="right">{formatCount(layer.featureCount)}</TableCell>
                  <TableCell>
                    <CapabilityChips layer={layer} />
                  </TableCell>
                  <TableCell>
                    <Tooltip title={STATUS_HINTS[layer.status]}>
                      <Chip
                        size="small"
                        label={STATUS_LABELS[layer.status]}
                        color={STATUS_COLOURS[layer.status]}
                        variant={layer.status === 'ACTIVE' ? 'filled' : 'outlined'}
                      />
                    </Tooltip>
                  </TableCell>
                  <TableCell>
                    <Stack direction="row" spacing={0.25}>
                      <Action title="Preview" onClick={() => setPreviewing(layer)}>
                        <PreviewIcon fontSize="small" />
                      </Action>
                      {/*
                       * Manage attributes leaves this module entirely. Data Management owns fields,
                       * and this is a link into it for the selected layer rather than a second
                       * editor — the whole reason the registry has no attribute tab.
                       */}
                      <Action
                        title="Manage attributes in Data Management"
                        disabled={!canManageFields}
                        onClick={() => navigate(`/data-management?layerId=${layer.id}`)}
                      >
                        <SchemaIcon fontSize="small" />
                      </Action>
                      <Action
                        title="Styles"
                        disabled={!canStyle}
                        onClick={() => navigate(`/layer-styles?layerId=${layer.id}`)}
                      >
                        <PaletteIcon fontSize="small" />
                      </Action>
                      <Action
                        title={
                          layer.importEnabled
                            ? 'Import into this layer'
                            : 'Import is switched off for this layer'
                        }
                        disabled={!layer.importEnabled || !layer.status.startsWith('ACTIVE')}
                        onClick={() => navigate(`/import?layerId=${layer.id}`)}
                      >
                        <ImportIcon fontSize="small" />
                      </Action>
                      <Action
                        title={
                          layer.exportEnabled
                            ? 'Export this layer'
                            : 'Export is switched off for this layer'
                        }
                        disabled={!layer.exportEnabled}
                        onClick={() => navigate(`/assets?assetType=${layer.assetType}`)}
                      >
                        <ExportIcon fontSize="small" />
                      </Action>
                      <Action title="Edit" disabled={!canManage} onClick={() => openEdit(layer)}>
                        <EditIcon fontSize="small" />
                      </Action>
                      {layer.status === 'ACTIVE' ? (
                        <Action
                          title="Disable — withdraws it from the map and the import hub; no feature is removed"
                          disabled={!canManage}
                          onClick={() =>
                            changeStatus.mutate({ id: layer.id, status: 'INACTIVE' })
                          }
                        >
                          <DisableIcon fontSize="small" />
                        </Action>
                      ) : (
                        <Action
                          title="Enable"
                          disabled={!canManage}
                          onClick={() => changeStatus.mutate({ id: layer.id, status: 'ACTIVE' })}
                        >
                          <EnableIcon fontSize="small" />
                        </Action>
                      )}
                      <Action
                        title={
                          layer.system
                            ? (systemLayerReason(true) ?? '')
                            : 'Archive — retires the layer; every feature is kept'
                        }
                        disabled={!canManage || layer.system || layer.status === 'ARCHIVED'}
                        onClick={() => changeStatus.mutate({ id: layer.id, status: 'ARCHIVED' })}
                      >
                        <ArchiveIcon fontSize="small" />
                      </Action>
                    </Stack>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </Card>

      <LayerFormDialog
        open={formOpen}
        layer={editing}
        onClose={() => setFormOpen(false)}
        onSaved={(layer) => {
          setFormOpen(false);
          /*
           * Straight into Data Management for a newly created layer. The architecture is
           * "create the layer, then define its fields", and making that the default next step is
           * what stops a layer sitting empty because its attributes were somebody else's job.
           */
          if (!editing && canManageFields) {
            navigate(`/data-management?layerId=${layer.id}`);
          }
        }}
      />

      <LayerPreviewDialog
        layer={previewing}
        open={Boolean(previewing)}
        onClose={() => setPreviewing(null)}
      />
    </Stack>
  );
}

/**
 * The capability flags, as chips.
 *
 * Only the ones that are *off* are shown as muted, and vector tiles is always shown, because a layer
 * with tiles disabled looks identical to a broken one on the map and this is the only place that
 * says which it is.
 */
function CapabilityChips({ layer }: { layer: GisLayer }) {
  const flags: { label: string; on: boolean; hint: string }[] = [
    { label: 'Tiles', on: layer.vectorTileEnabled, hint: 'Vector tiles are served for this layer' },
    { label: 'Visible', on: layer.visibleByDefault, hint: 'Switched on when the map opens' },
    { label: 'Query', on: layer.queryable, hint: 'Clicking a feature opens the inspection card' },
    { label: 'Search', on: layer.searchable, hint: 'The map’s search box looks here' },
    { label: 'Import', on: layer.importEnabled, hint: 'Offered as an import target' },
    { label: 'Export', on: layer.exportEnabled, hint: 'Included in exports' },
  ];
  return (
    <Stack direction="row" spacing={0.4} flexWrap="wrap" useFlexGap>
      {flags.map((flag) => (
        <Tooltip key={flag.label} title={flag.hint}>
          <Chip
            size="small"
            label={flag.label}
            variant="outlined"
            sx={(theme) => ({
              height: 20,
              fontSize: 10.5,
              opacity: flag.on ? 1 : 0.35,
              borderColor: flag.on
                ? alpha(theme.palette.primary.main, 0.5)
                : theme.palette.divider,
            })}
          />
        </Tooltip>
      ))}
    </Stack>
  );
}

function Action({
  title,
  disabled,
  onClick,
  children,
}: {
  title: string;
  disabled?: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <Tooltip title={title}>
      {/* A disabled button fires no events, so the tooltip needs a wrapper that still can. Without
          it the explanation for why a control is greyed out is unreachable. */}
      <span>
        <IconButton size="small" disabled={disabled} onClick={onClick}>
          {children}
        </IconButton>
      </span>
    </Tooltip>
  );
}
