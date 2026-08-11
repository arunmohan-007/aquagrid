import { useEffect, useMemo, useState } from 'react';
import {
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  InputAdornment,
  Stack,
  Tab,
  Tabs,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import SearchIcon from '@mui/icons-material/SearchOutlined';
import UploadIcon from '@mui/icons-material/FileUploadOutlined';
import { useAuth } from '@/lib/auth/AuthProvider';
import { setUploadedSymbolTinting } from '@/features/gis/iconImages';
import { MARKER_SHAPE_PATHS } from '@/features/gis/markerShapes';
import { useMapSymbols, useStyleVocabulary } from '../hooks/useLayers';
import { SymbolUploadDialog } from './SymbolUploadDialog';

/**
 * Chooses the marker a point style draws.
 *
 * Three sources behind one control, because from an administrator's point of view they are one
 * decision — "what shape is a valve" — and only differ in where the artwork came from:
 *
 * - **Shapes** the client draws itself. No download, always present, always tintable.
 * - **Library** — Mapbox Maki (CC0) and Google Material Symbols (Apache-2.0), vendored into the
 *   build so they need no key and no route to the internet.
 * - **Uploads** — this tenant's own SVG or PNG.
 *
 * The stored value is the icon id (`diamond`, `lib-water`, `sym-<uuid>`), which is exactly what the
 * server validates and what the composer emits as `icon-image`. Nothing about the artwork's origin
 * leaks into the style document.
 */
export function IconPicker({
  value,
  onChange,
}: {
  value: string | undefined;
  onChange: (icon: string) => void;
}) {
  const { hasPermission } = useAuth();
  const { data: vocabulary } = useStyleVocabulary();
  const { data: symbols } = useMapSymbols();
  const [open, setOpen] = useState(false);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [tab, setTab] = useState(0);
  const [search, setSearch] = useState('');

  /*
   * The map registers uploaded icons as tintable or not, and learns which from here rather than by
   * fetching each symbol's metadata a second time when a style references it.
   */
  useEffect(() => {
    if (symbols) setUploadedSymbolTinting(symbols);
  }, [symbols]);

  const shapes = vocabulary?.icons ?? [];
  // Memoised so the `?? []` fallback does not produce a new array identity on every render, which
  // would make the filter below re-run whether or not the library actually changed.
  const library = useMemo(() => vocabulary?.libraryIcons ?? [], [vocabulary]);
  const uploads = useMemo(() => symbols ?? [], [symbols]);

  const selected = value ?? 'circle';
  const filter = search.trim().toLowerCase();

  const filteredLibrary = useMemo(
    () =>
      filter
        ? library.filter(
            (icon) =>
              icon.name.toLowerCase().includes(filter) ||
              icon.iconName.toLowerCase().includes(filter),
          )
        : library,
    [library, filter],
  );

  return (
    <>
      <Box>
        <Typography variant="caption" sx={{ opacity: 0.75, display: 'block', mb: 0.5 }}>
          Icon
        </Typography>
        <Button
          variant="outlined"
          onClick={() => setOpen(true)}
          fullWidth
          sx={{ justifyContent: 'flex-start', gap: 1, py: 1 }}
        >
          <IconPreview icon={selected} size={22} />
          <Typography variant="body2" sx={{ textTransform: 'none' }}>
            {describe(selected, library, uploads)}
          </Typography>
        </Button>
      </Box>

      <Dialog open={open} onClose={() => setOpen(false)} maxWidth="md" fullWidth>
        <DialogTitle sx={{ pb: 1 }}>Choose an icon</DialogTitle>
        <DialogContent dividers>
          <Stack direction="row" spacing={1.5} alignItems="center" sx={{ mb: 1.5 }}>
            <Tabs value={tab} onChange={(_e, next) => setTab(next)} sx={{ minHeight: 36 }}>
              <Tab label="Shapes" sx={{ minHeight: 36, py: 0 }} />
              <Tab label={`Library (${library.length})`} sx={{ minHeight: 36, py: 0 }} />
              <Tab label={`Uploads (${uploads.length})`} sx={{ minHeight: 36, py: 0 }} />
            </Tabs>
            <Box sx={{ flex: 1 }} />
            {tab === 1 ? (
              <TextField
                size="small"
                placeholder="Search icons"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                InputProps={{
                  startAdornment: (
                    <InputAdornment position="start">
                      <SearchIcon fontSize="small" />
                    </InputAdornment>
                  ),
                }}
              />
            ) : null}
            {tab === 2 && hasPermission('gis:style:manage') ? (
              <Button size="small" startIcon={<UploadIcon />} onClick={() => setUploadOpen(true)}>
                Upload
              </Button>
            ) : null}
          </Stack>

          {tab === 0 ? (
            <IconGrid
              items={shapes.map((shape) => ({ id: shape, label: shape }))}
              selected={selected}
              onPick={(id) => {
                onChange(id);
                setOpen(false);
              }}
            />
          ) : null}

          {tab === 1 ? (
            <>
              <Typography variant="caption" sx={{ opacity: 0.7, display: 'block', mb: 1 }}>
                Mapbox Maki (CC0) and Google Material Symbols (Apache-2.0), bundled into this build —
                no key, and no network access needed to draw them.
              </Typography>
              {filteredLibrary.length === 0 ? (
                <Typography variant="body2" sx={{ opacity: 0.7, py: 3, textAlign: 'center' }}>
                  No icon matches “{search}”.
                </Typography>
              ) : (
                <IconGrid
                  items={filteredLibrary.map((icon) => ({
                    id: icon.iconName,
                    label: icon.name,
                    caption: icon.set === 'MAKI' ? 'Maki' : 'Material',
                  }))}
                  selected={selected}
                  onPick={(id) => {
                    onChange(id);
                    setOpen(false);
                  }}
                />
              )}
            </>
          ) : null}

          {tab === 2 ? (
            uploads.length === 0 ? (
              <Typography variant="body2" sx={{ opacity: 0.7, py: 4, textAlign: 'center' }}>
                No symbols uploaded yet. SVG and PNG are accepted; an SVG uploaded as a silhouette
                takes the style&apos;s colour, including a classified one.
              </Typography>
            ) : (
              <IconGrid
                items={uploads.map((symbol) => ({
                  id: symbol.iconName,
                  label: symbol.name,
                  caption: symbol.tintable ? 'Tintable' : 'Full colour',
                }))}
                selected={selected}
                onPick={(id) => {
                  onChange(id);
                  setOpen(false);
                }}
              />
            )
          ) : null}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>Close</Button>
        </DialogActions>
      </Dialog>

      <SymbolUploadDialog
        open={uploadOpen}
        onClose={() => setUploadOpen(false)}
        onUploaded={(iconName) => {
          onChange(iconName);
          setUploadOpen(false);
          setOpen(false);
        }}
      />
    </>
  );
}

function IconGrid({
  items,
  selected,
  onPick,
}: {
  items: { id: string; label: string; caption?: string }[];
  selected: string;
  onPick: (id: string) => void;
}) {
  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fill, minmax(88px, 1fr))',
        gap: 1,
        maxHeight: 380,
        overflowY: 'auto',
      }}
    >
      {items.map((item) => (
        <Tooltip key={item.id} title={item.caption ? `${item.label} · ${item.caption}` : item.label}>
          <Box
            onClick={() => onPick(item.id)}
            sx={{
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              gap: 0.5,
              p: 1,
              borderRadius: 1.5,
              cursor: 'pointer',
              border: (theme) =>
                `1px solid ${item.id === selected ? theme.palette.primary.main : 'transparent'}`,
              bgcolor: item.id === selected ? 'action.selected' : 'transparent',
              '&:hover': { bgcolor: 'action.hover' },
            }}
          >
            <IconPreview icon={item.id} size={26} />
            <Typography
              variant="caption"
              sx={{
                textAlign: 'center',
                lineHeight: 1.2,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
                width: '100%',
              }}
            >
              {item.label}
            </Typography>
          </Box>
        </Tooltip>
      ))}
    </Box>
  );
}

