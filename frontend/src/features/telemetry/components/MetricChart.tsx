import { useMemo } from 'react';
import ReactECharts from 'echarts-for-react';
import type { EChartsOption } from 'echarts';
import { Alert, Box, Typography } from '@mui/material';
import { chartTokens, tooltipStyle } from '@/features/dashboard/chartTheme';
import type { MetricSeries } from '../types';

/**
 * One metric over time.
 *
 * A time axis rather than a category axis, and that is the whole point of the chart: meters report
 * on their own duty cycles and go silent under a comms fault, so the gap between two readings is
 * itself information. A category axis would space them evenly and draw a healthy-looking line
 * straight through a four-hour outage.
 *
 * One series, one hue, drawn in the cyan the pipe network already uses — there is nothing here to
 * distinguish by colour, so spending the identity channel would be re-encoding what position
 * already shows.
 */
export function MetricChart({ series }: { series: MetricSeries }) {
  const option = useMemo<EChartsOption>(() => {
    const points = series.points
      .filter((point) => point.value !== undefined && point.value !== null)
      .map((point) => [new Date(point.observedAt).getTime(), point.value as number]);

    return {
      grid: { left: 8, right: 16, top: 16, bottom: 24, containLabel: true },
      tooltip: {
        trigger: 'axis',
        ...tooltipStyle,
        formatter: (params: unknown) => {
          const rows = params as { data: [number, number] }[];
          const row = rows[0];
          if (!row) return '';
          const [time, value] = row.data;
          return [
            new Date(time).toLocaleString(undefined, {
              month: 'short',
              day: '2-digit',
              hour: '2-digit',
              minute: '2-digit',
              second: '2-digit',
            }),
            `<strong>${value.toLocaleString(undefined, { maximumFractionDigits: 3 })}</strong>` +
              (series.unit ? ` ${escapeHtml(series.unit)}` : ''),
          ].join('<br/>');
        },
      },
      xAxis: {
        type: 'time',
        axisLabel: { color: chartTokens.axisLabel, fontSize: 11, hideOverlap: true },
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: { show: false },
      },
      yAxis: {
        type: 'value',
        name: series.unit ?? '',
        nameLocation: 'end',
        nameGap: 10,
        nameTextStyle: { color: chartTokens.axisTitle, fontSize: 11, align: 'left' },
        axisLabel: {
          color: chartTokens.axisLabel,
          fontSize: 11,
          formatter: (value: number) => value.toLocaleString(undefined, { maximumFractionDigits: 2 }),
        },
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: { lineStyle: { color: chartTokens.grid, width: 1, type: 'solid' } },
        /*
         * A cumulative register climbs slowly from a large opening value, so a zero-based axis
         * would flatten a day's consumption into an invisible wobble at the top of the plot. Only
         * a counter gets a floating baseline: for a measurement, suppressing zero exaggerates
         * small variation into apparent drama.
         */
        scale: series.kind === 'COUNTER',
      },
      series: [
        {
          type: 'line',
          data: points,
          showSymbol: points.length <= 120,
          symbolSize: 5,
          // Straight segments, not a spline. A smoothed curve invents plausible intermediate
          // values between two readings that were minutes apart, which is precisely the thing a
          // telemetry chart must not do.
          smooth: false,
          lineStyle: { color: chartTokens.pipeline, width: 2 },
          itemStyle: { color: chartTokens.pipeline },
          areaStyle: { color: 'rgba(8,145,178,0.12)' },
        },
      ],
    };
  }, [series]);

  if (series.points.length === 0) {
    return (
      <Box sx={{ py: 6, textAlign: 'center' }}>
        <Typography variant="body2" color="text.secondary">
          No {series.label.toLowerCase()} readings in this window.
        </Typography>
      </Box>
    );
  }

  return (
    <Box>
      {series.truncated ? (
        <Alert severity="info" variant="outlined" sx={{ mx: 2, mt: 1.5 }}>
          This window holds more readings than can be charted at once — showing the most recent
          {' '}
          {series.points.length.toLocaleString()}. Narrow the window to see the whole period.
        </Alert>
      ) : null}
      <ReactECharts option={option} style={{ height: 320 }} notMerge lazyUpdate />
      {series.kind === 'COUNTER' ? (
        <Typography variant="caption" color="text.secondary" sx={{ px: 2, pb: 1.5, display: 'block' }}>
          A cumulative register — it only climbs. The consumption over any period is the difference
          between its endpoints, not the height of the line.
        </Typography>
      ) : null}
    </Box>
  );
}

/** Tooltips are raw HTML, so anything interpolated into one is escaped. */
function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}
