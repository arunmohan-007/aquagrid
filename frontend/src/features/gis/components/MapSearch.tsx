import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Box, CircularProgress, InputBase, Stack, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material';
import SearchIcon from '@mui/icons-material/SearchOutlined';
import ClearIcon from '@mui/icons-material/CloseOutlined';
import MyLocationIcon from '@mui/icons-material/MyLocationOutlined';
import PlaceIcon from '@mui/icons-material/PlaceOutlined';
import AssetIcon from '@mui/icons-material/Inventory2Outlined';
import { assetsApi } from '@/features/assets/api/assetsApi';
import { geocodeSearch, parseCoordinate } from '../api/geocode';
import { layerSymbol } from '../layerStyle';
import { mapChrome } from '../mapTheme';

/**
 * Map search.
 *
 * Three sources share one pill, selected by a segmented toggle so the operator never has to
 * remember which box does what:
 *
 * - **Assets** — the tenant's own catalogue (`GET /assets?search=`). Default. Selecting a result
 *   flies the view to that asset. Results without coordinates (lines and polygons, whose geometry
 *   the list endpoint does not return) are listed but not selectable, because zooming to
 *   "somewhere" is worse than saying the position is unavailable here.
 * - **Place** — Nominatim (OpenStreetMap) forward geocoding, same-origin through the `/geocode`
 *   proxy. No API key. Selecting a result flies the view to that coordinate.
 * - **Coord** — a `"lat, lng"` input parsed locally, no network call. Enter flies the view there.
 *
 * All three converge on the same `onPick(lonLat, label)` contract that the page wires to flyTo.
 */
export type SearchMode = 'assets' | 'place' | 'coord';

