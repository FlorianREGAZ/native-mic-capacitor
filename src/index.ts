import { registerPlugin } from '@capacitor/core';

import type { NativeMicPlugin } from './definitions';

const NativeMic = registerPlugin<NativeMicPlugin>('NativeMic', {
  web: () => import('./web').then((m) => new m.NativeMicWeb()),
});

export * from './definitions';
export { NativeMic };
