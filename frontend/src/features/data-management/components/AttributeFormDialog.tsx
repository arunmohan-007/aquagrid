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
  MenuItem,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import { toProblem } from '@/lib/api/problem';
import { useCreateAttribute, useDataTypes, useReservedWords, useUpdateAttribute } from '../hooks/useDataManagement';
import { lockReason } from '../labels';
import type { LayerAttribute, MetadataLayer } from '../types';

/** Field names are identifiers, not labels. Mirrors FieldNamePolicy on the server. */
const FIELD_NAME = /^[a-z][a-z0-9_]*$/;
const MAX_FIELD_NAME = 63;

interface FormState {
  layerId: string;
  fieldName: string;
  displayName: string;
  description: string;
  dataType: string;
  maxLength: string;
  numericPrecision: string;
  numericScale: string;
  defaultValue: string;
  sampleValue: string;
  mandatory: boolean;
  unique: boolean;
  duplicateCheck: boolean;
  editable: boolean;
  visible: boolean;
  active: boolean;
  changeReason: string;
}

const EMPTY: FormState = {
  layerId: '',
  fieldName: '',
  displayName: '',
  description: '',
  dataType: 'TEXT',
  maxLength: '',
  numericPrecision: '',
  numericScale: '',
  defaultValue: '',
  sampleValue: '',
  mandatory: false,
  unique: false,
  duplicateCheck: false,
  editable: true,
  visible: true,
  active: true,
  changeReason: '',
};

/**
 * Create or edit an attribute.
 *
 * <p>The type-dependent half of the form — Length for text, Precision and Scale for decimals — is
 * rendered from `GET /data-management/data-types`, not from a table in this file. The server
 * decides which types exist and which configuration each one takes; a second copy here would
 * eventually offer a length field for a boolean, or hide one the server requires.
 *
 * <p>Renaming a field or changing its type is allowed and is not silent. The first submit is
 * refused by the server with `ATTRIBUTE_CHANGE_REQUIRES_CONFIRMATION` and a sentence describing
 * what the change does to data that already exists; this dialog shows that sentence — the
 * server's, not an invented one — and only then offers to proceed. A generic "Are you sure?"
 * would train people to click through the one dialog that matters.
 */
