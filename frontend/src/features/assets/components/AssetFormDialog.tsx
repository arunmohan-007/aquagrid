import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
} from '@mui/material';
import { problemMessage } from '@/lib/api/problem';
import { useCreateAsset, useUpdateAsset } from '../hooks/useAssets';
import type { Asset, AssetRequest, AssetStatus, AssetType } from '../types';
import { ASSET_TYPE_LABELS } from '../labels';

const TYPES: AssetType[] = ['METER', 'VALVE', 'PIPELINE', 'HYDRANT', 'TANK', 'RESERVOIR', 'PUMP_STATION', 'OPEN_WELL', 'BORE_WELL', 'DMA', 'PANCHAYAT', 'SERVICE_CONNECTION', 'SENSOR'];
const STATUSES: AssetStatus[] = ['PLANNED', 'IN_SERVICE', 'OUT_OF_SERVICE', 'DECOMMISSIONED', 'DAMAGED'];

/**
 * Create/edit dialog. Geometry input accepts "lon, lat" for points — the common case for assets
 * created from the register. Lines/polygons come from the map editor or bulk import.
 */
export function AssetFormDialog({
  open,
  onClose,
  editing,
}: {
  open: boolean;
  onClose: () => void;
  editing?: Asset | null;
}) {
  const create = useCreateAsset();
  const updateAsset = useUpdateAsset();
  const [error, setError] = useState<string | null>(null);

  const [form, setForm] = useState<AssetRequest>({
    assetCode: '',
    assetType: 'METER',
    name: '',
    status: 'IN_SERVICE',
  });
  const [lonLat, setLonLat] = useState('');

  useEffect(() => {
    if (!open) return;
    setError(null);
    if (editing) {
      setForm({
        assetCode: editing.assetCode,
        assetType: editing.assetType,
        name: editing.name,
        status: editing.status,
      });
      setLonLat(editing.coordinates ? `${editing.coordinates[0]}, ${editing.coordinates[1]}` : '');
    } else {
      setForm({ assetCode: '', assetType: 'METER', name: '', status: 'IN_SERVICE' });
      setLonLat('');
    }
  }, [open, editing]);

  const patchForm = (patch: Partial<AssetRequest>) => setForm((prev) => ({ ...prev, ...patch }));

  const submit = async () => {
    setError(null);
    const payload: AssetRequest = { ...form };
    if (lonLat.trim()) {
      const [lonStr, latStr] = lonLat.split(',').map((s) => s?.trim() ?? '');
      const lon = parseFloat(lonStr ?? '');
      const lat = parseFloat(latStr ?? '');
      if (Number.isNaN(lon) || Number.isNaN(lat)) {
        setError('Coordinates must be "longitude, latitude".');
        return;
      }
      payload.geometry = { type: 'Point', coordinates: [lon, lat] };
    }
    try {
      if (editing) {
        await updateAsset.mutateAsync({ id: editing.id, payload });
      } else {
        await create.mutateAsync(payload);
      }
      onClose();
    } catch (err) {
      setError(problemMessage(err));
    }
  };

  const pending = create.isPending || updateAsset.isPending;

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{editing ? 'Edit asset' : 'New asset'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error ? <Alert severity="error" variant="outlined">{error}</Alert> : null}
          <Stack direction="row" spacing={2}>
            <TextField
              label="Asset code"
              value={form.assetCode ?? ''}
              onChange={(e) => patchForm({ assetCode: e.target.value })}
              required
              disabled={Boolean(editing)}
              fullWidth
            />
            <TextField
              select
              label="Type"
              value={form.assetType ?? 'METER'}
              onChange={(e) => patchForm({ assetType: e.target.value as AssetType })}
              disabled={Boolean(editing)}
              fullWidth
            >
              {TYPES.map((t) => (
                <MenuItem key={t} value={t}>{ASSET_TYPE_LABELS[t]}</MenuItem>
              ))}
            </TextField>
          </Stack>
          <TextField
            label="Name"
            value={form.name ?? ''}
            onChange={(e) => patchForm({ name: e.target.value })}
            required
            fullWidth
          />
          <Stack direction="row" spacing={2}>
            <TextField
              select
              label="Status"
              value={form.status ?? 'IN_SERVICE'}
              onChange={(e) => patchForm({ status: e.target.value as AssetStatus })}
              fullWidth
            >
              {STATUSES.map((s) => (
                <MenuItem key={s} value={s}>{s.replaceAll('_', ' ')}</MenuItem>
              ))}
            </TextField>
            <TextField
              label="Location (lon, lat)"
              value={lonLat}
              onChange={(e) => setLonLat(e.target.value)}
              helperText="Decimal degrees, e.g. 76.9366, 8.5241"
              fullWidth
            />
          </Stack>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          disabled={pending || !form.assetCode || !form.name}
          onClick={submit}
        >
          {pending ? 'Saving…' : editing ? 'Save' : 'Create'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
