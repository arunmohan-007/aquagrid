import { useState, type ChangeEvent } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import {
  Alert,
  Box,
  Button,
  Card,
  IconButton,
  InputAdornment,
  MenuItem,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Toolbar,
  Typography,
} from '@mui/material';
import SearchIcon from '@mui/icons-material/SearchOutlined';
import UploadIcon from '@mui/icons-material/UploadOutlined';
import ChevronLeftIcon from '@mui/icons-material/ChevronLeftOutlined';
import ChevronRightIcon from '@mui/icons-material/ChevronRightOutlined';
import { useAssetList } from '../hooks/useAssets';
import { AssetStatusChip } from '../components/AssetStatusChip';
import type { AssetType } from '../types';
import { ASSET_TYPE_LABELS_PLURAL } from '../labels';

/*
 * Every type the backend can store, in the order the layers panel uses. The list used to stop at
 * pump stations, so an operator who imported sensors, service connections or boundaries had no
 * way to filter down to them — the register simply looked as though the import had not happened.
 */
const FILTERABLE_TYPES: AssetType[] = [
  'METER', 'VALVE', 'PIPELINE', 'HYDRANT', 'TANK', 'RESERVOIR', 'PUMP_STATION',
  'OPEN_WELL', 'BORE_WELL', 'SENSOR', 'SERVICE_CONNECTION', 'DMA', 'PANCHAYAT',
];

const TYPE_FILTERS: Array<{ value: '' | AssetType; label: string }> = [
  { value: '', label: 'All types' },
  ...FILTERABLE_TYPES.map((value) => ({ value, label: ASSET_TYPE_LABELS_PLURAL[value] })),
];

/**
 * The asset register. Search and type filter are debounced into the query key; paging back is
 * instant because React Query caches each distinct query.
 */
export default function AssetsListPage() {
  const [search, setSearch] = useState('');
  const [type, setType] = useState<'' | AssetType>('');
  const [page, setPage] = useState(0);

  const { data, isLoading, error, isFetching } = useAssetList(search, type || undefined, page);
  const assets = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;

  return (
    <Stack spacing={2.5}>
      <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={2}>
        <Box>
          <Typography variant="h1">Assets</Typography>
          <Typography variant="body2" color="text.secondary">
            The spatial asset register — every physical thing in the network.
          </Typography>
        </Box>
        <Button variant="outlined" startIcon={<UploadIcon />} component={RouterLink} to="/import">
          Import
        </Button>
      </Stack>

      {error ? (
        <Alert severity="error" variant="outlined">
          Could not load assets. {(error as Error).message}
        </Alert>
      ) : null}

      <Card variant="outlined">
        <Toolbar disableGutters sx={{ gap: 1.5, px: 2, py: 1.5, flexWrap: 'wrap' }}>
          <TextField
            size="small"
            placeholder="Search code or name"
            value={search}
            onChange={(e: ChangeEvent<HTMLInputElement>) => {
              setPage(0);
              setSearch(e.target.value);
            }}
            sx={{ minWidth: 240, flexGrow: 1, maxWidth: 380 }}
            InputProps={{
              startAdornment: (
                <InputAdornment position="start">
                  <SearchIcon fontSize="small" />
                </InputAdornment>
              ),
            }}
          />
          <TextField
            select
            size="small"
            value={type}
            onChange={(e) => {
              setPage(0);
              setType(e.target.value as '' | AssetType);
            }}
            sx={{ minWidth: 160 }}
            label="Type"
          >
            {TYPE_FILTERS.map((option) => (
              <MenuItem key={option.value} value={option.value}>
                {option.label}
              </MenuItem>
            ))}
          </TextField>
        </Toolbar>

        <TableContainer>
          <Table size="small" aria-label="Assets">
            <TableHead>
              <TableRow>
                <TableCell>Code</TableCell>
                <TableCell>Name</TableCell>
                <TableCell>Type</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>Location</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {isLoading ? (
                <TableRow>
                  <TableCell colSpan={5} align="center" sx={{ py: 4 }}>
                    <Typography variant="body2" color="text.secondary">
                      Loading…
                    </Typography>
                  </TableCell>
                </TableRow>
              ) : assets.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={5} align="center" sx={{ py: 4 }}>
                    <Typography variant="body2" color="text.secondary">
                      No assets match your search.
                    </Typography>
                  </TableCell>
                </TableRow>
              ) : (
                assets.map((asset) => (
                  <TableRow
                    key={asset.id}
                    hover
                    component={RouterLink}
                    to={asset.id}
                    sx={{ textDecoration: 'none', cursor: 'pointer' }}
                  >
                    <TableCell>
                      <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                        {asset.assetCode}
                      </Typography>
                    </TableCell>
                    <TableCell>{asset.name}</TableCell>
                    <TableCell>
                      <Typography variant="caption" color="text.secondary">
                        {asset.assetType.replaceAll('_', ' ')}
                      </Typography>
                    </TableCell>
                    <TableCell>
                      <AssetStatusChip status={asset.status} />
                    </TableCell>
                    <TableCell>
                      <Typography variant="caption" color="text.secondary">
                        {asset.coordinates
                          ? `${asset.coordinates[1].toFixed(4)}, ${asset.coordinates[0].toFixed(4)}`
                          : asset.geometryType ?? '—'}
                      </Typography>
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </TableContainer>

        <Toolbar disableGutters sx={{ justifyContent: 'flex-end', px: 2, py: 1.25, gap: 1 }}>
          <Typography variant="caption" color="text.secondary">
            {isFetching ? 'Updating…' : `Page ${page + 1} of ${Math.max(totalPages, 1)}`}
          </Typography>
          <IconButton size="small" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))} aria-label="Previous page">
            <ChevronLeftIcon fontSize="small" />
          </IconButton>
          <IconButton size="small" disabled={page >= totalPages - 1} onClick={() => setPage((p) => p + 1)} aria-label="Next page">
            <ChevronRightIcon fontSize="small" />
          </IconButton>
        </Toolbar>
      </Card>
    </Stack>
  );
}
