import { slate } from '@/app/theme/palette';

/**
 * Chart tokens for the dashboard.
 *
 * Every colour here was run through the palette validator against the dark card surface
 * (`#131A2A`) rather than picked by eye, because "does this pair survive deuteranopia" is a
 * computation, not a matter of taste. What the checks decided:
 *
 * - **Pipe network — one hue, not many.** Panchayats are nominal: swapping two of them changes
 *   nothing, so colouring each bar differently would spend the identity channel re-encoding what
 *   bar length already shows. One cyan, which is also the network's colour on the map.
 * - **Facilities — three hues that survive CVD.** The map paints tanks violet and bore wells
 *   fuchsia, and that pair collapses under deuteranopia (ΔE 5.8, a hard fail) — adjacent on the
 *   colour wheel is exactly what red-green blindness compresses. Re-stepping either one inside its
 *   own family does not fix it (violet↔fuchsia bottoms out at 4.1), so the trio moves to
 *   violet / blue / pink, which clears every gate. The weakest remaining pair (violet↔blue, ΔE 6.6)
 *   sits in the floor band, which is legal only with secondary encoding — hence the legend, the
 *   value labels on every segment, the 2px surface gaps between them, and the table underneath.
 *   Those are not decoration; they are what makes this palette permissible.
 */
export const chartTokens = {
  /** The card surface the marks are validated against. Also the colour of the 2px stack gaps. */
  surface: '#131A2A',

  /** Recessive grid: one step off the surface, hairline, solid. */
  grid: 'rgba(255,255,255,0.07)',
  axisLabel: slate[400],
  axisTitle: slate[300],
  valueLabel: slate[50],

  /** Pipe network — single hue. Cyan, matching the pipeline layer on the map. */
  pipeline: '#0891B2',

  /**
   * Facility identities. Order matters: the stack is drawn tanks → bore wells → open wells so the
   * one floor-band pair (violet ↔ blue) never shares an edge.
   */
  facilities: {
    tanks: '#7C3AED',
    boreWells: '#EC4899',
    openWells: '#3B82F6',
  },
} as const;

/** Shared ECharts tooltip styling, so every chart on the page pops the same panel. */
export const tooltipStyle = {
  backgroundColor: 'rgba(10,14,26,0.94)',
  borderColor: 'rgba(255,255,255,0.14)',
  borderWidth: 1,
  padding: [8, 12] as [number, number],
  textStyle: { color: slate[50], fontSize: 12.5 },
  extraCssText: 'border-radius:10px; box-shadow:0 12px 32px rgba(0,0,0,0.55); backdrop-filter:blur(8px);',
};

/** Metres → a display string in the unit a network is actually discussed in. */
export function formatKm(metres: number, digits = 2): string {
  return `${(metres / 1000).toFixed(digits)} km`;
}
