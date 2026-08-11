/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  /*
   * Dark mode is driven by the same attribute MUI writes, so the two systems can never
   * disagree about which scheme is active. Without this, a Tailwind utility and an MUI
   * component sitting side by side will render in different schemes.
   */
  darkMode: ['selector', '[data-mui-color-scheme="dark"]'],
  /*
   * MUI is configured with `cssVariables: true`, so it emits its palette as CSS custom
   * properties. Tailwind consumes those same variables here instead of redeclaring the
   * brand colours — one source of truth, and a theme change is a one-line edit.
   */
  theme: {
    extend: {
      colors: {
        brand: {
          DEFAULT: 'var(--mui-palette-primary-main)',
          light: 'var(--mui-palette-primary-light)',
          dark: 'var(--mui-palette-primary-dark)',
          contrast: 'var(--mui-palette-primary-contrastText)',
        },
        accent: {
          DEFAULT: 'var(--mui-palette-secondary-main)',
          dark: 'var(--mui-palette-secondary-dark)',
        },
        surface: {
          DEFAULT: 'var(--mui-palette-background-paper)',
          sunken: 'var(--mui-palette-background-default)',
        },
        content: {
          DEFAULT: 'var(--mui-palette-text-primary)',
          muted: 'var(--mui-palette-text-secondary)',
        },
        outline: 'var(--mui-palette-divider)',
      },
      fontFamily: {
        sans: ['Inter var', 'Inter', 'system-ui', '-apple-system', 'Segoe UI', 'sans-serif'],
        mono: ['JetBrains Mono', 'ui-monospace', 'SFMono-Regular', 'monospace'],
      },
      keyframes: {
        'flow-dash': { to: { strokeDashoffset: '-1000' } },
        'fade-up': {
          from: { opacity: '0', transform: 'translateY(12px)' },
          to: { opacity: '1', transform: 'translateY(0)' },
        },
        ripple: {
          '0%': { transform: 'scale(0.6)', opacity: '0.6' },
          '100%': { transform: 'scale(2.4)', opacity: '0' },
        },
      },
      animation: {
        'flow-dash': 'flow-dash 18s linear infinite',
        'fade-up': 'fade-up 420ms cubic-bezier(0.22, 1, 0.36, 1) both',
        ripple: 'ripple 3.2s ease-out infinite',
      },
    },
  },
  plugins: [],
};
