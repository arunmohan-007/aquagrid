import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Autocomplete,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  FormControlLabel,
  MenuItem,
  Stack,
  Switch,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from '@mui/material';
import { problemMessage } from '@/lib/api/problem';
import {
  useAssetTypeOptions,
  useCreateLayer,
  useCrsOptions,
  useGeometryTypes,
  useLayerCategories,
  useUpdateLayer,
} from '../hooks/useLayers';
import type { CreateLayerRequest, GeometryTypeCode, GisLayer } from '../types';

/**
 * Derives a layer name from a display name, the same way the server does when one is omitted.
 *
 * Duplicated in the client on purpose, and only for the *suggestion*: the operator has to see the
 * name before saving, because it is permanent and it appears in tile URLs. The server derives it
 * again and validates it, so this copy can never be the thing that decides — which is what makes the
 * duplication safe rather than the usual two-copies-that-drift problem.
 */
function deriveCode(title: string): string {
  const code = title
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  if (!code) return '';
  return /^[a-z]/.test(code) ? code.slice(0, 60) : `layer-${code}`.slice(0, 60);
}

interface Props {
  open: boolean;
  /** Null creates; a layer edits it. */
  layer: GisLayer | null;
  onClose: () => void;
  onSaved: (layer: GisLayer) => void;
}

/**
 * Create or edit a layer.
 *
 * The form is deliberately shaped around what is permanent and what is not. The layer name and the
 * asset type are chosen once and then shown read-only — the name is the `source-layer` inside every
 * cached vector tile and the MapLibre source id every render layer references, so renaming it would
 * stop the map drawing the layer with no error anywhere.
 *
 * There is no attribute section, and there never will be. Fields belong to Data Management; this
 * dialog's last step is a button that opens it for the layer just created.
 */
