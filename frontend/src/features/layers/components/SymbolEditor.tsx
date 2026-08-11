import { useState } from 'react';
import {
  Alert,
  Box,
  FormControlLabel,
  MenuItem,
  Slider,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import { IconPicker } from './IconPicker';
import type { GeometryFamily, StyleVocabulary, Symbol } from '../types';

/**
 * Edits one symbol document.
 *
 * Which controls appear comes from the server's vocabulary (`symbolKeys` per geometry family), not
 * from a table in this file. That is the same rule the device registration form follows for
 * transports, and it is what keeps the editor from offering a property the server would drop or
 * omitting one it accepts.
 *
 * Used twice with different shapes: for a style's base symbol, where every applicable key is shown,
 * and for a rule's override, where the same controls appear but an untouched one falls through to
 * the base rather than being written. That fall-through is why the rule editor sets `sparse` — a
 * rule that only changes the colour should say only that, so a later change to the base width
 * reaches every class rather than only the ones that happened not to restate it.
 */
export function SymbolEditor({
  value,
  onChange,
  family,
  vocabulary,
  sparse = false,
  compact = false,
}: {
  value: Symbol;
  onChange: (next: Symbol) => void;
  family: GeometryFamily;
  vocabulary: StyleVocabulary | undefined;
  sparse?: boolean;
  compact?: boolean;
}) {
  /*
   * "Every property", off by default.
   *
   * The default shows the keys that apply to the layer's own geometry, which is what an administrator
   * of a point layer wants — line joins and fill opacity on a layer that holds no lines or polygons
   * are controls that do nothing. But a layer's declared geometry is a declaration, and a mixed
   * layer, or one whose geometry is about to be widened, genuinely needs the rest. The switch is the
   * escape hatch; the filtered view is the useful default.
   */
  const [allProperties, setAllProperties] = useState(false);
  const keys = (allProperties ? vocabulary?.symbolKeys?.ANY : vocabulary?.symbolKeys?.[family]) ?? [];
  const has = (key: string) => keys.includes(key);
  const set = <K extends keyof Symbol>(key: K, next: Symbol[K]) =>
    onChange({ ...value, [key]: next });

  const renderMode = value.renderMode ?? 'circle';

  /*
   * An explicit message rather than an empty section.
   *
   * The vocabulary is a request, and a failed one used to render as a heading with nothing beneath
   * it — which reads as "this product has no style options" rather than "a call failed". Saying so
   * is the difference between a bug report about a missing feature and one about a broken endpoint.
   */
  if (!vocabulary) {
    return (
      <Alert severity="warning" sx={{ fontSize: 13 }}>
        The style vocabulary could not be loaded, so the symbol controls cannot be rendered. They are
        served by the API rather than built into this page, which is what keeps the editor from
        offering a value the server would reject — reload, and check that
        <code> GET /layer-styles/vocabulary </code> is responding.
      </Alert>
    );
  }

  return (
    <Stack spacing={compact ? 1.5 : 2}>
      {!sparse && !compact ? (
        <Stack direction="row" justifyContent="flex-end">
          <FormControlLabel
            control={
              <Switch
                size="small"
                checked={allProperties}
                onChange={(e) => setAllProperties(e.target.checked)}
              />
            }
            label={
              <Typography variant="caption">
                Every property {allProperties ? '' : `(showing ${family.toLowerCase()} only)`}
              </Typography>
            }
          />
        </Stack>
      ) : null}
      {has('renderMode') ? (
        <Stack direction="row" spacing={1.5}>
          <TextField
            select
            size="small"
            label="Point drawn as"
            fullWidth
            value={renderMode}
            onChange={(e) => set('renderMode', e.target.value as 'circle' | 'icon')}
          >
            {(vocabulary?.enumeratedKeys?.renderMode ?? ['circle', 'icon']).map((option) => (
              <MenuItem key={option} value={option}>
                {option === 'circle' ? 'Circle' : 'Icon'}
              </MenuItem>
            ))}
          </TextField>
          {renderMode === 'icon' ? (
            <Box sx={{ flex: 1 }}>
              <IconPicker value={value.icon} onChange={(icon) => set('icon', icon)} />
            </Box>
          ) : null}
        </Stack>
      ) : null}

      {has('iconSize') && renderMode === 'icon' && !sparse ? (
        <NumberSlider
          label="Icon size"
          value={value.iconSize ?? 1}
          min={0.2}
          max={4}
          step={0.1}
          onChange={(v) => set('iconSize', v)}
        />
      ) : null}

      <Stack direction="row" spacing={1.5} flexWrap="wrap" useFlexGap>
        {has('fillColor') ? (
          <ColourField
            label={family === 'POLYGON' ? 'Fill colour' : 'Colour'}
            value={value.fillColor}
            onChange={(v) => set('fillColor', v)}
          />
        ) : null}
        {has('lineColor') ? (
          <ColourField
            label="Line colour"
            value={value.lineColor}
            onChange={(v) => set('lineColor', v)}
          />
        ) : null}
        {has('strokeColor') ? (
          <ColourField
            label="Stroke colour"
            value={value.strokeColor}
            onChange={(v) => set('strokeColor', v)}
          />
        ) : null}
        {has('outlineColor') ? (
          <ColourField
            label="Outline colour"
            value={value.outlineColor}
            onChange={(v) => set('outlineColor', v)}
          />
        ) : null}
        {has('glowColor') && !sparse ? (
          <ColourField
            label="Glow"
            value={value.glowColor}
            onChange={(v) => set('glowColor', v)}
          />
        ) : null}
      </Stack>

      {sparse ? null : (
        <>
          {has('size') ? (
            <NumberSlider
              label="Size"
              value={value.size ?? 5}
              min={1}
              max={30}
              step={0.5}
              onChange={(v) => set('size', v)}
            />
          ) : null}
          {has('lineWidth') ? (
            <NumberSlider
              label="Line width"
              value={value.lineWidth ?? 3}
              min={0.5}
              max={20}
              step={0.5}
              onChange={(v) => set('lineWidth', v)}
            />
          ) : null}
          {has('strokeWidth') ? (
            <NumberSlider
              label="Stroke width"
              value={value.strokeWidth ?? 1.5}
              min={0}
              max={10}
              step={0.5}
              onChange={(v) => set('strokeWidth', v)}
            />
          ) : null}
          {has('outlineWidth') ? (
            <NumberSlider
              label="Outline width"
              value={value.outlineWidth ?? 1.5}
              min={0}
              max={10}
              step={0.5}
              onChange={(v) => set('outlineWidth', v)}
            />
          ) : null}
          {has('opacity') ? (
            <NumberSlider
              label="Opacity"
              value={value.opacity ?? 1}
              min={0}
              max={1}
              step={0.05}
              onChange={(v) => set('opacity', v)}
            />
          ) : null}
          {has('fillOpacity') ? (
            <NumberSlider
              label="Fill opacity"
              value={value.fillOpacity ?? 0.14}
              min={0}
              max={1}
              step={0.02}
              onChange={(v) => set('fillOpacity', v)}
            />
          ) : null}
          {has('lineOpacity') ? (
            <NumberSlider
              label="Line opacity"
              value={value.lineOpacity ?? 1}
              min={0}
              max={1}
              step={0.05}
              onChange={(v) => set('lineOpacity', v)}
            />
          ) : null}

          {has('dashPattern') ? (
            <TextField
              size="small"
              label="Dash pattern"
              value={(value.dashPattern ?? []).join(', ')}
              onChange={(e) => {
                const parsed = e.target.value
                  .split(',')
                  .map((part) => Number(part.trim()))
                  .filter((n) => Number.isFinite(n) && n > 0);
                // Fewer than two segments is not a dash MapLibre can draw, so an incomplete entry
                // becomes "solid" rather than a line that silently disappears while being typed.
                set('dashPattern', parsed.length >= 2 ? parsed : []);
              }}
              helperText="Dash and gap lengths, in line widths — e.g. 2, 1.5. Empty means solid."
            />
          ) : null}

          {has('lineCap') || has('lineJoin') ? (
            <Stack direction="row" spacing={1.5}>
              {has('lineCap') ? (
                <TextField
                  select
                  size="small"
                  label="Line cap"
                  fullWidth
                  value={value.lineCap ?? 'round'}
                  onChange={(e) => set('lineCap', e.target.value as Symbol['lineCap'])}
                >
                  {(vocabulary?.enumeratedKeys?.lineCap ?? []).map((option) => (
                    <MenuItem key={option} value={option}>
                      {option}
                    </MenuItem>
                  ))}
                </TextField>
              ) : null}
              {has('lineJoin') ? (
                <TextField
                  select
                  size="small"
                  label="Line join"
                  fullWidth
                  value={value.lineJoin ?? 'round'}
                  onChange={(e) => set('lineJoin', e.target.value as Symbol['lineJoin'])}
                >
                  {(vocabulary?.enumeratedKeys?.lineJoin ?? []).map((option) => (
                    <MenuItem key={option} value={option}>
                      {option}
                    </MenuItem>
                  ))}
                </TextField>
              ) : null}
            </Stack>
          ) : null}
        </>
      )}
    </Stack>
  );
}

/**
 * A colour swatch plus its hex value.
 *
 * Both, rather than a picker alone: the swatch is how a colour is chosen and the text is how it is
 * pasted from a brand guide or a previous layer. The server validates the value either way.
 */
export function ColourField({
  label,
  value,
  onChange,
}: {
  label: string;
  value: string | undefined;
  onChange: (value: string) => void;
}) {
  const current = value ?? '#3B82F6';
  return (
    <Stack direction="row" spacing={1} alignItems="center">
      <Box
        component="input"
        type="color"
        aria-label={label}
        value={/^#[0-9a-fA-F]{6}$/.test(current) ? current : '#3B82F6'}
        onChange={(e: React.ChangeEvent<HTMLInputElement>) => onChange(e.target.value)}
        sx={{
          width: 38,
          height: 38,
          padding: 0,
          border: 'none',
          borderRadius: 1,
          cursor: 'pointer',
          background: 'none',
        }}
      />
      <TextField
        size="small"
        label={label}
        value={current}
        onChange={(e) => onChange(e.target.value)}
        sx={{ width: 150 }}
      />
    </Stack>
  );
}

function NumberSlider({
  label,
  value,
  min,
  max,
  step,
  onChange,
}: {
  label: string;
  value: number;
  min: number;
  max: number;
  step: number;
  onChange: (value: number) => void;
}) {
  return (
    <Box>
      <Stack direction="row" justifyContent="space-between" alignItems="baseline">
        <Typography variant="caption" sx={{ opacity: 0.8 }}>
          {label}
        </Typography>
        <Typography variant="caption" sx={{ fontWeight: 700 }}>
          {value}
        </Typography>
      </Stack>
      <Slider
        size="small"
        value={value}
        min={min}
        max={max}
        step={step}
        onChange={(_, next) => onChange(Array.isArray(next) ? next[0]! : next)}
      />
    </Box>
  );
}
