import {
  Alert,
  AlertTitle,
  Box,
  Card,
  CircularProgress,
  Divider,
  LinearProgress,
  Stack,
  Tooltip,
  Typography,
} from '@mui/material';
import { SimulatorControls } from '../components/SimulatorControls';
import { FleetTable } from '../components/FleetTable';
import {
  isDisabled,
  usePauseSimulator,
  useInjectFault,
  useReloadFleet,
  useSimulatedDevices,
  useSimulatorStatus,
  useStartSimulator,
  useStepSimulator,
  useSuspendDevice,
} from '../hooks/useSimulator';

/**
 * The simulator console: a virtual fleet standing in for meters that are not in the ground yet.
 *
 * The screen is arranged around one question — *did the platform accept what the fleet sent?* The
 * counters at the top are deliberately not a progress bar: `emitted` is the simulator marking its
 * own homework, and the only number worth reading beside it is `accepted`. Where they differ, a
 * device row is wrong, and it is wrong in exactly the way it will be when the physical device is
 * fitted at that address.
 *
 * Nothing here is a parallel pipeline. Every packet these controls produce goes through the same
 * receiver, the same authentication, the same resolution and the same tables as live traffic, so
 * what this page proves about the platform stays true after the meters are real.
 */
export default function SimulatorPage() {
  const status = useSimulatorStatus();
  const devices = useSimulatedDevices(Boolean(status.data));

  const start = useStartSimulator();
  const pause = usePauseSimulator();
  const step = useStepSimulator();
  const reload = useReloadFleet();
  const inject = useInjectFault();
  const suspend = useSuspendDevice();

  const busy =
    start.isPending ||
    pause.isPending ||
    step.isPending ||
    reload.isPending ||
    inject.isPending ||
    suspend.isPending;

  const header = (
    <Box>
      <Typography variant="h1">Simulator</Typography>
      <Typography variant="body2" color="text.secondary">
        A virtual fleet for inspection and validation — registered devices driven through the same
        receiver, credentials and tables as physical ones, so what you prove here stays true when
        the meters are real.
      </Typography>
    </Box>
  );

  // A deployment without the simulator answers 404 on every route. That is a configuration state,
  // not a failure, and saying so plainly is more useful than an error the operator cannot act on.
  if (isDisabled(status.error)) {
    return (
      <Stack spacing={2.5}>
        {header}
        <Alert severity="info" variant="outlined">
          <AlertTitle>The simulator is not enabled on this deployment</AlertTitle>
          Set <code>aquagrid.iot.transports.simulator=true</code> to load it. It is off by default
          and refuses to start outside a development profile, because fictional telemetry that
          reached a production database would be indistinguishable from real device data.
        </Alert>
      </Stack>
    );
  }

  if (status.isLoading) {
    return (
      <Stack spacing={2.5}>
        {header}
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}>
          <CircularProgress size={28} />
        </Box>
      </Stack>
    );
  }

  if (status.isError || !status.data) {
    return (
      <Stack spacing={2.5}>
        {header}
        <Alert severity="error" variant="outlined">
          Could not load simulator status. {(status.error as Error)?.message}
        </Alert>
      </Stack>
    );
  }

  const data = status.data;
  const mutationError = [start, pause, step, reload, inject, suspend].find((m) => m.isError)?.error;

  return (
    <Stack spacing={2.5}>
      {header}

      {mutationError ? (
        <Alert severity="error" variant="outlined">
          {(mutationError as Error).message}
        </Alert>
      ) : null}

      <SimulatorControls
        status={data}
        busy={busy}
        onStart={() => start.mutate(undefined)}
        onPause={() => pause.mutate(undefined)}
        onStep={(ticks) => step.mutate(ticks)}
        onReload={() => reload.mutate(undefined)}
      />

      {/* Rejections come first and are stated as what they are: a claim about a device row, not
          about traffic. Nothing spoofs the simulator, so this is never noise. */}
      {data.rejected > 0 ? (
        <Alert severity="error" variant="outlined">
          <AlertTitle>
            {data.rejected.toLocaleString()} packet{data.rejected === 1 ? '' : 's'} refused by the
            receiver
          </AlertTitle>
          Nothing else sends packets as these devices, so every refusal is a registration or
          configuration fault — and it is the same refusal the physical device would get at that
          address. The reason is on the affected rows below.
        </Alert>
      ) : null}

      {data.unaddressable.length > 0 ? (
        <Alert severity="warning" variant="outlined">
          <AlertTitle>
            {data.unaddressable.length} device
            {data.unaddressable.length === 1 ? '' : 's'} cannot be driven
          </AlertTitle>
          Registered as simulated but carrying no network address, so nothing sent for{' '}
          {data.unaddressable.length === 1 ? 'it' : 'them'} could ever be resolved back:{' '}
          <code>{data.unaddressable.join(', ')}</code>. Re-register with the identity field the
          transport requires.
        </Alert>
      ) : null}

      <Stack direction="row" spacing={1.5} flexWrap="wrap" useFlexGap>
        <Stat
          label="Accepted"
          value={data.accepted}
          hint="Ingested as telemetry, through the full receiver pipeline."
        />
        <Stat
          label="Emitted"
          value={data.emitted}
          hint="Handed to the receiver. On its own this only measures the simulator — compare it with Accepted."
        />
        <Stat
          label="Duplicates"
          value={data.duplicates}
          hint="Recognised as already ingested by replay protection."
        />
        <Stat
          label="Rejected"
          value={data.rejected}
          emphasis={data.rejected > 0}
          hint="Refused. Always a statement about a device row, never stray traffic."
        />
        <Stat
          label="Silent"
          value={data.suppressed}
          hint="Ticks a meter stayed quiet for under a comms-loss fault. A simulated outage, not a failure."
        />
        <Stat
          label="Leaking"
          value={data.metersLeaking}
          hint="Meters with an active leak — what minimum-night-flow analysis should be finding."
        />
        <Stat label="Tampered" value={data.metersTampered} hint="Meters flagged tampered." />
      </Stack>

      <Card variant="outlined">
        <Stack
          direction="row"
          alignItems="baseline"
          justifyContent="space-between"
          spacing={1}
          sx={{ px: 2, py: 1.5 }}
        >
          <Box>
            <Typography variant="subtitle1">Fleet</Typography>
            <Typography variant="caption" color="text.secondary">
              Registered devices with source <code>SIMULATOR</code>. Faulty meters first.
            </Typography>
          </Box>
          <Typography variant="caption" color="text.secondary" noWrap>
            To hand one over to a real device: silence it, then set its source to LIVE.
          </Typography>
        </Stack>
        {devices.isFetching ? <LinearProgress /> : <Divider />}

        {devices.isError ? (
          <Box sx={{ p: 2 }}>
            <Alert severity="error" variant="outlined">
              {(devices.error as Error).message}
            </Alert>
          </Box>
        ) : (
          <Box sx={{ overflowX: 'auto' }}>
            <FleetTable
              devices={devices.data ?? []}
              busy={busy}
              onInject={(deviceId, fault) => inject.mutate({ deviceId, fault })}
              onSuspend={(deviceId, suspended) => suspend.mutate({ deviceId, suspended })}
            />
          </Box>
        )}
      </Card>
    </Stack>
  );
}

function Stat({
  label,
  value,
  hint,
  emphasis,
}: {
  label: string;
  value: number;
  hint: string;
  emphasis?: boolean;
}) {
  return (
    <Tooltip title={hint}>
      <Card variant="outlined" sx={{ px: 2, py: 1.25, minWidth: 132, flex: '1 1 132px' }}>
        <Typography variant="caption" color="text.secondary" display="block" noWrap>
          {label}
        </Typography>
        <Typography variant="h6" color={emphasis ? 'error.main' : 'text.primary'}>
          {value.toLocaleString()}
        </Typography>
      </Card>
    </Tooltip>
  );
}
