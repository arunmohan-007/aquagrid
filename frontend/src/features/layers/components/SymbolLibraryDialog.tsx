import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  Stack,
  Tooltip,
  Typography,
} from '@mui/material';
import UploadIcon from '@mui/icons-material/FileUploadOutlined';
import DeleteIcon from '@mui/icons-material/DeleteOutlineOutlined';
import { useAuth } from '@/lib/auth/AuthProvider';
import { problemMessage } from '@/lib/api/problem';
import { useDeleteSymbol, useMapSymbols } from '../hooks/useLayers';
import { IconPreview } from './IconPicker';
import { SymbolUploadDialog } from './SymbolUploadDialog';

/**
 * Manages the tenant's uploaded symbols, independently of any one style.
 *
 * This exists because the upload had no discoverable home. It was reachable only from inside the
 * icon picker, which is itself reachable only after switching a *point* layer's render mode to
 * Icon — so uploading a symbol meant six steps behind a setting you had to know to change first,
 * and on a line or polygon layer there was no route to it at all, because those geometries have no
 * `renderMode` control for the picker to hang off.
 *
 * A symbol belongs to the organisation rather than to a style, so it gets a surface that matches:
 * one button on the Layer Styles page, always visible, whatever layer happens to be selected.
 */
export function SymbolLibraryDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { hasPermission } = useAuth();
  const canManage = hasPermission('gis:style:manage');
  const { data: symbols, isLoading } = useMapSymbols();
  const remove = useDeleteSymbol();
  const [uploadOpen, setUploadOpen] = useState(false);
  const [confirming, setConfirming] = useState<string | null>(null);

  return (
    <>
      <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
        <DialogTitle sx={{ pb: 1 }}>
          <Stack direction="row" alignItems="center" spacing={1.5}>
            <Box sx={{ flex: 1 }}>
              <Typography variant="h6" sx={{ fontWeight: 700 }}>
                Symbol library
              </Typography>
              <Typography variant="caption" sx={{ opacity: 0.7 }}>
                Your organisation&apos;s own markers. Available to every point style on every layer.
              </Typography>
            </Box>
            {canManage ? (
              <Button
                variant="contained"
                size="small"
                startIcon={<UploadIcon />}
                onClick={() => setUploadOpen(true)}
              >
                Upload
              </Button>
            ) : null}
          </Stack>
        </DialogTitle>
        <DialogContent dividers>
          {remove.error ? (
            <Alert severity="error" sx={{ mb: 2 }}>
              {problemMessage(remove.error)}
            </Alert>
          ) : null}

          {isLoading ? (
            <Typography variant="body2" sx={{ opacity: 0.7, py: 4, textAlign: 'center' }}>
              Loading…
            </Typography>
          ) : (symbols ?? []).length === 0 ? (
            <Stack spacing={1.5} sx={{ py: 5 }} alignItems="center">
              <Typography variant="body2" sx={{ opacity: 0.75, textAlign: 'center', maxWidth: 460 }}>
                Nothing uploaded yet. SVG and PNG are accepted, up to 512 KB. Every SVG is sanitised
                on the way in — scripts, event handlers and external references are stripped — and
                drawn by the map as an image rather than as markup.
              </Typography>
              <Typography variant="caption" sx={{ opacity: 0.6, textAlign: 'center', maxWidth: 460 }}>
                You may not need to: the built-in shapes and the free Maki and Material libraries are
                already available in every icon picker.
              </Typography>
            </Stack>
          ) : (
            <Box
              sx={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))',
                gap: 1.5,
              }}
            >
              {(symbols ?? []).map((symbol) => (
                <Box
                  key={symbol.id}
                  sx={{
                    border: (theme) => `1px solid ${theme.palette.divider}`,
                    borderRadius: 2,
                    p: 1.5,
                    position: 'relative',
                  }}
                >
                  <Stack alignItems="center" spacing={1}>
                    <IconPreview icon={symbol.iconName} size={40} />
                    <Typography
                      variant="body2"
                      sx={{
                        fontWeight: 600,
                        textAlign: 'center',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                        width: '100%',
                      }}
                      title={symbol.name}
                    >
                      {symbol.name}
                    </Typography>
                    <Stack direction="row" spacing={0.5}>
                      <Chip size="small" variant="outlined" label={symbol.format} sx={{ height: 18, fontSize: 10 }} />
                      <Chip
                        size="small"
                        variant="outlined"
                        label={symbol.tintable ? 'Tintable' : 'Full colour'}
                        sx={{ height: 18, fontSize: 10 }}
                      />
                    </Stack>
                    <Typography variant="caption" sx={{ opacity: 0.6 }}>
                      {Math.max(1, Math.round(symbol.sizeBytes / 1024))} KB
                    </Typography>
                  </Stack>

                  {canManage ? (
                    <Tooltip
                      title={
                        confirming === symbol.id
                          ? 'Click again to delete'
                          : 'Delete this symbol'
                      }
                    >
                      <IconButton
                        size="small"
                        color={confirming === symbol.id ? 'error' : 'default'}
                        sx={{ position: 'absolute', top: 4, right: 4 }}
                        onClick={() => {
                          /*
                           * Two clicks rather than a confirmation dialog. Deleting a symbol is
                           * reversible by re-uploading the file, but it is not free: a style still
                           * referencing it will draw no marker at all, because MapLibre renders
                           * nothing for a missing image and reports no error. A second click is
                           * proportionate to that — a modal would not make anyone read it.
                           */
                          if (confirming === symbol.id) {
                            remove.mutate(symbol.id);
                            setConfirming(null);
                          } else {
                            setConfirming(symbol.id);
                          }
                        }}
                      >
                        <DeleteIcon fontSize="small" />
                      </IconButton>
                    </Tooltip>
                  ) : null}
                </Box>
              ))}
            </Box>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Close</Button>
        </DialogActions>
      </Dialog>

      <SymbolUploadDialog
        open={uploadOpen}
        onClose={() => setUploadOpen(false)}
        onUploaded={() => setUploadOpen(false)}
      />
    </>
  );
}
