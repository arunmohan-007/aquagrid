import { useRef, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import UploadIcon from '@mui/icons-material/FileUploadOutlined';
import { problemMessage } from '@/lib/api/problem';
import { useUploadSymbol } from '../hooks/useLayers';

/** What the server accepts. Mirrored here only to filter the file dialog, never to decide. */
const ACCEPT = '.svg,.png,image/svg+xml,image/png';
const MAX_BYTES = 512 * 1024;

/**
 * Uploads one symbol to the tenant's library.
 *
 * The client-side checks below are courtesy, not enforcement: the server sanitises every SVG, caps
 * the size, and refuses anything that is not actually an SVG or PNG regardless of what the filename
 * or the browser-reported type says. Checking here as well means an operator learns a 4 MB file is
 * too large before uploading it rather than after.
 */
export function SymbolUploadDialog({
  open,
  onClose,
  onUploaded,
}: {
  open: boolean;
  onClose: () => void;
  /** Receives the new symbol's icon id (`sym-<uuid>`), ready to store in a style. */
  onUploaded: (iconName: string) => void;
}) {
  const upload = useUploadSymbol();
  const inputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [tintable, setTintable] = useState(true);
  const [localError, setLocalError] = useState<string | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);

  const reset = () => {
    setFile(null);
    setName('');
    setDescription('');
    setTintable(true);
    setLocalError(null);
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    setPreviewUrl(null);
    upload.reset();
  };

  const choose = (chosen: File | null) => {
    if (!chosen) return;
    if (chosen.size > MAX_BYTES) {
      setLocalError(
        `${chosen.name} is ${Math.round(chosen.size / 1024)} KB; the limit is ${MAX_BYTES / 1024} KB. ` +
          'A map marker is a few kilobytes — anything larger is usually a full illustration or an ' +
          'embedded photograph, which will not read at icon size anyway.',
      );
      return;
    }
    setLocalError(null);
    setFile(chosen);
    if (!name.trim()) {
      // A sensible default from the filename, still editable — "valve-closed.svg" becomes
      // "valve-closed", which is nearly always what the operator would have typed.
      setName(chosen.name.replace(/\.(svg|png)$/i, ''));
    }
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    setPreviewUrl(URL.createObjectURL(chosen));
  };

  const submit = () => {
    if (!file) return;
    upload.mutate(
      { file, name, description, tintable },
      {
        onSuccess: (symbol) => {
          onUploaded(symbol.iconName);
          reset();
        },
      },
    );
  };

  return (
    <Dialog
      open={open}
      onClose={() => {
        reset();
        onClose();
      }}
      maxWidth="sm"
      fullWidth
    >
      <DialogTitle>Upload a symbol</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2}>
          <Typography variant="body2" sx={{ opacity: 0.8 }}>
            SVG or PNG, up to {MAX_BYTES / 1024} KB. SVG is sanitised on upload — scripts, event
            handlers and external references are stripped, and the file is served under a restrictive
            policy and drawn as an image rather than as markup.
          </Typography>

          <Box
            onClick={() => inputRef.current?.click()}
            onDragOver={(e) => e.preventDefault()}
            onDrop={(e) => {
              e.preventDefault();
              choose(e.dataTransfer.files?.[0] ?? null);
            }}
            sx={{
              border: (theme) => `1px dashed ${theme.palette.divider}`,
              borderRadius: 2,
              p: 3,
              textAlign: 'center',
              cursor: 'pointer',
              '&:hover': { borderColor: 'primary.main', bgcolor: 'action.hover' },
            }}
          >
            {previewUrl ? (
              <Stack alignItems="center" spacing={1}>
                <Box
                  component="img"
                  src={previewUrl}
                  alt=""
                  sx={{
                    width: 56,
                    height: 56,
                    objectFit: 'contain',
                    // The same inversion the picker uses: a black silhouette is invisible on dark
                    // chrome, and this preview is about the shape, not the colour the map will use.
                    filter: tintable ? 'invert(1)' : 'none',
                  }}
                />
                <Typography variant="body2">{file?.name}</Typography>
              </Stack>
            ) : (
              <Stack alignItems="center" spacing={1}>
                <UploadIcon sx={{ opacity: 0.6 }} />
                <Typography variant="body2">Drop a file here, or click to choose</Typography>
              </Stack>
            )}
            <input
              ref={inputRef}
              type="file"
              accept={ACCEPT}
              hidden
              onChange={(e) => choose(e.target.files?.[0] ?? null)}
            />
          </Box>

          <TextField
            size="small"
            label="Name"
            fullWidth
            value={name}
            onChange={(e) => setName(e.target.value)}
            helperText="What the picker calls it."
          />
          <TextField
            size="small"
            label="Description"
            fullWidth
            multiline
            minRows={2}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
          />

          <FormControlLabel
            control={
              <Switch checked={tintable} onChange={(e) => setTintable(e.target.checked)} />
            }
            label={
              <Box>
                <Typography variant="body2">Take the style&apos;s colour</Typography>
                <Typography variant="caption" sx={{ opacity: 0.7 }}>
                  {tintable
                    ? 'The shape is used as a silhouette and painted in the style’s colour — which is what lets a classified rule colour it green for in-service and red for faulty.'
                    : 'The file’s own colours are drawn as-is. Choose this for a multi-colour logo; a classified colour rule will have no effect on it.'}
                </Typography>
              </Box>
            }
          />

          {localError ? <Alert severity="warning">{localError}</Alert> : null}
          {upload.error ? <Alert severity="error">{problemMessage(upload.error)}</Alert> : null}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button
          onClick={() => {
            reset();
            onClose();
          }}
        >
          Cancel
        </Button>
        <Button
          variant="contained"
          onClick={submit}
          disabled={!file || upload.isPending}
          startIcon={<UploadIcon />}
        >
          {upload.isPending ? 'Uploading…' : 'Upload'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
