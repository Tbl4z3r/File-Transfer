import qrcode from './vendor/qrcode.mjs';

// v1 is ASCII; explicitly encode UTF-8 to keep the payload byte exact.
qrcode.stringToBytes = input => Array.from(new TextEncoder().encode(input));
export const QUIET_ZONE_MODULES = 4;
export const MODULE_PIXELS = 6;

/** The caller supplies exactly JSON.stringify(descriptor), with no URI wrapper. */
export function encodePairingQr(payload: string): { matrix: boolean[][]; dataUrl: string; pixels: number } {
  const code = qrcode(0, 'M');
  code.addData(payload, 'Byte');
  code.make();
  const count = code.getModuleCount();
  const matrix = Array.from({ length: count }, (_, row) =>
    Array.from({ length: count }, (_, col) => code.isDark(row, col)));
  const side = count + QUIET_ZONE_MODULES * 2;
  const pixels = side * MODULE_PIXELS;
  const dataUrl = code.createDataURL(MODULE_PIXELS, QUIET_ZONE_MODULES * MODULE_PIXELS);
  return { matrix, dataUrl, pixels };
}