/**
 * Draws one icon at picker size.
 *
 * Built-in shapes are rendered from the same path data the map rasterises, so the swatch is the
 * marker rather than an approximation of it. Library and uploaded icons are `<img>` tags pointing at
 * the content endpoint — an image element, never injected markup, which is the same secure static
 * mode the map uses and the reason an uploaded SVG cannot execute anything here either.
 */
export function IconPreview({ icon, size = 24 }: { icon: string; size?: number }) {
  const path = MARKER_SHAPE_PATHS[icon];
  if (path) {
    return (
      <Box
        component="svg"
        viewBox="0 0 24 24"
        sx={{ width: size, height: size, fill: 'currentColor', opacity: 0.9 }}
      >
        <path d={path} />
      </Box>
    );
  }

  const url = icon.startsWith('lib-')
    ? `/api/v1/layer-styles/library-icons/${encodeURIComponent(icon.slice(4))}/content`
    : icon.startsWith('sym-')
      ? `/api/v1/map-symbols/${encodeURIComponent(icon.slice(4))}/content`
      : null;

  if (!url) {
    return <Box sx={{ width: size, height: size }} />;
  }
  return (
    <Box
      component="img"
      src={url}
      alt=""
      sx={{
        width: size,
        height: size,
        objectFit: 'contain',
        /*
         * The library glyphs are black-on-transparent, which is invisible on this console's dark
         * chrome. Inverting is a display concern only — the map tints the real marker from the
         * style's colour, and a filter here cannot reach that.
         */
        filter: icon.startsWith('lib-') ? 'invert(1)' : 'none',
        opacity: 0.9,
      }}
    />
  );
}

/** The label under the trigger button: the icon's own name rather than its id. */
function describe(
  icon: string,
  library: { iconName: string; name: string }[],
  uploads: { iconName: string; name: string }[],
): string {
  if (MARKER_SHAPE_PATHS[icon]) {
    return icon.charAt(0).toUpperCase() + icon.slice(1);
  }
  return (
    library.find((i) => i.iconName === icon)?.name ??
    uploads.find((i) => i.iconName === icon)?.name ??
    // A style may reference an icon that has since been deleted. Saying so beats rendering a blank
    // button that looks like nothing was ever chosen.
    `${icon} (missing)`
  );
}
