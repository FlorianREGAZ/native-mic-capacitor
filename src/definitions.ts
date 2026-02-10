export interface NativeMicPlugin {
  echo(options: { value: string }): Promise<{ value: string }>;
}
