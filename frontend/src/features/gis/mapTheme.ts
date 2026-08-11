import { cyan, slate, teal } from '@/app/theme/palette';

/**
 * Chrome tokens for the map console.
 *
 * The map surface keeps dark chrome, which is a deliberate choice rather than a consequence of
 * the product being dark-only. Controls here float over cartography the application does not
 * control — OSM in one moment, satellite imagery the next — and a light panel over bright
 * imagery loses its edge entirely. Dark, slightly translucent chrome reads against both, which
 * is why every serious map product converges on it.
 *
 * Surfaces now carry a faint blue tint (rather than neutral grey) so the console shares one
 * identity with the aqua brand. The accent stays the cyan-leaning bright end of the aqua
 * gradient — the same family as the launcher/login accents — so the map console reads as part
 * of the product, not a third-party viewer bolted on. `palette.ts` reserves saturated red, amber
 * and orange for alarm severity and green for `ok`, so an operational colour must never be spent
 * on a "this panel is selected" affordance.
 */
export const mapChrome = {
  /** Rail and panel body. Opaque enough to read against satellite imagery; faint blue tint for brand cohesion. */
  surface: 'rgba(13, 20, 38, 0.94)',
  /** Raised cards inside a panel. Slightly lifted, blue-tinted. */
  card: 'rgba(30, 41, 64, 0.92)',
  cardHover: 'rgba(46, 58, 84, 0.95)',
  /** Floating controls that sit directly on the map (search, zoom, scale). */
  floating: 'rgba(13, 20, 38, 0.82)',
  border: 'rgba(255, 255, 255, 0.10)',
  borderStrong: 'rgba(255, 255, 255, 0.18)',
  /** Accent-tinted gradient hairline, used on floating containers for depth. */
  borderGradient: 'linear-gradient(180deg, rgba(103,232,249,0.35), rgba(103,232,249,0.04))',

  text: slate[50],
  textMuted: slate[300],
  textFaint: slate[400],

  // Cyan-leaning accent — the bright end of the aqua gradient, brighter than the old teal so it
  // reads as the same family as the launcher/login accents.
  accent: cyan[300],
  accentStrong: teal[300],
  accentSoft: 'rgba(103, 232, 249, 0.16)',

  shadow: '0 10px 34px rgba(0, 0, 0, 0.45)',
  shadowLg: '0 18px 48px rgba(0, 0, 0, 0.55)',
  /** Accent-coloured glow for elevated map controls (hover/active states). */
  glow: '0 0 18px rgba(34, 211, 238, 0.35)',
  glowStrong: '0 0 22px rgba(34, 211, 238, 0.55)',
} as const;

/**
 * Per-tool colour identities.
 *
 * The four rail tools each get their own colour family so an operator can read "which tool is
 * live" from colour alone, at a glance, without reading the label. The families are drawn from
 * the cool half of the spectrum (blue → cyan → teal → violet) so none of them collides with the
 * reserved severity hues (red / amber / orange) or with `ok` green.
 *
 * Keys match `RailTool` from `MapRail`, but typed as plain strings here to avoid an import cycle
 * (that module imports `mapTheme`; this module would import it back).
 */
export interface ToolTheme {
  /** Gradient start colour (top). */
  from: string;
  /** Gradient end colour (bottom). */
  to: string;
  /** Soft, low-alpha tint used for backgrounds behind active chrome. */
  soft: string;
  /** Glow box-shadow for the active state, in the tool's hue. */
  glow: string;
}

export const TOOL_THEMES: Record<string, ToolTheme> = {
  // Layers — the aqua brand gradient, since layers are the default/primary tool.
  layers: {
    from: '#3B82F6',
    to: '#22D3EE',
    soft: 'rgba(59,130,246,0.16)',
    glow: '0 0 16px rgba(59,130,246,0.40)',
  },
  // Base map — indigo → violet, the "cartography / style" family.
  basemap: {
    from: '#6366F1',
    to: '#A855F7',
    soft: 'rgba(99,102,241,0.16)',
    glow: '0 0 16px rgba(99,102,241,0.40)',
  },
  // Measure — teal → emerald, the "tooling / analysis" family (kept clear of the `ok` green hue).
  measure: {
    from: '#14B8A6',
    to: '#22D3EE',
    soft: 'rgba(20,184,166,0.16)',
    glow: '0 0 16px rgba(20,184,166,0.40)',
  },
  // Legend — violet → pink, the "reference / key" family.
  legend: {
    from: '#A855F7',
    to: '#EC4899',
    soft: 'rgba(168,85,247,0.16)',
    glow: '0 0 16px rgba(168,85,247,0.40)',
  },
};

/** Default tool theme, used when an unknown tool id is passed. Falls back to Layers. */
export const DEFAULT_TOOL_THEME = TOOL_THEMES.layers!;

/** Returns the tool theme for an id, defaulting safely. */
export function toolTheme(id: string | null | undefined): ToolTheme {
  return (id && TOOL_THEMES[id]) || DEFAULT_TOOL_THEME;
}

/** A vertical CSS gradient for a tool, e.g. for panel headers and active rail pills. */
export function toolGradientCss(id: string | null | undefined): string {
  const t = toolTheme(id);
  return `linear-gradient(180deg, ${t.from}, ${t.to})`;
}

/** Width of the slide-out panel beside the rail. */
export const PANEL_WIDTH = 316;

/** Width of the always-visible icon rail. */
export const RAIL_WIDTH = 76;
