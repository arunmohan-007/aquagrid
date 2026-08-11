import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  FormControlLabel,
  Grid,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import { useCategories, useDataTypes, useUnits } from '../hooks/useDeviceDataConfig';
import type {
  CreateParameterRequest,
  DeviceParameter,
  DiscoveredParameter,
  ParameterScope,
  UpdateParameterRequest,
} from '../types';

/**
 * The add/edit form for one parameter.
 *
 * Three things it deliberately does not do:
 *
 * - It does not enumerate data types, categories or units. All three are fetched from the server
 *   that enforces them, so the form cannot offer something the server refuses — which is a
 *   validation error the operator has no way to act on.
 * - It does not hide the range and precision boxes behind a guess about the type. It reads
 *   `numeric` and `usesPrecision` off the fetched type definition, so a type added on the server
 *   arrives here with its facets already correct.
 * - It does not stop anyone configuring a parameter. There is no "this value looks wrong" gate:
 *   the ranges configured here mark readings, they never discard them, and the form says so where
 *   an operator will read it — because a range box that looked like a filter would get set
 *   defensively narrow by someone trying to keep bad data out.
 */

interface Props {
  open: boolean;
  onClose: () => void;
  /** Editing an existing parameter, or null when creating. */
  parameter: DeviceParameter | null;
  /** Pre-fills the form from a discovery row — the Configure action on the Discovered screen. */
  fromDiscovery?: DiscoveredParameter | null;
  /** The scope the grid is currently showing, used as the create default. */
  defaultScope: ParameterScope;
  defaultDeviceType?: string | undefined;
  defaultDeviceId?: string | undefined;
  deviceTypeOptions: Array<{ value: string; label: string }>;
  onCreate: (payload: CreateParameterRequest) => Promise<unknown>;
  onUpdate: (id: string, payload: UpdateParameterRequest) => Promise<unknown>;
  error?: string | null;
}

interface FormState {
  scope: ParameterScope;
  deviceType: string;
  parameterName: string;
  displayName: string;
  description: string;
  dataType: string;
  unit: string;
  category: string;
  payloadKey: string;
  mandatory: boolean;
  dashboardVisible: boolean;
  useForAlarm: boolean;
  useForReports: boolean;
  minValue: string;
  maxValue: string;
  decimalPrecision: string;
  sampleValue: string;
  defaultValue: string;
  sortOrder: string;
  changeReason: string;
}

const EMPTY: FormState = {
  scope: 'DEVICE_TYPE',
  deviceType: '',
  parameterName: '',
  displayName: '',
  description: '',
  dataType: 'DECIMAL',
  unit: '',
  category: 'OTHER',
  payloadKey: '',
  mandatory: false,
  dashboardVisible: true,
  useForAlarm: false,
  useForReports: true,
  minValue: '',
  maxValue: '',
  decimalPrecision: '',
  sampleValue: '',
  defaultValue: '',
  sortOrder: '',
  changeReason: '',
};

