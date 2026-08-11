import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { problemMessage } from '@/lib/api/problem';
import {
  useCommunicationTypes,
  useRegisterDevice,
  useUpdateDevice,
} from '../hooks/useDevices';
import type {
  CommunicationType,
  Device,
  DeviceProtocol,
  DeviceRequest,
  DeviceSource,
  DeviceStatus,
  DeviceType,
} from '../types';
import {
  COMMUNICATION_TYPE_LABELS,
  DEVICE_PROTOCOL_LABELS,
  DEVICE_PROTOCOLS,
  DEVICE_SOURCE_LABELS,
  DEVICE_SOURCES,
  DEVICE_STATUSES,
  DEVICE_STATUS_LABELS,
  DEVICE_TYPES,
  DEVICE_TYPE_LABELS,
} from '../labels';

/**
 * Register or edit a device.
 *
 * <p>The form has two halves. The core fields — identity, hardware, siting, lifecycle — are the
 * same for every device on the network and never move. Below them sits one block whose contents
 * come from the server's catalogue for the selected Communication Type: SIM/IMEI/Operator for
 * NB-IoT and 4G, DevEUI/JoinEUI/AppKey for LoRaWAN.
 *
 * <p>Device Source, Protocol and Communication Type sit together because they are genuinely three
 * questions — where the readings come from, how the packet arrives, and which network carries
 * them — and none constrains the others. A simulated meter emulates a real transport, so it still
 * declares one and still gets a network address; that is what makes it a stand-in for the device
 * it replaces rather than a special case the ingest path has to know about.
 *
 * That block is rendered from {@link useCommunicationTypes}, not from a table in this file. A
 * hard-coded copy would be a second declaration of the same rules, and the two would disagree the
 * first time a transport gained a field — the form would drop it silently and the device would
 * register as unreachable.
 */
