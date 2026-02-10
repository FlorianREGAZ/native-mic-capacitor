import { WebPlugin } from '@capacitor/core';

import type { NativeMicPlugin } from './definitions';

export class NativeMicWeb extends WebPlugin implements NativeMicPlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }
}