export function ParameterFormDialog({
  open,
  onClose,
  parameter,
  fromDiscovery,
  defaultScope,
  defaultDeviceType,
  defaultDeviceId,
  deviceTypeOptions,
  onCreate,
  onUpdate,
  error,
}: Props) {
  const { data: dataTypes = [] } = useDataTypes();
  const { data: categories = [] } = useCategories();
  const { data: units = [] } = useUnits();

  const [form, setForm] = useState<FormState>(EMPTY);
  const [saving, setSaving] = useState(false);
  const editing = Boolean(parameter);

  useEffect(() => {
    if (!open) return;
    if (parameter) {
      setForm({
        scope: parameter.scope,
        deviceType: parameter.deviceType ?? '',
        parameterName: parameter.parameterName,
        displayName: parameter.displayName,
        description: parameter.description ?? '',
        dataType: parameter.dataType,
        unit: parameter.unit ?? '',
        category: parameter.category,
        // The server resolves "same as the name" before sending, so an unchanged key round-trips
        // as itself and is never mistaken for a rename.
        payloadKey: parameter.payloadKey === parameter.parameterName ? '' : parameter.payloadKey,
        mandatory: parameter.mandatory,
        dashboardVisible: parameter.dashboardVisible,
        useForAlarm: parameter.useForAlarm,
        useForReports: parameter.useForReports,
        minValue: parameter.minValue == null ? '' : String(parameter.minValue),
        maxValue: parameter.maxValue == null ? '' : String(parameter.maxValue),
        decimalPrecision:
          parameter.decimalPrecision == null ? '' : String(parameter.decimalPrecision),
        sampleValue: parameter.sampleValue ?? '',
        defaultValue: parameter.defaultValue ?? '',
        sortOrder: String(parameter.sortOrder),
        changeReason: '',
      });
      return;
    }
    // Creating. A discovery row pre-fills everything it credibly knows — the wire key, a real
    // sample and a detected type — so the common case is confirming a guess rather than retyping
    // what the screen just showed.
    setForm({
      ...EMPTY,
      scope: fromDiscovery ? 'DEVICE' : defaultScope,
      deviceType: fromDiscovery?.deviceType ?? defaultDeviceType ?? '',
      parameterName: fromDiscovery ? toParameterName(fromDiscovery.parameterName) : '',
      payloadKey: fromDiscovery
        ? keyIfDifferent(fromDiscovery.parameterName)
        : '',
      dataType: fromDiscovery?.detectedDataType ?? 'DECIMAL',
      sampleValue: fromDiscovery?.sampleValue ?? '',
    });
  }, [open, parameter, fromDiscovery, defaultScope, defaultDeviceType]);

  const selectedType = useMemo(
    () => dataTypes.find((type) => type.value === form.dataType),
    [dataTypes, form.dataType],
  );

  const set = <K extends keyof FormState>(key: K, value: FormState[K]) =>
    setForm((current) => ({ ...current, [key]: value }));

  const targetDeviceId = fromDiscovery?.deviceId ?? defaultDeviceId;
  const scopeIsDevice = form.scope === 'DEVICE';
  const canSave =
    form.parameterName.trim().length > 0 &&
    form.dataType.length > 0 &&
    (scopeIsDevice ? Boolean(targetDeviceId) : form.deviceType.length > 0);

  const handleSave = async () => {
    setSaving(true);
    try {
      if (parameter) {
        await onUpdate(parameter.id, {
          displayName: form.displayName.trim() || undefined,
          description: form.description.trim(),
          dataType: form.dataType,
          unit: form.unit,
          category: form.category,
          payloadKey: form.payloadKey.trim() || form.parameterName,
          mandatory: form.mandatory,
          dashboardVisible: form.dashboardVisible,
          useForAlarm: form.useForAlarm,
          useForReports: form.useForReports,
          // NaN is the server's "remove this bound" signal — an empty box means the operator
          // cleared it, which a plain omission could not distinguish from not having sent it.
          minValue: form.minValue.trim() === '' ? Number.NaN : Number(form.minValue),
          maxValue: form.maxValue.trim() === '' ? Number.NaN : Number(form.maxValue),
          decimalPrecision:
            form.decimalPrecision.trim() === '' ? null : Number(form.decimalPrecision),
          sampleValue: form.sampleValue,
          defaultValue: form.defaultValue,
          sortOrder: form.sortOrder.trim() === '' ? undefined : Number(form.sortOrder),
          changeReason: form.changeReason.trim() || undefined,
          // The server describes exactly what a retype or re-key will do on the first attempt; the
          // page surfaces that message and the operator resubmits.
          confirmBreakingChange: true,
        });
      } else {
        await onCreate({
          scope: form.scope,
          deviceType: scopeIsDevice ? undefined : form.deviceType,
          deviceId: scopeIsDevice ? targetDeviceId : undefined,
          parameterName: form.parameterName.trim(),
          displayName: form.displayName.trim() || undefined,
          description: form.description.trim() || undefined,
          dataType: form.dataType,
          unit: form.unit || undefined,
          category: form.category,
          payloadKey: form.payloadKey.trim() || undefined,
          mandatory: form.mandatory,
          dashboardVisible: form.dashboardVisible,
          useForAlarm: form.useForAlarm,
          useForReports: form.useForReports,
          minValue: form.minValue.trim() === '' ? null : Number(form.minValue),
          maxValue: form.maxValue.trim() === '' ? null : Number(form.maxValue),
          decimalPrecision:
            form.decimalPrecision.trim() === '' ? null : Number(form.decimalPrecision),
          sampleValue: form.sampleValue.trim() || undefined,
          defaultValue: form.defaultValue.trim() || undefined,
          active: true,
          sortOrder: form.sortOrder.trim() === '' ? undefined : Number(form.sortOrder),
          changeReason: form.changeReason.trim() || undefined,
          discoveredParameterId: fromDiscovery?.id,
        });
      }
      onClose();
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>
        {editing ? `Edit ${parameter?.parameterName}` : 'Configure parameter'}
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
          Configuration decides how this reading is used — its unit, its plausible range, whether it
          reaches a dashboard, an alarm or a report. It never decides whether the device&apos;s data
          is accepted: values outside the range below are stored and flagged, not discarded.
        </Typography>
      </DialogTitle>

      <DialogContent dividers>
        <Stack spacing={2.5}>
          {error ? <Alert severity="warning">{error}</Alert> : null}

          {fromDiscovery ? (
            <Alert severity="info">
              Pre-filled from <strong>{fromDiscovery.parameterName}</strong>, seen{' '}
              {fromDiscovery.occurrences.toLocaleString()} time
              {fromDiscovery.occurrences === 1 ? '' : 's'} on{' '}
              {fromDiscovery.deviceCode ?? 'this device'}. Saving closes it on the discovered list;
              the readings already received keep their history.
            </Alert>
          ) : null}

          {/* ---- Scope ---- */}
          <Grid container spacing={2}>
            <Grid size={{ xs: 12, sm: 4 }}>
              <TextField
                select
                fullWidth
                size="small"
                label="Applies to"
                value={form.scope}
                disabled={editing || Boolean(fromDiscovery)}
                onChange={(event) => set('scope', event.target.value as ParameterScope)}
                helperText={
                  form.scope === 'DEVICE_TYPE'
                    ? 'Every device of this type inherits it'
                    : 'This device only; overrides its type'
                }
              >
                <MenuItem value="DEVICE_TYPE">Device type</MenuItem>
                <MenuItem value="DEVICE" disabled={!targetDeviceId}>
                  A single device
                </MenuItem>
              </TextField>
            </Grid>
            <Grid size={{ xs: 12, sm: 8 }}>
              {scopeIsDevice ? (
                <TextField
                  fullWidth
                  size="small"
                  label="Device"
                  value={fromDiscovery?.deviceCode ?? defaultDeviceId ?? ''}
                  disabled
                  helperText="Chosen by the device filter on the list behind this dialog"
                />
              ) : (
                <TextField
                  select
                  fullWidth
                  size="small"
                  label="Device type"
                  value={form.deviceType}
                  disabled={editing}
                  onChange={(event) => set('deviceType', event.target.value)}
                >
                  {deviceTypeOptions.map((option) => (
                    <MenuItem key={option.value} value={option.value}>
                      {option.label}
                    </MenuItem>
                  ))}
                </TextField>
              )}
            </Grid>
          </Grid>

          <Divider />

          {/* ---- Identity ---- */}
          <Grid container spacing={2}>
            <Grid size={{ xs: 12, sm: 6 }}>
              <TextField
                fullWidth
                size="small"
                label="Parameter name"
                value={form.parameterName}
                disabled={editing}
                onChange={(event) => set('parameterName', event.target.value)}
                helperText={
                  editing
                    ? 'Immutable — readings are already stored under this name'
                    : 'Lower case, digits and underscores. e.g. water_level'
                }
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <TextField
                fullWidth
                size="small"
                label="Display name"
                value={form.displayName}
                onChange={(event) => set('displayName', event.target.value)}
                helperText="What an operator reads. Derived from the name if left blank"
              />
            </Grid>
            <Grid size={12}>
              <TextField
                fullWidth
                size="small"
                label="Description"
                value={form.description}
                onChange={(event) => set('description', event.target.value)}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <TextField
                fullWidth
                size="small"
                label="Source / payload key"
                value={form.payloadKey}
                onChange={(event) => set('payloadKey', event.target.value)}
                helperText="The vendor's spelling, if it differs. e.g. totalVolume"
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 3 }}>
              <TextField
                select
                fullWidth
                size="small"
                label="Data type"
                value={form.dataType}
                onChange={(event) => set('dataType', event.target.value)}
              >
                {dataTypes.map((type) => (
                  <MenuItem key={type.value} value={type.value}>
                    {type.label}
                  </MenuItem>
                ))}
              </TextField>
            </Grid>
            <Grid size={{ xs: 12, sm: 3 }}>
              <TextField
                select
                fullWidth
                size="small"
                label="Unit"
                value={form.unit}
                onChange={(event) => set('unit', event.target.value)}
              >
                <MenuItem value="">
                  <em>None</em>
                </MenuItem>
                {units.map((unit) => (
                  <MenuItem key={unit.id} value={unit.code}>
                    {unit.code} — {unit.label}
                  </MenuItem>
                ))}
              </TextField>
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <TextField
                select
                fullWidth
                size="small"
                label="Category"
                value={form.category}
                onChange={(event) => set('category', event.target.value)}
                helperText="The group this appears in on the telemetry screen"
              >
                {categories.map((category) => (
                  <MenuItem key={category.value} value={category.value}>
                    {category.label}
                  </MenuItem>
                ))}
              </TextField>
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <TextField
                fullWidth
                size="small"
                type="number"
                label="Sort order"
                value={form.sortOrder}
                onChange={(event) => set('sortOrder', event.target.value)}
                helperText="Blank appends to the end of the list"
              />
            </Grid>
          </Grid>

          <Divider />

          {/* ---- Validation ---- */}
          {selectedType?.numeric ? (
            <Grid container spacing={2}>
              <Grid size={{ xs: 12, sm: 4 }}>
                <TextField
                  fullWidth
                  size="small"
                  type="number"
                  label="Minimum value"
                  value={form.minValue}
                  onChange={(event) => set('minValue', event.target.value)}
                />
              </Grid>
              <Grid size={{ xs: 12, sm: 4 }}>
                <TextField
                  fullWidth
                  size="small"
                  type="number"
                  label="Maximum value"
                  value={form.maxValue}
                  onChange={(event) => set('maxValue', event.target.value)}
                />
              </Grid>
              {selectedType.usesPrecision ? (
                <Grid size={{ xs: 12, sm: 4 }}>
                  <TextField
                    fullWidth
                    size="small"
                    type="number"
                    label="Decimal precision"
                    value={form.decimalPrecision}
                    onChange={(event) => set('decimalPrecision', event.target.value)}
                    helperText="Applied by rounding, never by rejection"
                  />
                </Grid>
              ) : null}
              <Grid size={12}>
                <Typography variant="caption" color="text.secondary">
                  A reading outside this range is stored and marked <strong>Out of range</strong>. A
                  pressure of 47 bar on a 10 bar main is the most important reading of the day — the
                  range is here to draw attention to it, not to throw it away.
                </Typography>
              </Grid>
            </Grid>
          ) : null}

          {!selectedType?.storedAsReading ? (
            <Alert severity="info">
              {selectedType?.label ?? 'This type'} values are kept whole in the raw payload rather
              than reduced to a numeric reading, so they are catalogued and searchable but do not
              appear on charts.
            </Alert>
          ) : null}

          <Grid container spacing={2}>
            <Grid size={{ xs: 12, sm: 6 }}>
              <TextField
                fullWidth
                size="small"
                label="Sample value"
                value={form.sampleValue}
                onChange={(event) => set('sampleValue', event.target.value)}
                helperText="Also used by the simulator to generate this parameter"
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <TextField
                fullWidth
                size="small"
                label="Default value"
                value={form.defaultValue}
                onChange={(event) => set('defaultValue', event.target.value)}
              />
            </Grid>
          </Grid>

          <Divider />

          {/* ---- Usage ---- */}
          <Box>
            <Typography variant="subtitle2" gutterBottom>
              How this parameter is used
            </Typography>
            <Stack direction="row" flexWrap="wrap" columnGap={3}>
              <FormControlLabel
                control={
                  <Switch
                    checked={form.mandatory}
                    onChange={(event) => set('mandatory', event.target.checked)}
                  />
                }
                label="Mandatory"
              />
              <FormControlLabel
                control={
                  <Switch
                    checked={form.dashboardVisible}
                    onChange={(event) => set('dashboardVisible', event.target.checked)}
                  />
                }
                label="Show on dashboard"
              />
              <FormControlLabel
                control={
                  <Switch
                    checked={form.useForAlarm}
                    onChange={(event) => set('useForAlarm', event.target.checked)}
                  />
                }
                label="Use for alarms"
              />
              <FormControlLabel
                control={
                  <Switch
                    checked={form.useForReports}
                    onChange={(event) => set('useForReports', event.target.checked)}
                  />
                }
                label="Include in reports"
              />
            </Stack>
            {form.mandatory ? (
              <Typography variant="caption" color="text.secondary">
                A packet without this parameter is still accepted. The absence is recorded as a
                Missing reading so it can be queried and alarmed on.
              </Typography>
            ) : null}
          </Box>

          <TextField
            fullWidth
            size="small"
            label="Reason for this change"
            value={form.changeReason}
            onChange={(event) => set('changeReason', event.target.value)}
            helperText="Recorded in the parameter's history, so a reading can be read against the definition in force when it was written"
          />
        </Stack>
      </DialogContent>

      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!canSave || saving} onClick={handleSave}>
          {editing ? 'Save changes' : 'Configure'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/** `motorTemp` → `motor_temp`, matching what the server would normalise it to. */
function toParameterName(payloadKey: string): string {
  return payloadKey
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .toLowerCase()
    .replace(/[\s\-.]+/g, '_')
    .replace(/_+/g, '_')
    .replace(/^_|_$/g, '');
}

/** Keeps the wire key only when normalising it would change it. */
function keyIfDifferent(payloadKey: string): string {
  return toParameterName(payloadKey) === payloadKey ? '' : payloadKey;
}
