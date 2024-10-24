import { WebPlugin } from '@capacitor/core';

import type { Camera2Plugin } from './definitions';

export class Camera2Web extends WebPlugin implements Camera2Plugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }
}
