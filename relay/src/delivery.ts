/** Attempt identity changes on an explicit retry; voucher identity never does. */
export function deliveryId(voucher: string, attempt: number): string {
  return attempt > 0 ? `${voucher}:retry:${attempt}` : voucher;
}

export function supportsDurableRetry(version: string): boolean {
  const m = /^(\d+)\.(\d+)\.(\d+)$/.exec(version);
  if (!m) return false;
  const [, major, minor, patch] = m.map(Number);
  return major! > 1 || (major === 1 && (minor! > 15 || (minor === 15 && patch! >= 1)));
}
