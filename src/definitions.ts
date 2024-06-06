type Camera2Options = {
  x?: number;
  y?: number;
  width?: number;
  height?: number;
  paddingBottom?: number;
  toBack?: boolean;
  lockAndroidOrientation?: boolean;
};

export interface Camera2Plugin {
  echo(options: { value: string }): Promise<{ value: string }>;

  start(options: Camera2Options): Promise<void>;
  stop(): Promise<void>;
}
