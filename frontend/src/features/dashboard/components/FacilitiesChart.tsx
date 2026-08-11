import { useMemo } from 'react';
import ReactECharts from 'echarts-for-react';
import type { EChartsOption } from 'echarts';
import { chartTokens, tooltipStyle } from '../chartTheme';
import type { PanchayatSummary } from '../api/dashboardApi';

const BAR_MAX_WIDTH = 24;
const ROW_HEIGHT = 46;

/**
 * Series order is also the stack order, and it is not arbitrary.
 *
 * Violet and blue are the one pair in this palette that sits in the CVD floor band (ΔE 6.6), so
 * bore-well pink is stacked between them and they never share an edge. See `chartTheme.ts`.
 */
const SERIES = [
  { key: 'tanks', label: 'Over Head Tanks', colour: chartTokens.facilities.tanks },
  { key: 'boreWells', label: 'Bore Wells', colour: chartTokens.facilities.boreWells },
  { key: 'openWells', label: 'Open Wells', colour: chartTokens.facilities.openWells },
] as const;

/**
 * Mapped facilities by panchayat.
 *
 * Stacked rather than grouped: the question an operator asks of this chart is "how much is in each
 * panchayat, and of what" — a part-to-whole reading that a stack answers in one bar length, while
 * three grouped bars per row would triple the height and still need mental addition for the total.
 *
 * Segments are separated by 2px of the surface colour rather than by an outline: a stroke would add
 * ink that carries no data, whereas the gap does the separating with nothing at all. That gap, the
 * legend and the per-segment value labels are what license the one floor-band colour pair.
 */
export function FacilitiesChart({ rows }: { rows: PanchayatSummary[] }) {
  const option = useMemo<EChartsOption>(() => {
    // Busiest panchayat at the top, by total facilities.
    const total = (r: PanchayatSummary) => r.tanks + r.openWells + r.boreWells;
    const ordered = [...rows].sort((a, b) => total(a) - total(b));

    return {
      grid: { left: 8, right: 48, top: 8, bottom: 24, containLabel: true },
      legend: {
        show: true,
        top: -2,
        right: 0,
        itemWidth: 10,
        itemHeight: 10,
        itemGap: 16,
        icon: 'roundRect',
        // Legend text wears a text token; identity comes from the swatch beside it, never from
        // colouring the words.
        textStyle: { color: chartTokens.axisLabel, fontSize: 12 },
      },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(255,255,255,0.04)' } },
        ...tooltipStyle,
      },
      xAxis: {
        type: 'value',
        minInterval: 1,
        axisLabel: { color: chartTokens.axisLabel, fontSize: 11 },
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: { lineStyle: { color: chartTokens.grid, width: 1, type: 'solid' } },
      },
      yAxis: {
        type: 'category',
        data: ordered.map((r) => r.panchayat),
        axisLabel: { color: chartTokens.axisLabel, fontSize: 12 },
        axisLine: { show: false },
        axisTick: { show: false },
      },
      series: SERIES.map((series, index) => ({
        name: series.label,
        type: 'bar' as const,
        stack: 'facilities',
        barMaxWidth: BAR_MAX_WIDTH,
        data: ordered.map((row) => row[series.key]),
        itemStyle: {
          color: series.colour,
          // 1px of surface on each side of a join renders as the 2px gap the spec asks for.
          borderColor: chartTokens.surface,
          borderWidth: 1,
          // Only the outermost segment gets the rounded data end; rounding every segment would
          // read as a row of pills rather than one bar.
          borderRadius: index === SERIES.length - 1 ? [0, 4, 4, 0] : 0,
        },
        label: {
          show: true,
          color: '#FFFFFF',
          fontSize: 11,
          fontWeight: 600,
          // A zero contributes no segment, so its label would float over a neighbour's colour.
          formatter: (params: { value?: unknown }) => {
            const count = Number(params.value ?? 0);
            return count > 0 ? String(count) : '';
          },
        },
        // Drops any label that will not fit its segment instead of letting it overflow or clip —
        // the value is still in the tooltip and the table below.
        labelLayout: { hideOverlap: true },
      })),
    };
  }, [rows]);

  return (
    <ReactECharts
      option={option}
      style={{ height: Math.max(200, rows.length * ROW_HEIGHT + 40), width: '100%' }}
      opts={{ renderer: 'svg' }}
      notMerge
    />
  );
}