export function DeviceFormDialog({
  open,
  onClose,
  editing,
}: {
  open: boolean;
  onClose: () => void;
  editing?: Device | null;
}) {
  const register = useRegisterDevice();
  const updateDevice = useUpdateDevice();
  const { data: commTypes, isLoading: commLoading, error: commError } = useCommunicationTypes();
  const [error, setError] = useState<string | null>(null);

  const [form, setForm] = useState<DeviceRequest>({
    deviceCode: '',
    name: '',
    deviceType: 'WATER_METER',
    deviceSource: 'LIVE',
    protocol: 'HTTP',
    communicationType: 'NB_IOT',
    status: 'PROVISIONED',
  });
  const [comm, setComm] = useState<Record<string, string>>({});
  const [lonLat, setLonLat] = useState('');

  useEffect(() => {
    if (!open) return;
    setError(null);
    if (editing) {
      setForm({
        deviceCode: editing.deviceCode,
        name: editing.name,
        deviceType: editing.deviceType,
        assetNumber: editing.assetNumber ?? '',
        deviceSource: editing.deviceSource,
        protocol: editing.protocol ?? 'HTTP',
        communicationType: editing.communicationType,
        manufacturer: editing.manufacturer ?? '',
        model: editing.model ?? '',
        serialNumber: editing.serialNumber ?? '',
        installationDate: editing.installationDate ?? '',
        status: editing.status,
      });
      // Secrets are never returned, so they start empty and are only sent if retyped.
      setComm({ ...editing.communication });
      setLonLat(editing.coordinates ? `${editing.coordinates[0]}, ${editing.coordinates[1]}` : '');
    } else {
      setForm({
        deviceCode: '',
        name: '',
        deviceType: 'WATER_METER',
        deviceSource: 'LIVE',
        protocol: 'HTTP',
        communicationType: 'NB_IOT',
        status: 'PROVISIONED',
      });
      setComm({});
      setLonLat('');
    }
  }, [open, editing]);

  const profile = useMemo(
    () => commTypes?.find((t) => t.id === form.communicationType),
    [commTypes, form.communicationType],
  );

  const patchForm = (patch: Partial<DeviceRequest>) => setForm((prev) => ({ ...prev, ...patch }));

  /*
   * Switching communication type discards the previous block. Carrying a DevEUI across to an
   * NB-IoT device would post a field the server rejects, and keeping it hidden-but-present is how
   * a device ends up addressed by an identifier its network cannot route.
   */
  const changeCommunicationType = (next: CommunicationType) => {
    patchForm({ communicationType: next });
    setComm({});
  };

  const submit = async () => {
    setError(null);

    const payload: DeviceRequest = {
      deviceCode: form.deviceCode?.trim(),
      name: form.name?.trim(),
      deviceType: form.deviceType,
      deviceSource: form.deviceSource,
      protocol: form.protocol,
      communicationType: form.communicationType,
      status: form.status,
      assetNumber: form.assetNumber?.trim() || undefined,
      manufacturer: form.manufacturer?.trim() || undefined,
      model: form.model?.trim() || undefined,
      serialNumber: form.serialNumber?.trim() || undefined,
      installationDate: form.installationDate || undefined,
      // Empty strings are dropped rather than sent: a blank secret means "unchanged", and a blank
      // optional field means "not set" — neither should reach the validator as "".
      communication: Object.fromEntries(
        Object.entries(comm).filter(([, value]) => value.trim().length > 0),
      ),
    };

    if (lonLat.trim()) {
      const [lonStr, latStr] = lonLat.split(',').map((s) => s?.trim() ?? '');
      const lon = parseFloat(lonStr ?? '');
      const lat = parseFloat(latStr ?? '');
      if (Number.isNaN(lon) || Number.isNaN(lat)) {
        setError('GIS Location must be "longitude, latitude".');
        return;
      }
      payload.coordinates = [lon, lat];
    }

    try {
      if (editing) {
        await updateDevice.mutateAsync({ id: editing.id, payload });
      } else {
        await register.mutateAsync(payload);
      }
      onClose();
    } catch (err) {
      setError(problemMessage(err));
    }
  };

  const pending = register.isPending || updateDevice.isPending;
  const missingRequiredComm = (profile?.fields ?? []).some(
    (field) =>
      field.required
      // A secret already on file satisfies its requirement without being retyped.
      && !(field.secret && editing?.communicationSecretsSet?.includes(field.key))
      && !comm[field.key]?.trim(),
  );

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>{editing ? 'Edit device' : 'Register device'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error ? <Alert severity="error" variant="outlined">{error}</Alert> : null}

          <Stack direction="row" spacing={2}>
            <TextField
              label="Device ID"
              value={form.deviceCode ?? ''}
              onChange={(e) => patchForm({ deviceCode: e.target.value })}
              required
              // Immutable after registration: it is what the work order quotes.
              disabled={Boolean(editing)}
              helperText={editing ? 'Cannot be changed after registration' : 'Unique within your organisation'}
              fullWidth
            />
            <TextField
              label="Device Name"
              value={form.name ?? ''}
              onChange={(e) => patchForm({ name: e.target.value })}
              required
              fullWidth
            />
          </Stack>

          <Stack direction="row" spacing={2}>
            <TextField
              select
              label="Device Type"
              value={form.deviceType ?? 'WATER_METER'}
              onChange={(e) => patchForm({ deviceType: e.target.value as DeviceType })}
              fullWidth
            >
              {DEVICE_TYPES.map((t) => (
                <MenuItem key={t} value={t}>{DEVICE_TYPE_LABELS[t]}</MenuItem>
              ))}
            </TextField>
            <TextField
              label="Asset Number"
              value={form.assetNumber ?? ''}
              onChange={(e) => patchForm({ assetNumber: e.target.value })}
              helperText="Code of the asset this device is fitted to"
              fullWidth
            />
          </Stack>

          <Stack direction="row" spacing={2}>
            <TextField
              select
              label="Device Source"
              value={form.deviceSource ?? 'LIVE'}
              onChange={(e) => patchForm({ deviceSource: e.target.value as DeviceSource })}
              helperText="Where readings come from"
              required
              fullWidth
            >
              {DEVICE_SOURCES.map((s) => (
                <MenuItem key={s} value={s}>{DEVICE_SOURCE_LABELS[s]}</MenuItem>
              ))}
            </TextField>
            <TextField
              select
              label="Protocol"
              value={form.protocol ?? ''}
              onChange={(e) => patchForm({ protocol: e.target.value as DeviceProtocol })}
              helperText="HTTP or MQTT"
              required
              fullWidth
            >
              {DEVICE_PROTOCOLS.map((p) => (
                <MenuItem key={p} value={p}>{DEVICE_PROTOCOL_LABELS[p]}</MenuItem>
              ))}
            </TextField>
            <TextField
              select
              label="Network"
              value={form.communicationType ?? 'NB_IOT'}
              onChange={(e) => changeCommunicationType(e.target.value as CommunicationType)}
              disabled={commLoading}
              helperText={commError ? 'Could not load networks.' : 'The radio or wire'}
              error={Boolean(commError)}
              required
              fullWidth
            >
              {(commTypes ?? []).map((t) => (
                <MenuItem key={t.id} value={t.id}>
                  {COMMUNICATION_TYPE_LABELS[t.id] ?? t.id}
                </MenuItem>
              ))}
            </TextField>
          </Stack>

          <Stack direction="row" spacing={2}>
            <TextField
              label="Manufacturer"
              value={form.manufacturer ?? ''}
              onChange={(e) => patchForm({ manufacturer: e.target.value })}
              fullWidth
            />
            <TextField
              label="Model"
              value={form.model ?? ''}
              onChange={(e) => patchForm({ model: e.target.value })}
              fullWidth
            />
          </Stack>

          <Stack direction="row" spacing={2}>
            <TextField
              label="Serial Number"
              value={form.serialNumber ?? ''}
              onChange={(e) => patchForm({ serialNumber: e.target.value })}
              fullWidth
            />
            <TextField
              label="Installation Date"
              type="date"
              value={form.installationDate ?? ''}
              onChange={(e) => patchForm({ installationDate: e.target.value })}
              InputLabelProps={{ shrink: true }}
              fullWidth
            />
          </Stack>

          <Stack direction="row" spacing={2}>
            <TextField
              select
              label="Status"
              value={form.status ?? 'PROVISIONED'}
              onChange={(e) => patchForm({ status: e.target.value as DeviceStatus })}
              fullWidth
            >
              {DEVICE_STATUSES.map((s) => (
                <MenuItem key={s} value={s}>{DEVICE_STATUS_LABELS[s]}</MenuItem>
              ))}
            </TextField>
            <TextField
              label="GIS Location (lon, lat)"
              value={lonLat}
              onChange={(e) => setLonLat(e.target.value)}
              helperText="Decimal degrees, e.g. 76.9366, 8.5241"
              fullWidth
            />
          </Stack>

          {profile && profile.fields.length > 0 ? (
            <>
              <Divider />
              <Typography variant="subtitle2" color="text.secondary">
                {COMMUNICATION_TYPE_LABELS[profile.id] ?? profile.id} network details
              </Typography>
              {profile.fields.map((field) => {
                const onFile = field.secret
                  && Boolean(editing?.communicationSecretsSet?.includes(field.key));
                return (
                  <TextField
                    key={field.key}
                    label={field.label}
                    value={comm[field.key] ?? ''}
                    onChange={(e) =>
                      setComm((prev) => ({ ...prev, [field.key]: e.target.value }))}
                    required={field.required && !onFile}
                    // Secrets are write-only. The value is never returned, so the field starts
                    // empty on edit and leaving it empty keeps whatever is already stored.
                    type={field.secret ? 'password' : 'text'}
                    autoComplete={field.secret ? 'new-password' : 'off'}
                    helperText={onFile ? 'Stored — leave blank to keep it' : field.expectation}
                    fullWidth
                  />
                );
              })}
            </>
          ) : null}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          disabled={
            pending
            || !form.deviceCode?.trim()
            || !form.name?.trim()
            || !form.deviceSource
            || !form.protocol
            || !form.communicationType
            || missingRequiredComm
          }
          onClick={submit}
        >
          {pending ? 'Saving…' : editing ? 'Save' : 'Register'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