export function AttributeFormDialog({
  open,
  onClose,
  layers,
  defaultLayerId,
  editing,
}: {
  open: boolean;
  onClose: () => void;
  layers: MetadataLayer[];
  defaultLayerId?: string | undefined;
  editing?: LayerAttribute | null | undefined;
}) {
  const create = useCreateAttribute();
  const update = useUpdateAttribute();
  const { data: dataTypes } = useDataTypes();
  const { data: reservedWords } = useReservedWords();

  const [form, setForm] = useState<FormState>(EMPTY);
  const [error, setError] = useState<string | null>(null);
  /** The server's own description of what a rename or retype will do. Null until it says so. */
  const [breakingWarning, setBreakingWarning] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setError(null);
    setBreakingWarning(null);
    if (editing) {
      setForm({
        layerId: editing.layerId,
        fieldName: editing.fieldName,
        displayName: editing.displayName,
        description: editing.description ?? '',
        dataType: editing.dataType,
        maxLength: editing.maxLength?.toString() ?? '',
        numericPrecision: editing.numericPrecision?.toString() ?? '',
        numericScale: editing.numericScale?.toString() ?? '',
        defaultValue: editing.defaultValue ?? '',
        sampleValue: editing.sampleValue ?? '',
        mandatory: editing.mandatory,
        unique: editing.unique,
        duplicateCheck: editing.duplicateCheck,
        editable: editing.editable,
        visible: editing.visible,
        active: editing.active,
        changeReason: '',
      });
    } else {
      setForm({ ...EMPTY, layerId: defaultLayerId ?? layers[0]?.id ?? '' });
    }
  }, [open, editing, defaultLayerId, layers]);

  const selectedType = useMemo(
    () => dataTypes?.find((type) => type.code === form.dataType),
    [dataTypes, form.dataType],
  );
  const locked = lockReason(editing ?? null);
  const isSystem = Boolean(editing?.system);
  const identityLocked = isSystem || editing?.storage === 'TYPE_TABLE';

  const set = <K extends keyof FormState>(key: K, value: FormState[K]) =>
    setForm((current) => ({ ...current, [key]: value }));

  // ---- Field-name validation, shown while typing rather than after a round trip ---------------
  const normalisedName = form.fieldName.trim().toLowerCase();
  const nameIsReserved = Boolean(reservedWords?.includes(normalisedName));
  const nameError = (() => {
    if (editing) return null;
    if (!normalisedName) return null;
    if (normalisedName.length > MAX_FIELD_NAME) {
      return `Field names are at most ${MAX_FIELD_NAME} characters.`;
    }
    if (!FIELD_NAME.test(normalisedName)) {
      return 'Letters, numbers and underscore only, starting with a letter — for example consumer_no.';
    }
    if (nameIsReserved) {
      return `'${normalisedName}' is a reserved word. Try ${normalisedName}_code.`;
    }
    return null;
  })();

  const renaming = Boolean(editing) && normalisedName !== editing?.fieldName;
  const retyping = Boolean(editing) && form.dataType !== editing?.dataType;

  const submit = async (confirmBreakingChange: boolean) => {
    setError(null);
    const numberOrNull = (value: string) => (value.trim() === '' ? null : Number(value));

    try {
      if (editing) {
        await update.mutateAsync({
          id: editing.id,
          payload: {
            fieldName: identityLocked ? undefined : normalisedName,
            displayName: form.displayName.trim(),
            description: form.description.trim(),
            dataType: identityLocked ? undefined : form.dataType,
            maxLength: selectedType?.usesLength ? numberOrNull(form.maxLength) : undefined,
            numericPrecision: selectedType?.usesPrecisionScale
              ? numberOrNull(form.numericPrecision)
              : undefined,
            numericScale: selectedType?.usesPrecisionScale
              ? numberOrNull(form.numericScale)
              : undefined,
            defaultValue: form.defaultValue.trim(),
            sampleValue: form.sampleValue.trim(),
            // Mandatory, unique and duplicate-check are locked on system fields, so they are not
            // sent for one — the server refuses, and asking it to is a wasted round trip.
            mandatory: isSystem ? undefined : form.mandatory,
            unique: isSystem ? undefined : form.unique,
            duplicateCheck: isSystem ? undefined : form.duplicateCheck,
            editable: form.editable,
            visible: form.visible,
            confirmBreakingChange,
            changeReason: form.changeReason.trim() || undefined,
          },
        });
      } else {
        await create.mutateAsync({
          layerId: form.layerId,
          fieldName: normalisedName,
          displayName: form.displayName.trim() || undefined,
          description: form.description.trim() || undefined,
          dataType: form.dataType,
          maxLength: selectedType?.usesLength ? numberOrNull(form.maxLength) : null,
          numericPrecision: selectedType?.usesPrecisionScale
            ? numberOrNull(form.numericPrecision)
            : null,
          numericScale: selectedType?.usesPrecisionScale ? numberOrNull(form.numericScale) : null,
          defaultValue: form.defaultValue.trim() || undefined,
          sampleValue: form.sampleValue.trim() || undefined,
          mandatory: form.mandatory,
          unique: form.unique,
          duplicateCheck: form.duplicateCheck,
          editable: form.editable,
          visible: form.visible,
          active: form.active,
          changeReason: form.changeReason.trim() || undefined,
        });
      }
      onClose();
    } catch (cause) {
      const problem = toProblem(cause);
      if (problem.code === 'ATTRIBUTE_CHANGE_REQUIRES_CONFIRMATION') {
        // The server's sentence, verbatim. It names the field, the old and new values and what
        // happens to stored data — none of which the client could reconstruct correctly.
        setBreakingWarning(problem.detail ?? 'This change affects existing data.');
        return;
      }
      setError(problem.detail ?? problem.title ?? 'Could not save the attribute.');
    }
  };

  const busy = create.isPending || update.isPending;
  const canSubmit =
    !busy &&
    Boolean(form.layerId) &&
    Boolean(normalisedName) &&
    !nameError &&
    Boolean(form.dataType) &&
    !(editing?.storage === 'TYPE_TABLE');

  return (
    <Dialog open={open} onClose={busy ? undefined : onClose} maxWidth="md" fullWidth>
      <DialogTitle>
        {editing ? `Edit ${editing.displayName}` : 'Add attribute'}
        <Typography variant="body2" color="text.secondary">
          {editing
            ? 'Changes take effect on the next import, export and form render.'
            : 'Available to the import mapper, the exporter and asset forms as soon as it is saved — no release needed.'}
        </Typography>
      </DialogTitle>

      <DialogContent dividers>
        <Stack spacing={2.5}>
          {locked ? (
            <Alert severity="info" variant="outlined">
              {locked}
            </Alert>
          ) : null}
          {error ? (
            <Alert severity="error" variant="outlined">
              {error}
            </Alert>
          ) : null}
          {breakingWarning ? (
            <Alert severity="warning" variant="outlined">
              <Typography variant="body2" sx={{ fontWeight: 600, mb: 0.5 }}>
                This change affects existing data
              </Typography>
              <Typography variant="body2">{breakingWarning}</Typography>
            </Alert>
          ) : null}

          {/* ---- Identity ---------------------------------------------------------------- */}
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              select
              label="Layer"
              size="small"
              fullWidth
              value={form.layerId}
              // The layer decides which asset rows the field appears on; moving one afterwards
              // would orphan every value already written under it.
              disabled={Boolean(editing)}
              onChange={(e) => set('layerId', e.target.value)}
              helperText={editing ? 'A field cannot be moved between layers.' : undefined}
            >
              {layers.map((layer) => (
                <MenuItem key={layer.id} value={layer.id}>
                  {layer.title}
                </MenuItem>
              ))}
            </TextField>

            <TextField
              label="Field Name"
              size="small"
              fullWidth
              required
              value={form.fieldName}
              disabled={identityLocked}
              onChange={(e) => set('fieldName', e.target.value)}
              error={Boolean(nameError)}
              helperText={
                nameError ??
                (identityLocked
                  ? 'Locked — the platform reads this field by name.'
                  : 'Lower case, no spaces. This is the key the value is stored and exported under.')
              }
              inputProps={{ maxLength: MAX_FIELD_NAME, spellCheck: false }}
            />
          </Stack>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Display Name"
              size="small"
              fullWidth
              value={form.displayName}
              onChange={(e) => set('displayName', e.target.value)}
              helperText="Shown on forms, grids and export headings. Defaults to the field name."
            />
            <TextField
              select
              label="Data Type"
              size="small"
              fullWidth
              value={form.dataType}
              disabled={identityLocked}
              onChange={(e) => set('dataType', e.target.value)}
              helperText={selectedType?.description ?? ' '}
            >
              {(dataTypes ?? []).map((type) => (
                <MenuItem key={type.code} value={type.code} disabled={!type.selectable && !editing}>
                  {type.label}
                </MenuItem>
              ))}
            </TextField>
          </Stack>

          <TextField
            label="Description"
            size="small"
            fullWidth
            multiline
            minRows={2}
            value={form.description}
            onChange={(e) => set('description', e.target.value)}
            helperText="What this field holds, in a sentence. This is what a contractor reads in the exported data dictionary."
          />

          {/* ---- Type configuration, driven by the server's catalogue -------------------- */}
          {selectedType?.usesLength || selectedType?.usesPrecisionScale ? (
            <>
              <Divider />
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                {selectedType.usesLength ? (
                  <TextField
                    label="Length"
                    size="small"
                    type="number"
                    fullWidth
                    value={form.maxLength}
                    onChange={(e) => set('maxLength', e.target.value)}
                    helperText="Maximum characters. Defaults to 255."
                    inputProps={{ min: 1 }}
                  />
                ) : null}
                {selectedType.usesPrecisionScale ? (
                  <>
                    <TextField
                      label="Precision"
                      size="small"
                      type="number"
                      fullWidth
                      value={form.numericPrecision}
                      onChange={(e) => set('numericPrecision', e.target.value)}
                      helperText="Total significant digits. Defaults to 12."
                      inputProps={{ min: 1 }}
                    />
                    <TextField
                      label="Scale"
                      size="small"
                      type="number"
                      fullWidth
                      value={form.numericScale}
                      onChange={(e) => set('numericScale', e.target.value)}
                      helperText="Digits after the point. Cannot exceed precision."
                      inputProps={{ min: 0 }}
                    />
                  </>
                ) : null}
              </Stack>
            </>
          ) : null}

          <Divider />

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Default Value"
              size="small"
              fullWidth
              value={form.defaultValue}
              onChange={(e) => set('defaultValue', e.target.value)}
              helperText="Used when an import leaves the field empty. Must be valid for the type."
            />
            <TextField
              label="Sample Value"
              size="small"
              fullWidth
              value={form.sampleValue}
              onChange={(e) => set('sampleValue', e.target.value)}
              helperText={selectedType ? `For example: ${selectedType.sampleValue}` : ' '}
            />
          </Stack>

          {/* ---- Flags ------------------------------------------------------------------ */}
          <Box
            sx={{
              display: 'grid',
              gap: 0.5,
              gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))' },
            }}
          >
            <FlagSwitch
              label="Mandatory"
              hint="An import row without it fails."
              checked={form.mandatory}
              disabled={isSystem}
              onChange={(value) => set('mandatory', value)}
            />
            <FlagSwitch
              label="Unique"
              hint="No two assets on this layer may share a value."
              checked={form.unique}
              disabled={isSystem}
              onChange={(value) => set('unique', value)}
            />
            <FlagSwitch
              label="Consider for duplicate check"
              hint="Bulk imports use flagged fields (separately from Asset Code) to recognise a row already on file, and update it instead of adding a copy. Flag one field to match on it alone; flag several and a row must agree with an existing asset on all of them together."
              checked={form.duplicateCheck}
              disabled={isSystem}
              onChange={(value) => set('duplicateCheck', value)}
            />
            <FlagSwitch
              label="Editable"
              hint="Users may change the value after import."
              checked={form.editable}
              onChange={(value) => set('editable', value)}
            />
            <FlagSwitch
              label="Visible"
              hint="Included in exports and shown on forms by default."
              checked={form.visible}
              onChange={(value) => set('visible', value)}
            />
            {!editing ? (
              <FlagSwitch
                label="Active"
                hint="Inactive fields are hidden from imports and exports."
                checked={form.active}
                onChange={(value) => set('active', value)}
              />
            ) : null}
          </Box>

          {renaming || retyping ? (
            <TextField
              label="Reason for the change"
              size="small"
              fullWidth
              value={form.changeReason}
              onChange={(e) => set('changeReason', e.target.value)}
              helperText="Stored on the attribute's history. This is what someone reading the change in two years has to go on."
            />
          ) : null}
        </Stack>
      </DialogContent>

      <DialogActions sx={{ px: 3, py: 2 }}>
        <Button onClick={onClose} disabled={busy}>
          Cancel
        </Button>
        {breakingWarning ? (
          <Button variant="contained" color="warning" disabled={busy} onClick={() => submit(true)}>
            Change anyway
          </Button>
        ) : (
          <Button variant="contained" disabled={!canSubmit} onClick={() => submit(false)}>
            {editing ? 'Save changes' : 'Add attribute'}
          </Button>
        )}
      </DialogActions>
    </Dialog>
  );
}

function FlagSwitch({
  label,
  hint,
  checked,
  disabled,
  onChange,
}: {
  label: string;
  hint: string;
  checked: boolean;
  disabled?: boolean;
  onChange: (value: boolean) => void;
}) {
  return (
    <FormControlLabel
      sx={{ alignItems: 'flex-start', m: 0, py: 0.5 }}
      control={
        <Switch
          size="small"
          checked={checked}
          disabled={disabled}
          onChange={(e) => onChange(e.target.checked)}
          sx={{ mr: 1 }}
        />
      }
      label={
        <Box>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            {label}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {hint}
          </Typography>
        </Box>
      }
    />
  );
}