export function MapSearch({ onPick }: { onPick: (lonLat: [number, number], label: string) => void }) {
  const [mode, setMode] = useState<SearchMode>('assets');
  const [term, setTerm] = useState('');
  const [debounced, setDebounced] = useState('');
  const [open, setOpen] = useState(false);
  // Coord-mode validation message; empty means the input is empty or a valid coordinate.
  const [coordError, setCoordError] = useState<string | null>(null);
  const boxRef = useRef<HTMLDivElement>(null);

  // Debounced so a typed word is one request, not one per keystroke.
  useEffect(() => {
    const id = window.setTimeout(() => setDebounced(term.trim()), 300);
    return () => window.clearTimeout(id);
  }, [term]);

  // A click anywhere else dismisses the result list.
  useEffect(() => {
    const onDown = (event: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, []);

  const enabled = mode !== 'coord' && debounced.length >= 2;

  const assetQuery = useQuery({
    queryKey: ['gis', 'search', 'assets', debounced],
    queryFn: () => assetsApi.list({ search: debounced, size: 8 }),
    enabled: mode === 'assets' && debounced.length >= 2,
    staleTime: 30_000,
  });

  const placeQuery = useQuery({
    queryKey: ['gis', 'search', 'place', debounced],
    queryFn: ({ signal }) => geocodeSearch(debounced, signal),
    enabled: mode === 'place' && debounced.length >= 2,
    staleTime: 30_000,
  });

  const isFetching = (mode === 'assets' && assetQuery.isFetching) || (mode === 'place' && placeQuery.isFetching);
  const assetResults = assetQuery.data?.content ?? [];
  const placeResults = placeQuery.data ?? [];
  const showResults = open && enabled;

  const handleModeChange = (next: SearchMode) => {
    if (next === mode) return;
    setMode(next);
    setTerm('');
    setDebounced('');
    setCoordError(null);
    setOpen(false);
  };

  const submitCoord = (raw: string) => {
    const parsed = parseCoordinate(raw);
    if (!parsed) {
      setCoordError('Enter as lat, lng — e.g. 11.1271, 78.6569');
      return;
    }
    setCoordError(null);
    const label = `${parsed.lat.toFixed(4)}, ${parsed.lon.toFixed(4)}`;
    onPick([parsed.lon, parsed.lat], label);
    setOpen(false);
  };

  const placeholder =
    mode === 'assets'
      ? 'Search asset code, name or type…'
      : mode === 'place'
        ? 'Search a place or address…'
        : 'Enter lat, lng — e.g. 11.1271, 78.6569';

  return (
    <Box
      ref={boxRef}
      sx={{
        position: 'absolute',
        top: 16,
        left: '50%',
        transform: 'translateX(-50%)',
        width: 'min(560px, calc(100% - 140px))',
        zIndex: 6,
      }}
    >
      <Stack
        className="ag-search-pill"
        spacing={1.25}
        sx={{
          px: 1.25,
          py: 1,
          borderRadius: 3,
          bgcolor: mapChrome.floating,
          border: `1px solid ${mapChrome.border}`,
          backdropFilter: 'blur(14px) saturate(140%)',
          boxShadow: mapChrome.shadow,
          // A focus glow that lights the pill the moment the operator clicks into it, so the
          // active search affordance is obvious across a busy map. Uses the accent glow token.
          transition: 'box-shadow 180ms ease, border-color 180ms ease',
          '&:focus-within': {
            borderColor: mapChrome.accent,
            boxShadow: `${mapChrome.shadow}, 0 0 0 3px rgba(103,232,249,0.18), ${mapChrome.glow}`,
          },
        }}
      >
        <ToggleButtonGroup
          exclusive
          size="small"
          value={mode}
          onChange={(_, next: SearchMode | null) => next && handleModeChange(next)}
          aria-label="Search source"
          sx={{
            '& .MuiToggleButton-root': {
              px: 1.25,
              py: 0.25,
              borderRadius: 1.5,
              borderColor: mapChrome.border,
              color: mapChrome.textFaint,
              fontSize: 12,
              fontWeight: 600,
              textTransform: 'none',
              gap: 0.5,
              '& .MuiSvgIcon-root': { fontSize: 15 },
              '&:hover': { color: mapChrome.text, bgcolor: 'rgba(255,255,255,0.05)' },
              '&.Mui-selected': {
                color: mapChrome.accent,
                bgcolor: mapChrome.accentSoft,
                borderColor: mapChrome.accent,
              },
            },
          }}
        >
          <ToggleButton value="assets">
            <AssetIcon /> Assets
          </ToggleButton>
          <ToggleButton value="place">
            <PlaceIcon /> Place
          </ToggleButton>
          <ToggleButton value="coord">
            <MyLocationIcon /> Coord
          </ToggleButton>
        </ToggleButtonGroup>

        <Stack direction="row" alignItems="center" spacing={1.25} sx={{ px: 0.75 }}>
          <SearchIcon
            sx={{
              color: mapChrome.textFaint,
              fontSize: 21,
              transition: 'color 180ms ease',
              '.ag-search-pill:focus-within &': { color: mapChrome.accent },
            }}
          />
          <InputBase
            value={term}
            onChange={(event) => {
              setTerm(event.target.value);
              setOpen(true);
              if (mode === 'coord') setCoordError(null);
            }}
            onFocus={() => setOpen(true)}
            onKeyDown={(event) => {
              if (mode === 'coord' && event.key === 'Enter') {
                event.preventDefault();
                submitCoord(term);
              }
            }}
            placeholder={placeholder}
            inputProps={{
              'aria-label':
                mode === 'assets'
                  ? 'Search assets on the map'
                  : mode === 'place'
                    ? 'Search a place or address'
                    : 'Enter a latitude, longitude coordinate',
            }}
            sx={{
              flexGrow: 1,
              color: mapChrome.text,
              fontSize: 14.5,
              '& input::placeholder': { color: mapChrome.textFaint, opacity: 1 },
            }}
          />
          {isFetching ? <CircularProgress size={16} sx={{ color: mapChrome.accent }} /> : null}
          {term ? (
            <Box
              component="button"
              type="button"
              aria-label="Clear search"
              onClick={() => {
                setTerm('');
                setCoordError(null);
                setOpen(false);
              }}
              sx={{
                border: 0,
                background: 'none',
                cursor: 'pointer',
                display: 'grid',
                color: mapChrome.textFaint,
                '&:hover': { color: mapChrome.text },
              }}
            >
              <ClearIcon sx={{ fontSize: 18 }} />
            </Box>
          ) : null}
        </Stack>

        {mode === 'coord' && coordError ? (
          <Typography sx={{ px: 0.75, fontSize: 11.5, color: mapChrome.accent }}>
            {coordError}
          </Typography>
        ) : null}
      </Stack>

      {showResults && mode === 'assets' ? (
        <ResultsPanel>
          {assetResults.length === 0 && !isFetching ? (
            <EmptyTerm term={debounced} />
          ) : (
            assetResults.map((asset) => {
              const locatable = Boolean(asset.coordinates);
              return (
                <ResultRow
                  key={asset.id}
                  disabled={!locatable}
                  onClick={() => {
                    if (!asset.coordinates) return;
                    onPick(asset.coordinates, asset.name);
                    setOpen(false);
                  }}
                  swatch={<SymbolSwatch code={`${asset.assetType.toLowerCase()}s`} />}
                  title={asset.name}
                  caption={`${asset.assetCode} · ${asset.assetType.replaceAll('_', ' ').toLowerCase()}${locatable ? '' : ' · no point location'}`}
                />
              );
            })
          )}
        </ResultsPanel>
      ) : null}

      {showResults && mode === 'place' ? (
        <ResultsPanel>
          {placeResults.length === 0 && !isFetching ? (
            <EmptyTerm term={debounced} />
          ) : (
            placeResults.map((result, index) => (
              <ResultRow
                /* The coordinates are the identity. The index only breaks ties between two results
                   at the same point, which a geocoder does return — a street and a building on it. */
                // eslint-disable-next-line react/no-array-index-key
                key={`${result.lon},${result.lat},${index}`}
                onClick={() => {
                  onPick([result.lon, result.lat], result.name);
                  setOpen(false);
                }}
                swatch={<Swatch colour={mapChrome.accent} />}
                title={result.name}
                caption={`${result.lat.toFixed(4)}, ${result.lon.toFixed(4)}${result.type ? ` · ${result.type}` : ''}`}
              />
            ))
          )}
        </ResultsPanel>
      ) : null}
    </Box>
  );
}

function ResultsPanel({ children }: { children: ReactNode }) {
  return (
    <Box
      sx={{
        mt: 1,
        borderRadius: 3,
        overflow: 'hidden',
        bgcolor: mapChrome.surface,
        border: `1px solid ${mapChrome.border}`,
        boxShadow: mapChrome.shadow,
        maxHeight: 340,
        overflowY: 'auto',
      }}
    >
      {children}
    </Box>
  );
}

function ResultRow({
  swatch,
  title,
  caption,
  disabled,
  onClick,
}: {
  swatch: ReactNode;
  title: string;
  caption: string;
  disabled?: boolean;
  onClick: () => void;
}) {
  return (
    <Box
      component="button"
      type="button"
      disabled={disabled}
      onClick={onClick}
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1.25,
        width: '100%',
        px: 2,
        py: 1.25,
        border: 0,
        borderBottom: `1px solid ${mapChrome.border}`,
        background: 'none',
        textAlign: 'left',
        cursor: disabled ? 'default' : 'pointer',
        opacity: disabled ? 0.55 : 1,
        '&:last-of-type': { borderBottom: 0 },
        '&:hover': disabled ? {} : { bgcolor: 'rgba(255,255,255,0.06)' },
      }}
    >
      {swatch}
      <Box sx={{ minWidth: 0, flexGrow: 1 }}>
        <Typography noWrap sx={{ fontSize: 13.5, color: mapChrome.text }}>
          {title}
        </Typography>
        <Typography sx={{ fontSize: 11, color: mapChrome.textFaint }}>{caption}</Typography>
      </Box>
    </Box>
  );
}

