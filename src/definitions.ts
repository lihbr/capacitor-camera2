export interface Camera2Plugin {
  echo(options: { value: string }): Promise<{ value: string }>;
}
