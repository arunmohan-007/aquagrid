import { createTheme, type Theme } from '@mui/material/styles';
import { brand, cyan, severity, slate, teal } from './palette';

/*
 * AquaGrid ships dark-only. The light colour scheme and its toggle are gone: a single, polished
 * dark identity with an aqua gradient system (blue → cyan → teal), depth via layered shadows,
 * accent-coloured glows, and frosted glass on the chrome.
 *
 * `cssVariables` stays on (with the `data-mui-color-scheme` selector) so Tailwind utilities and
 * the `.ag-glass` class keep reading the same CSS custom properties. The scheme is hard-pinned to
 * `dark` in `index.html` before first paint; the `colorSchemes.dark` block below is the only
 * palette the app uses.
 */

/** Reusable gradient stops, also emitted as CSS variables for non-MUI consumers. */
const aquaGradient = 'linear-gradient(135deg, #3B82F6 0%, #22D3EE 50%, #2DD4BF 100%)';
const brandGradient = 'linear-gradient(135deg, #2563EB 0%, #3B82F6 100%)';

/**
 * MUI's default shadow array is tuned for light surfaces. On a dark UI it reads as muddy grey,
 * so the whole array is rebuilt: darker base + a tinted family (16-24) that leaks the brand
 * accent, used for elevated cards and hover states.
 */
const darkShadows = [
  'none',
  '0 1px 2px rgba(0,0,0,0.4)',
  '0 2px 6px rgba(0,0,0,0.4)',
  '0 4px 12px rgba(0,0,0,0.42)',
  '0 6px 16px rgba(0,0,0,0.44)',
  '0 8px 20px rgba(0,0,0,0.46)',
  '0 10px 26px rgba(0,0,0,0.48)',
  '0 12px 30px rgba(0,0,0,0.5)',
  '0 14px 36px rgba(0,0,0,0.52)',
  '0 16px 40px rgba(0,0,0,0.54)',
  '0 18px 46px rgba(0,0,0,0.56)',
  '0 20px 50px rgba(0,0,0,0.58)',
  '0 22px 54px rgba(0,0,0,0.6)',
  // Accent-tinted elevations: the brand leaks into the shadow on raised/hovered elements.
  '0 8px 24px rgba(59,130,246,0.20), 0 2px 8px rgba(0,0,0,0.5)',
  '0 10px 30px rgba(59,130,246,0.22), 0 4px 10px rgba(0,0,0,0.5)',
  '0 12px 36px rgba(59,130,246,0.24), 0 6px 14px rgba(0,0,0,0.5)',
  '0 16px 44px rgba(59,130,246,0.26), 0 8px 18px rgba(0,0,0,0.52)',
  '0 20px 52px rgba(59,130,246,0.28), 0 10px 22px rgba(0,0,0,0.54)',
  '0 24px 60px rgba(34,211,238,0.26), 0 12px 26px rgba(0,0,0,0.56)',
  '0 28px 68px rgba(34,211,238,0.28), 0 14px 30px rgba(0,0,0,0.58)',
  '0 32px 76px rgba(45,212,191,0.28), 0 16px 34px rgba(0,0,0,0.6)',
  '0 36px 84px rgba(45,212,191,0.30), 0 18px 38px rgba(0,0,0,0.62)',
  '0 40px 92px rgba(45,212,191,0.32), 0 20px 42px rgba(0,0,0,0.64)',
  '0 44px 100px rgba(45,212,191,0.34), 0 22px 46px rgba(0,0,0,0.66)',
  '0 48px 108px rgba(45,212,191,0.36), 0 24px 50px rgba(0,0,0,0.68)',
];

