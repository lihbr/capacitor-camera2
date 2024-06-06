import { WebPlugin } from '@capacitor/core';

import type { Camera2Plugin } from './definitions';

const noop = (name: string) => async () => {
  throw new Error(`Method ${name} is not implemented for web platform`);
};

export class Camera2Web extends WebPlugin implements Camera2Plugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }

  start = noop('start');
  stop = noop('stop');
}