export type Descriptor = {
  v: number;
  host: string;
  port: number;
  server_device_id: string;
  cert_sha256: string;
  pairing_token: string;
};

function hexBytes(hex: string): Uint8Array {
  return Uint8Array.from(hex.match(/../g)!, (pair) => Number.parseInt(pair, 16));
}

function base64url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes))
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replace(/=+$/, "");
}

export function encodeConnectionCode(descriptor: Descriptor): string {
  const { v, host, port, server_device_id, cert_sha256, pairing_token } = descriptor;
  const octets = host.split(".").map(Number);
  if (
    v !== 1 ||
    octets.length !== 4 ||
    octets.some((octet) => !Number.isInteger(octet) || octet < 0 || octet > 255) ||
    octets.join(".") !== host ||
    octets[0] < 1 || octets[0] > 223 ||
    (octets[0] === 169 && octets[1] === 254) ||
    !Number.isInteger(port) || port < 1024 || port > 65535 ||
    server_device_id.length !== 36 ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(server_device_id) ||
    cert_sha256.length !== 64 ||
    !/^[0-9a-f]{64}$/.test(cert_sha256) ||
    pairing_token.length !== 43 ||
    !/^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$/.test(pairing_token)
  ) {
    throw new Error("Invalid pairing descriptor");
  }
  const decoded = atob(pairing_token.replaceAll("-", "+").replaceAll("_", "/") + "=");
  const token = Uint8Array.from(decoded, (character) => character.charCodeAt(0));
  const bytes = new Uint8Array(86);
  bytes.set(octets);
  new DataView(bytes.buffer).setUint16(4, port);
  bytes.set(hexBytes(server_device_id.replaceAll("-", "")), 6);
  bytes.set(hexBytes(cert_sha256), 22);
  bytes.set(token, 54);
  return "FT1-" + base64url(bytes);
}
