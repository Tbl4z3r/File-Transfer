// Minimal interface for the pinned vendor ESM copied by build.mjs.
interface Code {
  addData(data: string, mode: 'Byte'): void;
  make(): void;
  createDataURL(cellSize: number, margin: number): string;
  getModuleCount(): number;
  isDark(row: number, column: number): boolean;
}
interface Factory {
  (version: 0, correction: 'M'): Code;
  stringToBytes(input: string): number[];
}
declare const qrcode: Factory;
export default qrcode;
