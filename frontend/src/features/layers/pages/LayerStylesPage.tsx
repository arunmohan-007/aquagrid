import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Alert,
  Box,
  Button,
  Card,
  Chip,
  Divider,
  FormControlLabel,
  IconButton,
  List,
  ListItemButton,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/AddOutlined';
import StarIcon from '@mui/icons-material/StarOutlined';
import StarBorderIcon from '@mui/icons-material/StarBorderOutlined';
import SaveIcon from '@mui/icons-material/SaveOutlined';
import PaletteIcon from '@mui/icons-material/PaletteOutlined';
import { useAuth } from '@/lib/auth/AuthProvider';
import { problemMessage } from '@/lib/api/problem';
import { layersApi } from '../api/layersApi';
import {
  useGisLayers,
  useLayerStatistics,
  useLayerStyles,
  useSaveStyle,
  useStyleFields,
  useStyleLifecycle,
  useStyleVocabulary,
} from '../hooks/useLayers';
import { LayerPreviewMap } from '../components/LayerPreviewMap';
import { SymbolEditor, ColourField } from '../components/SymbolEditor';
import { StyleRuleBuilder } from '../components/StyleRuleBuilder';
import { StyleTemplatePicker } from '../components/StyleTemplatePicker';
import { SymbolLibraryDialog } from '../components/SymbolLibraryDialog';
import type {
  ComposedMapLayer,
  GisLayer,
  LabelConfig,
  LayerStyle,
  SaveStyleRequest,
  StyleRule,
  StyleTemplate,
  StyleTypeCode,
  Symbol,
} from '../types';

/** The editor's working copy, before it becomes a save request. */
interface Draft {
  id?: string;
  name: string;
  description: string;
  styleType: StyleTypeCode;
  classifyField: string;
  active: boolean;
  defaultStyle: boolean;
  minZoom: number;
  maxZoom: number;
  symbol: Symbol;
  label: LabelConfig;
  rules: StyleRule[];
}

function emptyDraft(): Draft {
  return {
    name: 'New style',
    description: '',
    styleType: 'SIMPLE',
    classifyField: '',
    active: true,
    defaultStyle: false,
    minZoom: 0,
    maxZoom: 24,
    symbol: {
      renderMode: 'circle',
      fillColor: '#3B82F6',
      glowColor: '#93C5FD',
      strokeColor: 'rgba(255,255,255,0.9)',
      strokeWidth: 1.5,
      size: 5,
      opacity: 1,
      lineColor: '#3B82F6',
      lineWidth: 3,
      lineOpacity: 1,
      lineCap: 'round',
      lineJoin: 'round',
      fillOpacity: 0.14,
      outlineColor: '#93C5FD',
      outlineWidth: 1.5,
      outlineOpacity: 1,
    },
    label: { enabled: false },
    rules: [],
  };
}

function toDraft(style: LayerStyle): Draft {
  return {
    id: style.id,
    name: style.name,
    description: style.description ?? '',
    styleType: style.styleType,
    classifyField: style.classifyField ?? '',
    active: style.active,
    defaultStyle: style.defaultStyle,
    minZoom: style.minZoom,
    maxZoom: style.maxZoom,
    symbol: { ...emptyDraft().symbol, ...style.symbol },
    label: style.label ?? { enabled: false },
    rules: style.rules,
  };
}

function toRequest(layerId: string, draft: Draft): SaveStyleRequest {
  return {
    layerId,
    name: draft.name,
    description: draft.description || undefined,
    styleType: draft.styleType,
    classifyField: draft.styleType === 'SIMPLE' ? null : draft.classifyField || null,
    active: draft.active,
    defaultStyle: draft.defaultStyle,
    minZoom: draft.minZoom,
    maxZoom: draft.maxZoom,
    symbol: draft.symbol,
    label: draft.label,
    rules:
      draft.styleType === 'SIMPLE'
        ? []
        : draft.rules.map(({ id: _id, active: _active, ...rule }) => rule),
  };
}

