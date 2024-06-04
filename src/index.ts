import { registerPlugin } from '@capacitor/core';

import type { Camera2Plugin } from './definitions';

const Camera2 = registerPlugin<Camera2Plugin>('Camera2', {
  web: () => import('./web').then(m => new m.Camera2Web()),
});

export * from './definitions';
export { Camera2 };
