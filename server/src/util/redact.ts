/** Masks the access code in a URL before it reaches the logs (`?code=ABC123` → `?code=[redacted]`). */
export function redactUrl(url: string): string {
  return url.replace(/([?&]code=)[^&#]*/gi, "$1[redacted]");
}