export const theme: Theme = createTheme({
  cssVariables: {
    colorSchemeSelector: 'data-mui-color-scheme',
  },
  shadows: darkShadows as Theme['shadows'],
  colorSchemes: {
    dark: {
      palette: {
        primary: {
          main: brand[400],
          light: brand[300],
          dark: brand[600],
          contrastText: slate[950],
        },
        secondary: {
          main: teal[300],
          light: teal[200],
          dark: teal[500],
          contrastText: slate[950],
        },
        error: { main: severity.critical },
        warning: { main: severity.major },
        info: { main: severity.info },
        success: { main: severity.ok },
        background: { default: slate[950], paper: slate[900] },
        text: { primary: slate[50], secondary: slate[300] },
        divider: 'rgba(255, 255, 255, 0.10)',
      },
    },
  },

  shape: { borderRadius: 12 },

  typography: {
    fontFamily: "'Inter var', Inter, system-ui, -apple-system, 'Segoe UI', sans-serif",
    h1: { fontSize: '2rem', fontWeight: 700, letterSpacing: '-0.02em' },
    h2: { fontSize: '1.5rem', fontWeight: 700, letterSpacing: '-0.015em' },
    h3: { fontSize: '1.25rem', fontWeight: 600, letterSpacing: '-0.01em' },
    h4: { fontSize: '1.125rem', fontWeight: 600 },
    subtitle1: { fontSize: '0.95rem', fontWeight: 600 },
    body2: { fontSize: '0.875rem', lineHeight: 1.6 },
    button: { textTransform: 'none', fontWeight: 600, letterSpacing: 0 },
    caption: { fontSize: '0.78rem', letterSpacing: '0.01em' },
  },

  components: {
    MuiCssBaseline: {
      styleOverrides: {
        /*
         * Honours the operating system's reduced-motion setting. Vestibular disorders are common,
         * and an animation the user cannot switch off is an accessibility defect, not a flourish.
         * The new aqua accents are gradient/glow based — all animation collapses under this guard.
         */
        '@media (prefers-reduced-motion: reduce)': {
          '*, *::before, *::after': {
            animationDuration: '0.01ms !important',
            animationIterationCount: '1 !important',
            transitionDuration: '0.01ms !important',
          },
        },
        ':root': {
          // Gradient + glow tokens for non-MUI consumers (the launcher, glass surfaces, SVG).
          '--ag-gradient-aqua': aquaGradient,
          '--ag-gradient-brand': brandGradient,
          '--ag-glow-accent': 'rgba(34,211,238,0.35)',
          '--ag-glow-brand': 'rgba(59,130,246,0.35)',
          '--ag-cyan': cyan[400],
          '--ag-teal': teal[300],
        },
        ':focus-visible': {
          outline: '2px solid var(--mui-palette-primary-main)',
          outlineOffset: '2px',
        },
        // The deep base, with a faint top radial wash so the page is never a flat black slab.
        body: {
          backgroundColor: slate[950],
          backgroundImage:
            'radial-gradient(1200px 600px at 50% -200px, rgba(59,130,246,0.10), transparent 70%)',
          backgroundAttachment: 'fixed',
        },
      },
    },
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: {
        root: { borderRadius: 10, paddingInline: 18, minHeight: 42 },
        sizeLarge: { minHeight: 48, fontSize: '0.975rem' },
        containedPrimary: {
          backgroundImage: brandGradient,
          boxShadow: '0 6px 20px rgba(59,130,246,0.30)',
          '&:hover': {
            boxShadow: '0 10px 28px rgba(59,130,246,0.40)',
            backgroundImage: brandGradient,
          },
        },
      },
    },
    MuiTextField: {
      defaultProps: { variant: 'outlined', size: 'medium' },
    },
    MuiOutlinedInput: {
      styleOverrides: {
        root: {
          borderRadius: 10,
          '&:hover .MuiOutlinedInput-notchedOutline': {
            borderColor: 'rgba(255,255,255,0.25)',
          },
        },
      },
    },
    MuiPaper: {
      styleOverrides: {
        root: { backgroundImage: 'none' },
      },
    },
    MuiCard: {
      styleOverrides: {
        root: {
          // A subtle hairline + lifted shadow give cards presence on the deep surface.
          backgroundImage:
            'linear-gradient(180deg, rgba(255,255,255,0.03), rgba(255,255,255,0))',
        },
      },
    },
    MuiAppBar: {
      styleOverrides: {
        root: {
          // Frosted glass top bar: translucent surface + blur reads as layered depth.
          backgroundImage: 'none',
          backgroundColor: 'rgba(10,14,26,0.72)',
          backdropFilter: 'blur(14px) saturate(140%)',
        },
      },
    },
    MuiChip: {
      styleOverrides: {
        root: { fontWeight: 600 },
        outlinedPrimary: {
          borderColor: 'rgba(96,165,250,0.40)',
          backgroundColor: 'rgba(59,130,246,0.10)',
        },
      },
    },
    MuiAlert: {
      styleOverrides: {
        root: { borderRadius: 10, alignItems: 'center' },
      },
    },
    MuiTooltip: {
      defaultProps: { arrow: true, enterDelay: 400 },
    },
  },
});

// Keep the cyan/teal imports referenced (used by the gradient tokens above and mapTheme consumers).
void cyan;
