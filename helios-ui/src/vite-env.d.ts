/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_URL?: string;
  readonly VITE_ENABLE_DEBUG_MODE?: string;
  readonly VITE_ENABLE_EXPERIMENTAL_FEATURES?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