export function LayerFormDialog({ open, layer, onClose, onSaved }: Props) {
  const editing = Boolean(layer);
  const { data: geometryTypes } = useGeometryTypes();
  const { data: assetTypes } = useAssetTypeOptions();
  const { data: categories } = useLayerCategories();
  const [crsSearch, setCrsSearch] = useState('');
  const { data: crsOptions } = useCrsOptions(crsSearch);

  const create = useCreateLayer();
  const update = useUpdateLayer();
  const saving = create.isPending || update.isPending;
  const error = create.error ?? update.error;

  const [form, setForm] = useState<CreateLayerRequest>({ title: '' });
  const [codeTouched, setCodeTouched] = useState(false);
  /*
   * Advanced geometry is off by default. An ordinary administrator wants Point, Line or Polygon; the
   * eight precise types exist for the GIS specialist whose deliverables need MULTILINESTRING, and
   * putting all eight in front of everyone makes the common choice harder to make correctly.
   */
  const [advancedGeometry, setAdvancedGeometry] = useState(false);

  useEffect(() => {
    if (!open) return;
    setCodeTouched(false);
    if (layer) {
      setForm({
        title: layer.title,
        code: layer.code,
        description: layer.description ?? '',
        category: layer.category ?? '',
        assetType: layer.assetType,
        geometryType: layer.geometryType,
        crsAuthority: layer.crsAuthority,
        srid: layer.srid,
        visibleByDefault: layer.visibleByDefault,
        editable: layer.editable,
        queryable: layer.queryable,
        searchable: layer.searchable,
        importEnabled: layer.importEnabled,
        exportEnabled: layer.exportEnabled,
        vectorTileEnabled: layer.vectorTileEnabled,
        minZoom: layer.minZoom,
        maxZoom: layer.maxZoom,
      });
      setAdvancedGeometry(!['POINT', 'LINESTRING', 'POLYGON'].includes(layer.geometryType));
    } else {
      setForm({
        title: '',
        code: '',
        description: '',
        category: 'Other',
        assetType: 'CUSTOM',
        geometryType: 'POINT',
        crsAuthority: 'EPSG',
        srid: 4326,
        active: true,
        visibleByDefault: false,
        editable: true,
        queryable: true,
        searchable: true,
        importEnabled: true,
        exportEnabled: true,
        vectorTileEnabled: true,
        minZoom: 0,
        maxZoom: 24,
      });
      setAdvancedGeometry(false);
    }
  }, [open, layer]);

  const simpleTypes = useMemo(
    () => (geometryTypes ?? []).filter((t) => t.simple),
    [geometryTypes],
  );

  const set = <K extends keyof CreateLayerRequest>(key: K, value: CreateLayerRequest[K]) =>
    setForm((prev) => ({ ...prev, [key]: value }));

  const effectiveCode = editing
    ? layer!.code
    : codeTouched
      ? (form.code ?? '')
      : deriveCode(form.title);

  const submit = async () => {
    const payload: CreateLayerRequest = { ...form, code: effectiveCode || undefined };
    const saved = editing
      ? await update.mutateAsync({
          id: layer!.id,
          payload: {
            title: payload.title,
            description: payload.description,
            category: payload.category,
            geometryType: payload.geometryType,
            crsAuthority: payload.crsAuthority,
            srid: payload.srid,
            visibleByDefault: payload.visibleByDefault,
            editable: payload.editable,
            queryable: payload.queryable,
            searchable: payload.searchable,
            importEnabled: payload.importEnabled,
            exportEnabled: payload.exportEnabled,
            vectorTileEnabled: payload.vectorTileEnabled,
            minZoom: payload.minZoom,
            maxZoom: payload.maxZoom,
          },
        })
      : await create.mutateAsync(payload);
    onSaved(saved);
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>{editing ? `Edit ${layer!.title}` : 'Create layer'}</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2.5} sx={{ pt: 1 }}>
          {error ? <Alert severity="error">{problemMessage(error)}</Alert> : null}

          {!editing ? (
            <Alert severity="info" sx={{ '& .MuiAlert-message': { fontSize: 13 } }}>
              No table is created. The layer’s features live in the shared spatial store alongside
              every other layer’s, described by the geometry type and CRS declared here — which is
              why those two can be corrected later, and why the layer name cannot.
            </Alert>
          ) : null}

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Display name"
              required
              fullWidth
              value={form.title}
              onChange={(e) => set('title', e.target.value)}
              helperText="What operators see on the map, the legend and every list."
            />
            <TextField
              label="Layer name"
              fullWidth
              value={effectiveCode}
              disabled={editing}
              onChange={(e) => {
                setCodeTouched(true);
                set('code', e.target.value);
              }}
              helperText={
                editing
                  ? 'Permanent — it is the identifier inside every cached vector tile.'
                  : 'Lower-case, numbers and hyphens. Permanent once created.'
              }
            />
          </Stack>

          <TextField
            label="Description"
            fullWidth
            multiline
            minRows={2}
            value={form.description ?? ''}
            onChange={(e) => set('description', e.target.value)}
            helperText="A sentence saying what the layer is for. Shown wherever the layer is chosen."
          />

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <Autocomplete
              freeSolo
              fullWidth
              options={categories ?? []}
              value={form.category ?? ''}
              onInputChange={(_, value) => set('category', value)}
              renderInput={(params) => (
                <TextField {...params} label="Category" helperText="How the registry groups it." />
              )}
            />
            <TextField
              select
              label="Backed by"
              fullWidth
              value={form.assetType ?? 'CUSTOM'}
              disabled={editing}
              onChange={(e) => set('assetType', e.target.value)}
              helperText={
                editing
                  ? 'Permanent — changing it would orphan every existing feature rather than move it.'
                  : 'CUSTOM for a layer the platform knows nothing about. The others tie into the dashboard and network trace.'
              }
            >
              {(assetTypes ?? []).map((option) => (
                <MenuItem key={option.value} value={option.value}>
                  {option.label}
                </MenuItem>
              ))}
            </TextField>
          </Stack>

          <Divider textAlign="left">
            <Typography variant="caption" sx={{ fontWeight: 700, letterSpacing: 0.4 }}>
              GEOMETRY AND CRS
            </Typography>
          </Divider>

          <Stack spacing={1.5}>
            <Stack direction="row" alignItems="center" justifyContent="space-between">
              <Typography variant="body2" sx={{ fontWeight: 600 }}>
                Geometry type
              </Typography>
              <FormControlLabel
                control={
                  <Switch
                    size="small"
                    checked={advancedGeometry}
                    onChange={(e) => setAdvancedGeometry(e.target.checked)}
                  />
                }
                label={<Typography variant="caption">Advanced types</Typography>}
              />
            </Stack>

            {advancedGeometry ? (
              <TextField
                select
                fullWidth
                value={form.geometryType ?? 'POINT'}
                onChange={(e) => set('geometryType', e.target.value as GeometryTypeCode)}
                helperText="Multi forms accept both single and multi features. “Any geometry” accepts everything — the honest choice for a layer surveyed both ways."
              >
                {(geometryTypes ?? []).map((option) => (
                  <MenuItem key={option.value} value={option.value}>
                    {option.label}
                  </MenuItem>
                ))}
              </TextField>
            ) : (
              <ToggleButtonGroup
                exclusive
                fullWidth
                value={form.geometryType ?? 'POINT'}
                onChange={(_, value) => value && set('geometryType', value as GeometryTypeCode)}
              >
                {simpleTypes.map((option) => (
                  <ToggleButton key={option.value} value={option.value}>
                    {option.label}
                  </ToggleButton>
                ))}
              </ToggleButtonGroup>
            )}
          </Stack>

          <Autocomplete
            fullWidth
            options={crsOptions ?? []}
            getOptionLabel={(option) => `${option.code} — ${option.title}`}
            isOptionEqualToValue={(a, b) => a.srid === b.srid}
            value={(crsOptions ?? []).find((c) => c.srid === form.srid) ?? null}
            onInputChange={(_, value) => setCrsSearch(value)}
            onChange={(_, value) => {
              if (!value) return;
              set('srid', value.srid);
              set('crsAuthority', value.authority);
            }}
            renderInput={(params) => (
              <TextField
                {...params}
                label="Coordinate reference system"
                helperText="Read from this database’s own projection catalogue, so a local grid your organisation has added appears here too."
              />
            )}
          />

          <Divider textAlign="left">
            <Typography variant="caption" sx={{ fontWeight: 700, letterSpacing: 0.4 }}>
              CAPABILITIES
            </Typography>
          </Divider>

          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' },
              columnGap: 2,
            }}
          >
            <Toggle
              label="Visible by default"
              hint="Switched on in the map’s layer control when the console opens."
              checked={form.visibleByDefault ?? false}
              onChange={(v) => set('visibleByDefault', v)}
            />
            <Toggle
              label="Vector tiles"
              hint="Whether tiles are served at all. Off for a layer held only for export."
              checked={form.vectorTileEnabled ?? true}
              onChange={(v) => set('vectorTileEnabled', v)}
            />
            <Toggle
              label="Queryable"
              hint="Clicking a feature opens the inspection card."
              checked={form.queryable ?? true}
              onChange={(v) => set('queryable', v)}
            />
            <Toggle
              label="Searchable"
              hint="The map’s search box looks in this layer."
              checked={form.searchable ?? true}
              onChange={(v) => set('searchable', v)}
            />
            <Toggle
              label="Editable"
              hint="Features may be edited through the asset register."
              checked={form.editable ?? true}
              onChange={(v) => set('editable', v)}
            />
            <Toggle
              label="Import enabled"
              hint="Offered as a target in the Import Hub."
              checked={form.importEnabled ?? true}
              onChange={(v) => set('importEnabled', v)}
            />
            <Toggle
              label="Export enabled"
              hint="Included in asset exports."
              checked={form.exportEnabled ?? true}
              onChange={(v) => set('exportEnabled', v)}
            />
          </Box>

          <Stack direction="row" spacing={2}>
            <TextField
              label="Minimum zoom"
              type="number"
              fullWidth
              value={form.minZoom ?? 0}
              onChange={(e) => set('minZoom', Number(e.target.value))}
              inputProps={{ min: 0, max: 24 }}
              helperText="Below this the layer is not drawn."
            />
            <TextField
              label="Maximum zoom"
              type="number"
              fullWidth
              value={form.maxZoom ?? 24}
              onChange={(e) => set('maxZoom', Number(e.target.value))}
              inputProps={{ min: 0, max: 24 }}
              helperText="Household meters at 15+, district boundaries at 5–10."
            />
          </Stack>
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, py: 2 }}>
        <Button onClick={onClose} disabled={saving}>
          Cancel
        </Button>
        <Button
          variant="contained"
          onClick={submit}
          disabled={saving || !form.title.trim() || (!editing && !effectiveCode)}
        >
          {editing ? 'Save changes' : 'Create layer'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/** A switch with its reason underneath, so no capability is a flag nobody can interpret. */
function Toggle({
  label,
  hint,
  checked,
  onChange,
}: {
  label: string;
  hint: string;
  checked: boolean;
  onChange: (value: boolean) => void;
}) {
  return (
    <Box sx={{ py: 0.5 }}>
      <FormControlLabel
        control={<Switch checked={checked} onChange={(e) => onChange(e.target.checked)} />}
        label={<Typography variant="body2">{label}</Typography>}
      />
      <Typography variant="caption" sx={{ display: 'block', pl: 6, mt: -0.5, opacity: 0.7 }}>
        {hint}
      </Typography>
    </Box>
  );
}
