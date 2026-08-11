/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** API base path. Defaults to the same-origin '/api/v1', which is the proxied dev setup. */
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_APP_NAME?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
