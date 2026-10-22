// Preserve the shell's cache version for lazily loaded assets.
const version = new URL(import.meta.url).search;
export const assetUrl = (path) => `${path}${version}`;
