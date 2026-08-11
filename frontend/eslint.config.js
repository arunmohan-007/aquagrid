import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import react from 'eslint-plugin-react';

/**
 * ESLint flat config.
 *
 * The repository had none, so `npm run lint` — one of the two frontend gates AGENTS.md documents —
 * had been failing outright rather than passing. A gate that errors on startup is worse than no
 * gate: it looks like it is running.
 *
 * Scoped deliberately narrowly. TypeScript already owns type correctness and `tsc --noEmit` is the
 * other gate, so nothing here duplicates it; what is left is the class of mistake the compiler
 * cannot see. In practice that is almost entirely the React hooks rules — a missing dependency or a
 * conditional hook is a real, shipped bug that types are blind to — plus the handful of core rules
 * that catch genuine errors rather than expressing a preference.
 *
 * Stylistic rules are omitted on purpose. `--max-warnings 0` means every rule enabled here is a
 * build failure, and a build that fails over quote style trains people to bypass the build.
 */
export default tseslint.config(
  {
    // Build output and dependencies. Linting dist/ would report on minified vendor code.
    ignores: ['dist/**', 'node_modules/**', 'coverage/**'],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      react,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,

      /*
       * Two rules recovered from the source rather than chosen fresh. The codebase carries
       * `eslint-disable-next-line` comments for `no-console` and `react/no-array-index-key`, which
       * only make sense if the lost config enabled both — and a disable comment for a rule that is
       * off is itself reported, so leaving them out would have meant deleting deliberate,
       * well-reasoned suppressions to satisfy a config that had forgotten why they existed.
       *
       * `no-console` bans every level, including `error`: MapView's four suppressions are all
       * `console.error`, so an `allow: ['error']` list would silently make them redundant.
       */
      'no-console': 'error',
      'react/no-array-index-key': 'error',

      /*
       * Unused variables are an error, with the conventional underscore escape hatch — a caught
       * error that is deliberately ignored, or a destructured field pulled out only to omit it,
       * are both legitimate and both read as intentional when prefixed.
       */
      '@typescript-eslint/no-unused-vars': [
        'error',
        {
          argsIgnorePattern: '^_',
          varsIgnorePattern: '^_',
          caughtErrorsIgnorePattern: '^_',
          destructuredArrayIgnorePattern: '^_',
        },
      ],

      /*
       * Off, not error. `any` is a smell, but the codebase reaches for it at genuine boundaries —
       * MapLibre's untyped event payloads, shpjs — and the honest fix there is a typed wrapper, not
       * a lint rule that makes the build red until someone writes `unknown` and casts anyway.
       */
      '@typescript-eslint/no-explicit-any': 'off',

      // Empty catch blocks and unreachable code are always mistakes.
      'no-empty': ['error', { allowEmptyCatch: false }],
    },
  },
  {
    // Config and tooling files run in Node, not the browser.
    files: ['*.config.{js,ts}', 'vite.config.ts'],
    languageOptions: {
      globals: globals.node,
    },
  },
);