/**
 * A small symbol swatch for a search result. Uses the layer's real shape + colour so a result row
 * looks like the asset it represents on the map (a meter is a blue dot, a pipeline is a dash).
 */
function SymbolSwatch({ code }: { code: string }) {
  const sym = layerSymbol(code);
  return (
    <Box
      aria-hidden
      sx={{
        width: 14,
        height: 14,
        flexShrink: 0,
        display: 'grid',
        placeItems: 'center',
        color: sym.colour,
        filter: `drop-shadow(0 0 4px ${sym.glow}66)`,
      }}
    >
      {sym.shape === 'line' ? (
        <Box sx={{ width: 14, height: 3, borderRadius: 2, background: sym.colour }} />
      ) : sym.shape === 'fill' ? (
        <Box sx={{ width: 11, height: 11, borderRadius: 1, background: sym.colour, opacity: 0.4, border: `1.5px solid ${sym.colour}` }} />
      ) : sym.shape === 'diamond' ? (
        <Box sx={{ width: 10, height: 10, background: sym.colour, transform: 'rotate(45deg)', borderRadius: 1 }} />
      ) : (
        <Box sx={{ width: 11, height: 11, borderRadius: '50%', background: sym.colour, border: `1.5px solid ${sym.glow}` }} />
      )}
    </Box>
  );
}

/** A small accent dot — used for place (geocode) results, which have no layer symbol of their own. */
function Swatch({ colour }: { colour: string }) {
  return (
    <Box
      aria-hidden
      sx={{ width: 9, height: 9, borderRadius: '50%', flexShrink: 0, bgcolor: colour }}
    />
  );
}

function EmptyTerm({ term }: { term: string }) {
  return (
    <Typography sx={{ px: 2, py: 1.75, fontSize: 13, color: mapChrome.textFaint }}>
      Nothing matches “{term}”.
    </Typography>
  );
}
