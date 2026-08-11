import { Alert, Box, Stack, Typography } from '@mui/material';
import DashboardIcon from '@mui/icons-material/SpaceDashboardOutlined';
import PipeIcon from '@mui/icons-material/TimelineOutlined';
import TankIcon from '@mui/icons-material/HexagonOutlined';
import OpenWellIcon from '@mui/icons-material/RadioButtonCheckedOutlined';
import BoreWellIcon from '@mui/icons-material/VerticalAlignBottomOutlined';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useNetworkSummary } from '../hooks/useDashboard';
import { chartTokens } from '../chartTheme';
import { StatTile } from '../components/StatTile';
import { ChartCard } from '../components/ChartCard';
import { PipeNetworkChart } from '../components/PipeNetworkChart';
import { FacilitiesChart } from '../components/FacilitiesChart';
import { PanchayatTable } from '../components/PanchayatTable';

/**
 * The Dashboard module.
 *
 * Reads top-down in the order the questions get asked: how much network do we have, what is mapped
 * on it, where is it, and then the numbers themselves. The headline figures and the charts under
 * them come from one server-side aggregate, so the total can never disagree with its own breakdown.
 */
export default function DashboardPage() {
  const { user } = useAuth();
  const { data, isPending, error } = useNetworkSummary();

  if (!user) return null;
  const org = user.organization;

  const rows = data?.panchayats ?? [];
  const facilitiesTotal = (data?.tanks ?? 0) + (data?.openWells ?? 0) + (data?.boreWells ?? 0);
  const hasNetwork = rows.some((r) => r.pipelineLengthM > 0);
  const hasFacilities = rows.some((r) => r.tanks + r.openWells + r.boreWells > 0);

  return (
    <Stack spacing={3}>
      <Stack direction="row" spacing={1.5} alignItems="center">
        <DashboardIcon color="primary" sx={{ fontSize: 30 }} />
        <Box>
          <Typography variant="h1">Dashboard</Typography>
          <Typography variant="body2" color="text.secondary">
            {org.name} · {org.type.replaceAll('_', ' ').toLowerCase()}
          </Typography>
        </Box>
      </Stack>

      {error ? (
        <Alert severity="error" variant="outlined">
          Could not load the network summary. {(error as Error).message}
        </Alert>
      ) : null}

      {/*
        KPI row. The network length leads at double the type size — it is the one number this page
        exists to answer — and the three facility counts follow at equal weight, because no one of
        them is more important than the others.
      */}
      <Box
        sx={{
          display: 'grid',
          gap: 2,
          gridTemplateColumns: {
            xs: '1fr',
            sm: 'repeat(2, 1fr)',
            lg: '1.4fr repeat(3, 1fr)',
          },
        }}
      >
        <StatTile
          hero
          loading={isPending}
          label="Total pipe network"
          value={((data?.totalPipelineLengthM ?? 0) / 1000).toFixed(2)}
          unit="km"
          caption={`${data?.pipelineCount ?? 0} segments across ${rows.length} panchayat${rows.length === 1 ? '' : 's'}`}
          accent={chartTokens.pipeline}
          icon={<PipeIcon />}
        />
        <StatTile
          loading={isPending}
          label="Over Head Tanks"
          value={String(data?.tanks ?? 0)}
          caption="Mapped"
          accent={chartTokens.facilities.tanks}
          icon={<TankIcon />}
        />
        <StatTile
          loading={isPending}
          label="Bore Wells"
          value={String(data?.boreWells ?? 0)}
          caption="Mapped"
          accent={chartTokens.facilities.boreWells}
          icon={<BoreWellIcon />}
        />
        <StatTile
          loading={isPending}
          label="Open Wells"
          value={String(data?.openWells ?? 0)}
          caption="Mapped"
          accent={chartTokens.facilities.openWells}
          icon={<OpenWellIcon />}
        />
      </Box>

      <Box
        sx={{
          display: 'grid',
          gap: 2,
          gridTemplateColumns: { xs: '1fr', lg: 'repeat(2, 1fr)' },
        }}
      >
        <ChartCard
          title="Pipe network by panchayat"
          subtitle="Length of mapped mains and distribution lines"
          loading={isPending}
          empty={!hasNetwork ? 'No pipe network imported yet.' : undefined}
        >
          <PipeNetworkChart rows={rows} />
        </ChartCard>

        <ChartCard
          title="Facilities by panchayat"
          subtitle={`${facilitiesTotal} structure${facilitiesTotal === 1 ? '' : 's'} mapped`}
          loading={isPending}
          empty={!hasFacilities ? 'No tanks or wells imported yet.' : undefined}
        >
          <FacilitiesChart rows={rows} />
        </ChartCard>
      </Box>

      {data && rows.length > 0 ? <PanchayatTable summary={data} /> : null}
    </Stack>
  );
}