/**
 * Layer Style Management.
 *
 * Three columns, because the work is three questions at once: which layer and which of its styles,
 * how it should look, and what that actually produces. The preview is not a mock-up — it is the same
 * composed MapLibre specification the console's map applies, drawn against the same tiles, so what
 * is on screen here is the product rather than a picture of it.
 *
 * Every field the editor offers comes from Data Management's catalogue for the selected layer. That
 * is the module's central constraint and the reason there is no field list anywhere in this feature:
 * a field retired in Data Management disappears from the label picker and the rule builder without a
 * reload, and the server refuses a style naming one regardless of what the client sent.
 */
export default function LayerStylesPage() {
  const { hasPermission } = useAuth();
  const canManage = hasPermission('gis:style:manage');
  const [params, setParams] = useSearchParams();

  const { data: layers } = useGisLayers({ status: 'ACTIVE', withCounts: false });
  const layerId = params.get('layerId') ?? layers?.[0]?.id ?? '';
  const layer: GisLayer | undefined = useMemo(
    () => (layers ?? []).find((l) => l.id === layerId),
    [layers, layerId],
  );

  const { data: styles } = useLayerStyles(layerId || undefined);
  const { data: fields } = useStyleFields(layerId || undefined);
  const { data: vocabulary, error: vocabularyError } = useStyleVocabulary();
  const { data: stats } = useLayerStatistics(layerId || undefined);
  const saveStyle = useSaveStyle();
  const lifecycle = useStyleLifecycle();

  const [draft, setDraft] = useState<Draft>(emptyDraft);
  const [selectedStyleId, setSelectedStyleId] = useState<string | null>(null);
  const [preview, setPreview] = useState<ComposedMapLayer | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [libraryOpen, setLibraryOpen] = useState(false);

  /*
   * Select the layer's default style when the layer changes, rather than leaving the editor on the
   * previous layer's style. A style belongs to the layer it was created on — the server refuses to
   * move one — so carrying a draft across would produce a save that is rejected for a reason the
   * operator never chose.
   */
  useEffect(() => {
    if (!styles) return;
    const chosen = styles.find((s) => s.defaultStyle) ?? styles[0];
    setSelectedStyleId(chosen?.id ?? null);
    setDraft(chosen ? toDraft(chosen) : emptyDraft());
  }, [styles, layerId]);

  /*
   * The live preview, debounced.
   *
   * Composed by the server on the same code path as the save with the write removed, so the preview
   * cannot show something the save would reject — and by the same composer the map uses, so the two
   * cannot disagree about what a rule means. Debounced because it is a round trip and the colour
   * slider fires on every pixel.
   */
  useEffect(() => {
    if (!layerId) return;
    const handle = window.setTimeout(() => {
      layersApi
        .previewStyle(toRequest(layerId, draft))
        .then((composed) => {
          setPreview(composed);
          setPreviewError(null);
        })
        .catch((cause) => {
          // A half-typed rule is the normal state of this form, so a rejected preview is expected
          // rather than exceptional. The last good composition stays on screen and the reason is
          // shown beneath it, which is more useful than a blank canvas.
          setPreviewError(problemMessage(cause));
        });
    }, 350);
    return () => window.clearTimeout(handle);
  }, [layerId, draft]);

  const set = <K extends keyof Draft>(key: K, value: Draft[K]) =>
    setDraft((prev) => ({ ...prev, [key]: value }));

  const selectStyle = (style: LayerStyle | null) => {
    setSelectedStyleId(style?.id ?? null);
    setDraft(style ? toDraft(style) : emptyDraft());
  };

  /**
   * Fills the draft from a template.
   *
   * The name is only replaced while it is still the untouched placeholder — an administrator who has
   * named their style and then browses templates for a symbol should not have the name taken back.
   *
   * Rule seeds become real rules here, against the field the picker resolved. When the layer has no
   * suitable field the rules are still created, with the field left blank: the rule builder then
   * shows exactly what needs choosing, which is more useful than silently dropping the classes and
   * leaving an apparently-simple style behind.
   */
  const applyTemplate = (template: StyleTemplate, classifyField: string, labelField: string) => {
    setDraft((prev) => ({
      ...prev,
      name: prev.name === 'New style' ? template.name : prev.name,
      description: prev.description || template.description,
      styleType: template.styleType,
      classifyField,
      symbol: { ...template.symbol },
      /*
       * Labels come off the template switched off, because "labels on, no field" is the one thing
       * the server refuses — a template has to be savable exactly as it arrives. They switch on here
       * only when the layer's catalogue actually had the field the template asked for; otherwise the
       * appearance is pre-filled and the switch is left for the administrator, next to the picker
       * that shows what their fields are.
       */
      label: labelField
        ? { ...template.label, enabled: true, field: labelField }
        : { ...template.label },
      rules: template.ruleSeeds.map((seed, index) => ({
        id: crypto.randomUUID(),
        fieldName: classifyField,
        operator: seed.operator,
        // A graduated seed carries a lower bound only; the upper is the next band's, which the
        // administrator sets — or replaces wholesale with "From data" in the rule builder.
        value1: seed.value,
        value2: seed.operator === 'BETWEEN' ? '' : undefined,
        label: seed.label,
        symbol: { ...seed.symbol },
        sortOrder: (index + 1) * 10,
      })),
    }));
  };

  const submit = async () => {
    if (!layerId) return;
    const saved = await saveStyle.mutateAsync({
      id: selectedStyleId ?? undefined,
      payload: toRequest(layerId, draft),
    });
    setSelectedStyleId(saved.id);
    setDraft(toDraft(saved));
  };

  const family = layer?.geometryFamily ?? 'ANY';

  return (
    <Stack spacing={2.5}>
      <Stack direction="row" spacing={2} alignItems="flex-start">
        <Box sx={{ flex: 1 }}>
          <Typography variant="h5" sx={{ fontWeight: 700 }}>
            Layer Styles
          </Typography>
          <Typography variant="body2" sx={{ opacity: 0.75, mt: 0.5, maxWidth: 760 }}>
            How each layer is drawn. Styles are stored as configuration and composed into MapLibre
            instructions by the server, so a colour changed here reaches the map without a release —
            and every field a rule or a label names comes from Data Management’s catalogue.
          </Typography>
        </Box>
        {/*
          * The symbol library lives here rather than only inside the icon picker.
          *
          * A symbol belongs to the organisation, not to one style, and burying its only entry point
          * behind "set this point layer's render mode to Icon" made it unreachable on a line or
          * polygon layer — those geometries have no render-mode control for the picker to hang off.
          * One always-visible button matches what the thing actually is.
          */}
        <Button
          variant="outlined"
          startIcon={<PaletteIcon />}
          onClick={() => setLibraryOpen(true)}
          sx={{ flexShrink: 0, mt: 0.5 }}
        >
          Symbol library
        </Button>
      </Stack>

      {vocabularyError ? (
        <Alert severity="error">
          The style vocabulary could not be loaded: {problemMessage(vocabularyError)}. The style type
          and symbol controls are served by the API rather than built into this page, so they cannot
          be rendered until it responds.
        </Alert>
      ) : null}
      {saveStyle.error ? (
        <Alert severity="error" onClose={() => saveStyle.reset()}>
          {problemMessage(saveStyle.error)}
        </Alert>
      ) : null}
      {lifecycle.error ? (
        <Alert severity="error" onClose={() => lifecycle.reset()}>
          {problemMessage(lifecycle.error)}
        </Alert>
      ) : null}

      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', lg: '260px minmax(0, 1fr) minmax(0, 420px)' },
          gap: 2,
          alignItems: 'start',
        }}
      >
        {/* --- Layer and style chooser ------------------------------------------------------ */}
        <Card variant="outlined">
          <Box sx={{ p: 1.5 }}>
            <TextField
              select
              size="small"
              fullWidth
              label="Layer"
              value={layerId}
              onChange={(e) => setParams({ layerId: e.target.value })}
            >
              {(layers ?? []).map((l) => (
                <MenuItem key={l.id} value={l.id}>
                  {l.title}
                </MenuItem>
              ))}
            </TextField>
          </Box>
          <Divider />
          <List dense disablePadding>
            {(styles ?? []).map((style) => (
              <ListItemButton
                key={style.id}
                selected={style.id === selectedStyleId}
                onClick={() => selectStyle(style)}
                sx={{ opacity: style.active ? 1 : 0.55 }}
              >
                <Stack sx={{ flexGrow: 1, minWidth: 0 }}>
                  <Stack direction="row" alignItems="center" spacing={0.75}>
                    <Typography variant="body2" noWrap sx={{ fontWeight: 600 }}>
                      {style.name}
                    </Typography>
                    {!style.active ? (
                      <Chip size="small" label="Off" variant="outlined" sx={{ height: 18 }} />
                    ) : null}
                  </Stack>
                  <Typography variant="caption" sx={{ opacity: 0.65 }}>
                    {style.styleType === 'SIMPLE'
                      ? 'Single symbol'
                      : `${style.rules.length} class${style.rules.length === 1 ? '' : 'es'} on ${style.classifyField}`}
                  </Typography>
                </Stack>
                <Tooltip
                  title={
                    style.defaultStyle
                      ? 'The style the map draws'
                      : 'Make this the style the map draws'
                  }
                >
                  <span>
                    <IconButton
                      size="small"
                      disabled={!canManage || style.defaultStyle}
                      onClick={(event) => {
                        event.stopPropagation();
                        lifecycle.mutate({ id: style.id, action: 'make-default' });
                      }}
                    >
                      {style.defaultStyle ? (
                        <StarIcon fontSize="small" color="primary" />
                      ) : (
                        <StarBorderIcon fontSize="small" />
                      )}
                    </IconButton>
                  </span>
                </Tooltip>
              </ListItemButton>
            ))}
          </List>
          <Divider />
          <Box sx={{ p: 1 }}>
            <Button
              fullWidth
              size="small"
              startIcon={<AddIcon />}
              disabled={!canManage || !layerId}
              onClick={() => selectStyle(null)}
            >
              New style
            </Button>
          </Box>
        </Card>

        {/* --- Editor ---------------------------------------------------------------------- */}
        <Card variant="outlined" sx={{ p: 2 }}>
          <Stack spacing={2.5}>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
              <TextField
                size="small"
                label="Style name"
                fullWidth
                value={draft.name}
                onChange={(e) => set('name', e.target.value)}
              />
              <TextField
                select
                size="small"
                label="Style type"
                fullWidth
                value={draft.styleType}
                onChange={(e) => set('styleType', e.target.value as StyleTypeCode)}
              >
                {(vocabulary?.styleTypes ?? []).map((option) => (
                  <MenuItem key={option.value} value={option.value}>
                    <Stack>
                      <Typography variant="body2">{option.label}</Typography>
                      <Typography variant="caption" sx={{ opacity: 0.65 }}>
                        {option.hint}
                      </Typography>
                    </Stack>
                  </MenuItem>
                ))}
              </TextField>
            </Stack>

            <TextField
              size="small"
              label="Description"
              fullWidth
              value={draft.description}
              onChange={(e) => set('description', e.target.value)}
            />

            <Stack direction="row" spacing={2} alignItems="center">
              <FormControlLabel
                control={
                  <Switch
                    checked={draft.active}
                    onChange={(e) => set('active', e.target.checked)}
                  />
                }
                label={<Typography variant="body2">Active</Typography>}
              />
              <FormControlLabel
                control={
                  <Switch
                    checked={draft.defaultStyle}
                    onChange={(e) => set('defaultStyle', e.target.checked)}
                  />
                }
                label={<Typography variant="body2">Default style</Typography>}
              />
              <TextField
                size="small"
                type="number"
                label="Min zoom"
                sx={{ width: 110 }}
                value={draft.minZoom}
                onChange={(e) => set('minZoom', Number(e.target.value))}
                inputProps={{ min: 0, max: 24 }}
              />
              <TextField
                size="small"
                type="number"
                label="Max zoom"
                sx={{ width: 110 }}
                value={draft.maxZoom}
                onChange={(e) => set('maxZoom', Number(e.target.value))}
                inputProps={{ min: 0, max: 24 }}
              />
            </Stack>

            {/*
              * Templates are offered while creating, not while editing. Applying one to a saved style
              * would silently discard the classes and colours someone already tuned, and an "are you
              * sure" on every card is a worse answer than putting them where they belong.
              */}
            {!selectedStyleId && layerId ? (
              <>
                <Divider textAlign="left">
                  <Typography variant="caption" sx={{ fontWeight: 700, letterSpacing: 0.4 }}>
                    TEMPLATES
                  </Typography>
                </Divider>
                <StyleTemplatePicker
                  layerId={layerId}
                  fields={fields ?? []}
                  onApply={applyTemplate}
                />
              </>
            ) : null}

            <Divider textAlign="left">
              <Typography variant="caption" sx={{ fontWeight: 700, letterSpacing: 0.4 }}>
                BASE SYMBOL
              </Typography>
            </Divider>
            <SymbolEditor
              value={draft.symbol}
              onChange={(symbol) => set('symbol', symbol)}
              family={family}
              vocabulary={vocabulary}
            />

            {draft.styleType !== 'SIMPLE' ? (
              <>
                <Divider textAlign="left">
                  <Typography variant="caption" sx={{ fontWeight: 700, letterSpacing: 0.4 }}>
                    {draft.styleType === 'GRADUATED' ? 'RANGES' : 'RULES'}
                  </Typography>
                </Divider>
                <StyleRuleBuilder
                  layerId={layerId}
                  styleType={draft.styleType}
                  classifyField={draft.classifyField}
                  onClassifyField={(field) => set('classifyField', field)}
                  fields={fields ?? []}
                  rules={draft.rules}
                  onRules={(rules) => set('rules', rules)}
                  family={family}
                  vocabulary={vocabulary}
                />
              </>
            ) : null}

            <Divider textAlign="left">
              <Typography variant="caption" sx={{ fontWeight: 700, letterSpacing: 0.4 }}>
                LABELS
              </Typography>
            </Divider>
            <LabelEditor
              value={draft.label}
              onChange={(label) => set('label', label)}
              fields={fields ?? []}
            />
          </Stack>
        </Card>

        {/* --- Preview --------------------------------------------------------------------- */}
        <Stack spacing={1.5} sx={{ position: { lg: 'sticky' }, top: { lg: 16 } }}>
          <Card variant="outlined" sx={{ p: 1.5 }}>
            <Typography variant="caption" sx={{ fontWeight: 700, letterSpacing: 0.4 }}>
              LIVE PREVIEW
            </Typography>
            <Box sx={{ mt: 1 }}>
              <LayerPreviewMap composed={preview} extent={stats?.extent} height={300} />
            </Box>
            {previewError ? (
              <Alert severity="warning" sx={{ mt: 1, fontSize: 12 }}>
                {previewError}
              </Alert>
            ) : null}
            {preview && preview.legend.length > 1 ? (
              <Stack spacing={0.5} sx={{ mt: 1.5 }}>
                {preview.legend.map((entry) => (
                  <Stack key={entry.label} direction="row" alignItems="center" spacing={1}>
                    <Box
                      sx={{
                        width: 12,
                        height: 12,
                        borderRadius: entry.shape === 'fill' ? 0.5 : '50%',
                        bgcolor: entry.colour,
                        flexShrink: 0,
                      }}
                    />
                    <Typography variant="caption">{entry.label}</Typography>
                  </Stack>
                ))}
              </Stack>
            ) : null}
            {preview && preview.styledFields.length > 0 ? (
              <Typography variant="caption" sx={{ display: 'block', mt: 1.5, opacity: 0.7 }}>
                Reads {preview.styledFields.join(', ')} from the vector tile.
              </Typography>
            ) : null}
          </Card>

          <Stack direction="row" spacing={1}>
            <Button
              fullWidth
              variant="contained"
              startIcon={<SaveIcon />}
              disabled={!canManage || !layerId || saveStyle.isPending || !draft.name.trim()}
              onClick={submit}
            >
              {selectedStyleId ? 'Save style' : 'Create style'}
            </Button>
            {selectedStyleId ? (
              <Button
                variant="outlined"
                disabled={!canManage}
                onClick={() =>
                  lifecycle.mutate({
                    id: selectedStyleId,
                    action: draft.active ? 'deactivate' : 'activate',
                  })
                }
              >
                {draft.active ? 'Deactivate' : 'Activate'}
              </Button>
            ) : null}
          </Stack>
        </Stack>
      </Box>

      <SymbolLibraryDialog open={libraryOpen} onClose={() => setLibraryOpen(false)} />
    </Stack>
  );
}

