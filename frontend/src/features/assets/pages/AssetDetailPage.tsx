import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import ArrowBackIcon from '@mui/icons-material/ArrowBackOutlined';
import EditIcon from '@mui/icons-material/EditOutlined';
import DeleteIcon from '@mui/icons-material/DeleteOutlined';
import AttachFileIcon from '@mui/icons-material/AttachFileOutlined';
import UploadIcon from '@mui/icons-material/UploadOutlined';
import { problemMessage } from '@/lib/api/problem';
import {
  useAsset,
  useAttachments,
  useDeleteAsset,
  useUploadAttachment,
  useDeleteAttachment,
} from '../hooks/useAssets';
import { assetsApi } from '../api/assetsApi';
import { AssetStatusChip } from '../components/AssetStatusChip';
import { AssetFormDialog } from '../components/AssetFormDialog';
import { assetTypeLabel } from '../labels';

export default function AssetDetailPage() {
  const { assetId = '' } = useParams();
  const navigate = useNavigate();
  const { data: asset, isLoading, error } = useAsset(assetId);
  const { data: attachments, refetch: refetchAttachments } = useAttachments(assetId);
  const deleteAsset = useDeleteAsset();
  const uploadAttachment = useUploadAttachment();
  const deleteAttachment = useDeleteAttachment();

  const [editOpen, setEditOpen] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  if (isLoading) return <Typography variant="body2" color="text.secondary">Loading…</Typography>;
  if (error || !asset) {
    return <Alert severity="error" variant="outlined">Could not load this asset. {problemMessage(error)}</Alert>;
  }

  const handleDelete = async () => {
    try {
      await deleteAsset.mutateAsync(assetId);
      navigate('/assets');
    } catch (err) {
      setMessage(problemMessage(err));
    }
  };

  const onUpload = async (file: File) => {
    try {
      await uploadAttachment.mutateAsync({ assetId, file });
      refetchAttachments();
    } catch (err) {
      setMessage(problemMessage(err));
    }
  };

  return (
    <Stack spacing={2.5}>
      <Stack direction="row" alignItems="center" spacing={1}>
        <IconButton onClick={() => navigate('/assets')} aria-label="Back to assets">
          <ArrowBackIcon />
        </IconButton>
        <Box>
          <Typography variant="h1">{asset.name}</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ fontFamily: 'monospace' }}>
            {asset.assetCode} · {assetTypeLabel(asset.assetType)}
          </Typography>
        </Box>
      </Stack>

      {message ? <Alert severity="error" variant="outlined" onClose={() => setMessage(null)}>{message}</Alert> : null}

      <Stack direction={{ xs: 'column', md: 'row' }} spacing={2.5}>
        <Card variant="outlined" sx={{ flex: 2 }}>
          <CardContent>
            <Stack spacing={2}>
              <Typography variant="h3">Details</Typography>
              <Row label="Status"><AssetStatusChip status={asset.status} /></Row>
              <Row label="Type">{assetTypeLabel(asset.assetType)}</Row>
              <Row label="Installed">{asset.installDate ?? '—'}</Row>
              <Row label="Location">
                {asset.coordinates
                  ? `${asset.coordinates[1].toFixed(6)}, ${asset.coordinates[0].toFixed(6)}`
                  : asset.geometryType ?? '—'}
              </Row>
              <Row label="Created">{new Date(asset.createdAt).toLocaleString()}</Row>
            </Stack>
          </CardContent>
        </Card>

        <Card variant="outlined" sx={{ flex: 3 }}>
          <CardContent>
            <Stack spacing={1.5}>
              <Typography variant="h3">Attributes</Typography>
              {Object.keys(asset.attributes).length === 0 ? (
                <Typography variant="caption" color="text.secondary">
                  No custom attributes.
                </Typography>
              ) : (
                <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap>
                  {Object.entries(asset.attributes).map(([k, v]) => (
                    <Chip
                      key={k}
                      size="small"
                      variant="outlined"
                      label={`${k}: ${String(v)}`}
                      sx={{ fontFamily: 'monospace', fontSize: 11 }}
                    />
                  ))}
                </Stack>
              )}
            </Stack>
          </CardContent>
        </Card>
      </Stack>

      <Card variant="outlined">
        <CardContent>
          <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={2} mb={2}>
            <Stack direction="row" spacing={1} alignItems="center">
              <AttachFileIcon color="primary" fontSize="small" />
              <Typography variant="h3">Attachments</Typography>
            </Stack>
            <Stack direction="row" spacing={1}>
              <Button
                size="small"
                variant="outlined"
                startIcon={<EditIcon />}
                onClick={() => setEditOpen(true)}
              >
                Edit
              </Button>
              <Button
                size="small"
                variant="outlined"
                color="error"
                startIcon={<DeleteIcon />}
                onClick={handleDelete}
                disabled={deleteAsset.isPending}
              >
                Delete
              </Button>
            </Stack>
          </Stack>
          <UploadRow onUpload={onUpload} />
          <TableContainer sx={{ mt: 1 }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>File</TableCell>
                  <TableCell>Type</TableCell>
                  <TableCell>Size</TableCell>
                  <TableCell>Uploaded</TableCell>
                  <TableCell />
                </TableRow>
              </TableHead>
              <TableBody>
                {(attachments ?? []).map((a) => (
                  <TableRow key={a.id} hover>
                    <TableCell>{a.fileName}</TableCell>
                    <TableCell><Typography variant="caption">{a.contentType}</Typography></TableCell>
                    <TableCell><Typography variant="caption">{(a.sizeBytes / 1024).toFixed(1)} KB</Typography></TableCell>
                    <TableCell><Typography variant="caption">{new Date(a.uploadedAt).toLocaleString()}</Typography></TableCell>
                    <TableCell align="right">
                      <Button size="small" href={assetsApi.downloadAttachmentUrl(a.id)} target="_blank">Open</Button>
                      <IconButton
                        size="small"
                        aria-label="Delete attachment"
                        onClick={async () => {
                          await deleteAttachment.mutateAsync(a.id);
                          refetchAttachments();
                        }}
                      >
                        <DeleteIcon fontSize="small" />
                      </IconButton>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        </CardContent>
      </Card>

      <AssetFormDialog open={editOpen} onClose={() => setEditOpen(false)} editing={asset} />
    </Stack>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <Stack direction="row" spacing={2} justifyContent="space-between" alignItems="center">
      <Typography variant="body2" color="text.secondary">{label}</Typography>
      <Box>{children}</Box>
    </Stack>
  );
}

function UploadRow({ onUpload }: { onUpload: (file: File) => void }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button size="small" startIcon={<UploadIcon />} onClick={() => setOpen(true)}>Upload</Button>
      <Dialog open={open} onClose={() => setOpen(false)} fullWidth maxWidth="xs">
        <DialogTitle>Upload attachment</DialogTitle>
        <DialogContent>
          <input
            type="file"
            onChange={(e) => {
              const f = e.target.files?.[0];
              if (f) onUpload(f);
              setOpen(false);
            }}
          />
        </DialogContent>
        <DialogActions><Button onClick={() => setOpen(false)}>Cancel</Button></DialogActions>
      </Dialog>
    </>
  );
}
