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
  start(options: Camera2Options): Promise<void>;
  stop(): Promise<void>;
  capture(options: {
    picturePath: string
    thumbnailPath?: string
    thumbnailWidth?: number
    thumbnailHeight?: number
    thumbnailQuality?: number
  }): Promise<void>;
  pictureToThumbnail(options: {
    picture: string,
    width: number,
    height: number,
    quality?: number,
  }): Promise<{ thumbnail: string }>;

  setViewFinderSize(options: { width: number; height: number }): Promise<void>;

  getShutterSpeedRange(): Promise<{ value: [min: number, max: number] | null }>;
  setShutterSpeed(options: { value: number }): Promise<void>;

  getApertureRange(): Promise<{ value: [min: number, max: number] | null }>;
  setAperture(options: { value: number }): Promise<void>;

  getIsoRange(): Promise<{ value: [min: number, max: number] | null }>;
  setIso(options: { value: number }): Promise<void>;
}
