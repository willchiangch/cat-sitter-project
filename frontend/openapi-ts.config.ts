import { defineConfig } from '@hey-api/openapi-ts';

export default defineConfig({
  input: '../backend/openapi.json',
  output: {
    path: 'src/services/gen',
    format: 'prettier',
  },
  plugins: [
    '@hey-api/client-axios',
    '@hey-api/typescript',
    '@hey-api/sdk',
  ],
});
