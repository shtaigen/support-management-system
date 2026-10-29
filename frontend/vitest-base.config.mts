import { defineConfig } from 'vitest/config';

// Vitest 5 caches modules by the file on disk, but @angular/build:unit-test serves specs
// from an in-memory bundle, so changes to imported files were not picked up.
// @angular/build 22.2.3 forces this off itself (angular/angular-cli#34311); remove this
// file after upgrading.
export default defineConfig({
  test: {
    fsModuleCache: false,
  },
});