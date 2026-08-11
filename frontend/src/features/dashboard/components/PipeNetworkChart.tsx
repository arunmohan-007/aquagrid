import { useMemo } from 'react';
import ReactECharts from 'echarts-for-react';
import type { EChartsOption } from 'echarts';
import { chartTokens, formatKm, tooltipStyle } from '../chartTheme';
import type { PanchayatSummary } from '../api/dashboardApi';

/** Bar thickness is capped rather than filling the band — the leftover is deliberate air. */
const BAR_MAX_WIDTH = 24;
const ROW_HEIGHT = 46;

/**
 * Pipe network length by panchayat.
 *
 * Horizontal bars because the categories are place names: read left-to-right at a comfortable
 * size, they never need rotating to 45°, and a new panchayat extends the chart downwards where
 * there is room instead of squeezing every existing bar.
 *
 * One hue for every bar. Panchayats are nominal — swapping two of them changes nothing — so
 * colouring each differently would spend the identity channel restating what bar length already
 * encodes. With a single series there is no legend: the card's title says what is plotted.
 */
export function PipeNetworkChart({ rows }: { rows: PanchayatSummary[] }) {
  const option = useMemo<EChartsOption>(() => {
    // Longest at the top: ECharts draws a category axis bottom-up, so the array is reversed and
    // the biggest bar lands where the eye starts rather than where it gives up.
    const ordered = [...rows].sort((a, b) => a.pipelineLengthM - b.pipelineLengthM);

    return {
      grid: { left: 8, right: 64, top: 8, bottom: 24, containLabel: true },
      tooltip: {
        trigger: 'item',
        ...tooltipStyle,
        formatter: (params: unknown) => {
          const point = params as { dataIndex: number };
          const row = ordered[point.dataIndex];
          if (!row) return '';
          return [
            `<strong>${escapeHtml(row.panchayat)}</strong>`,
            `${formatKm(row.pipelineLengthM)} of pipe`,
            `${row.pipelineCount} segment${row.pipelineCount === 1 ? '' : 's'}`,
          ].join('<br/>');
        },
      },
      xAxis: {
        type: 'value',
        name: 'km',
        nameLocation: 'end',
        nameGap: 8,
        nameTextStyle: { color: chartTokens.axisTitle, fontSize: 11, align: 'right' },
        axisLabel: {
          color: chartTokens.axisLabel,
          fontSize: 11,
          formatter: (value: number) => (value / 1000).toFixed(1),
        },
        axisLine: { show: false },
        axisTick: { show: false },
        // Hairline, solid, one step off the surface — present enough to read a value against,
        // quiet enough that the bars stay the loudest thing on the card.
        splitLine: { lineStyle: { color: chartTokens.grid, width: 1, type: 'solid' } },
      },
      yAxis: {
        type: 'category',
        data: ordered.map((r) => r.panchayat),
        axisLabel: { color: chartTokens.axisLabel, fontSize: 12 },
        axisLine: { show: false },
        axisTick: { show: false },
      },
      series: [
        {
          type: 'bar',
          barMaxWidth: BAR_MAX_WIDTH,
          data: ordered.map((r) => r.pipelineLengthM),
          // Rounded at the data end, square at the baseline: the bar reads as growing from the
          // axis rather than as a floating pill.
          itemStyle: { color: chartTokens.pipeline, borderRadius: [0, 4, 4, 0] },
          label: {
            show: true,
            position: 'right',
            distance: 8,
            color: chartTokens.valueLabel,
            fontSize: 12,
            fontWeight: 600,
            // Typed on `unknown` because ECharts hands every formatter its full callback params
            // union; narrowing here rather than in the signature keeps the option assignable.
            formatter: (params: { value?: unknown }) => formatKm(Number(params.value ?? 0)),
          },
          emphasis: { itemStyle: { color: '#0EA5E9' } },
        },
      ],
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

/** Panchayat names come from an imported file, so they are untrusted text in an HTML tooltip. */
function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (char) => {
    switch (char) {
      case '&': return '&amp;';
      case '<': return '&lt;';
      case '>': return '&gt;';
      case '"': return '&quot;';
      default: return '&#39;';
    }
  });
}
