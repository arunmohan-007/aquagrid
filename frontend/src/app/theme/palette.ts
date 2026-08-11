/**
 * The AquaGrid colour system.
 *
 * Two constraints drove these choices, and both are operational rather than aesthetic:
 *
 * 1. **Saturated red, amber and orange are reserved for alarm state.** A control-room
 *    operator must be able to read severity from colour alone, across the whole product.
 *    That rules those hues out of the brand palette, which is why the primary is a water
 *    blue, the secondary a teal, and the bridge between them a cyan — an "aqua" gradient
 *    that reads as water without spending a single severity colour.
 * 2. **Every pairing meets WCAG AA (4.5:1).** Public-sector procurement requires it, and a
 *    dashboard that is unreadable in daylight on a tablet at a pump station is a dashboard
 *    nobody uses.
 *
 * The product ships dark-only. Surfaces are lifted with progressively lighter slate rather
 * than pure black, because a control room runs dark for hours and a high-contrast black
 * background causes visible halation around light text.
 */

export const brand = {
  50: '#EFF6FF',
  100: '#DBEAFE',
  200: '#BFDBFE',
  300: '#93C5FD',
  400: '#60A5FA',
  500: '#3B82F6',
  600: '#2563EB',
  700: '#1D4ED8',
  800: '#1E40AF',
  900: '#1E3A8A',
  950: '#172554',
} as const;

/**
 * The cyan ramp bridges blue and teal — the middle of the "aqua" gradient. Used for accents,
 * glows and gradient stops; never as a standalone semantic colour.
 */
export const cyan = {
  50: '#ECFEFF',
  100: '#CFFAFE',
  200: '#A5F3FC',
  300: '#67E8F9',
  400: '#22D3EE',
  500: '#06B6D4',
  600: '#0891B2',
  700: '#0E7490',
  800: '#155E75',
  900: '#164E63',
} as const;

export const teal = {
  50: '#E6F7F7',
  100: '#C2EDED',
  200: '#8FDDDD',
  300: '#55C9C9',
  400: '#22B2B2',
  500: '#0E9F9F',
  600: '#0B8080',
  700: '#086363',
  800: '#054545',
  900: '#032C2C',
} as const;

/** Operational severity. Fixed across the product — these mean something. */
export const severity = {
  critical: '#F87171',
  major: '#FB923C',
  minor: '#FBBF24',
  ok: '#34D399',
  info: '#38BDF8',
  unknown: '#94A3B8',
} as const;

export const slate = {
  25: '#FBFCFD',
  50: '#F5F7FA',
  100: '#EBEEF3',
  200: '#DCE1E9',
  300: '#B9C2D0',
  400: '#8C97A8',
  500: '#66707F',
  600: '#4A5361',
  700: '#333B47',
  800: '#1E2530',
  900: '#131A2A',
  950: '#0A0E1A',
} as const;