/**
 * Label configuration.
 *
 * The field list is Data Management's, which is the whole point: "Bore Well → asset_id" is a choice
 * between the fields that layer actually has, and a free-text box here would let someone label a
 * layer by a field that does not exist and see nothing drawn, with no error anywhere.
 */
function LabelEditor({
  value,
  onChange,
  fields,
}: {
  value: LabelConfig;
  onChange: (next: LabelConfig) => void;
  fields: { fieldName: string; displayName: string }[];
}) {
  const set = <K extends keyof LabelConfig>(key: K, next: LabelConfig[K]) =>
    onChange({ ...value, [key]: next });

  return (
    <Stack spacing={1.5}>
      <FormControlLabel
        control={
          <Switch
            checked={value.enabled ?? false}
            onChange={(e) => set('enabled', e.target.checked)}
          />
        }
        label={<Typography variant="body2">Show labels</Typography>}
      />
      {value.enabled ? (
        <>
          <TextField
            select
            size="small"
            label="Label field"
            fullWidth
            value={value.field ?? ''}
            onChange={(e) => set('field', e.target.value)}
            helperText="From Data Management’s catalogue for this layer."
          >
            {fields.map((f) => (
              <MenuItem key={f.fieldName} value={f.fieldName}>
                {f.displayName}
                <Typography component="span" variant="caption" sx={{ opacity: 0.6, ml: 1 }}>
                  {f.fieldName}
                </Typography>
              </MenuItem>
            ))}
          </TextField>

          <Stack direction="row" spacing={1.5} flexWrap="wrap" useFlexGap>
            <ColourField
              label="Text colour"
              value={value.textColor}
              onChange={(v) => set('textColor', v)}
            />
            <ColourField
              label="Halo colour"
              value={value.haloColor}
              onChange={(v) => set('haloColor', v)}
            />
          </Stack>

          <Stack direction="row" spacing={1.5}>
            <TextField
              size="small"
              type="number"
              label="Text size"
              sx={{ width: 120 }}
              value={value.textSize ?? 11}
              onChange={(e) => set('textSize', Number(e.target.value))}
            />
            <TextField
              size="small"
              type="number"
              label="Halo width"
              sx={{ width: 120 }}
              value={value.haloWidth ?? 1.2}
              onChange={(e) => set('haloWidth', Number(e.target.value))}
            />
            <TextField
              size="small"
              type="number"
              label="Min zoom"
              sx={{ width: 110 }}
              value={value.minZoom ?? 0}
              onChange={(e) => set('minZoom', Number(e.target.value))}
              inputProps={{ min: 0, max: 24 }}
            />
            <TextField
              size="small"
              type="number"
              label="Max zoom"
              sx={{ width: 110 }}
              value={value.maxZoom ?? 24}
              onChange={(e) => set('maxZoom', Number(e.target.value))}
              inputProps={{ min: 0, max: 24 }}
            />
          </Stack>
          <Typography variant="caption" sx={{ opacity: 0.7 }}>
            Labels are drawn above the symbol and dropped where they would overlap — an unreadable
            pile of text tells an operator less than a thinned-out set of legible ones.
          </Typography>
        </>
      ) : null}
    </Stack>
  );
}
