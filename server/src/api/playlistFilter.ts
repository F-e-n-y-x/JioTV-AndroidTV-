import type { Channel } from "../jio/channels";

export interface PlaylistFilter {
  langs: Set<string>;
  groups: Set<string>;
  /** Only these ids (favourites), or null for no favourites filter. */
  favs: Set<string> | null;
  /** `drm=hide`: leave out channels known to need Widevine. */
  hideDrm: boolean;
  /** Channel ids the stream proxy has learned are Widevine-only (store/db `stream_modes`). */
  drmOnlyIds: ReadonlySet<string>;
}

/**
 * How `drm=hide` decides a channel needs Widevine:
 *  - Jio's channel lists carry no reliable DRM flag (`isDrm` is absent from v1.4 and v3.1), and
 *    `is_premium` / `plan_type` describe the subscription, not the encryption: most premium channels
 *    still have the plain "Fallback" HLS that external players can open.
 *  - So the source of truth is what the stream proxy LEARNS on a channel's first live play: if the
 *    non-DRM HLS is dead or missing and only the DASH works, it stores mode "drm" in SQLite; if the
 *    HLS works it stores "hls". `drm=hide` hides the "drm" ones (plus any channel the list itself
 *    flags `isDrm`, should Jio ever send it again).
 *  - A channel nobody has played yet is shown; once a player tries it and gets the 415 from `/live`,
 *    it is learned and hidden from the next playlist refresh.
 */
export function needsWidevine(c: Channel, drmOnlyIds: ReadonlySet<string>): boolean {
  return c.isDrm || drmOnlyIds.has(c.id);
}

export function filterPlaylistChannels(all: Channel[], f: PlaylistFilter): Channel[] {
  return all.filter((c) => {
    if (f.langs.size && !f.langs.has(c.language.toLowerCase())) return false;
    if (f.groups.size && !f.groups.has(c.group.toLowerCase())) return false;
    if (f.favs && !f.favs.has(c.id)) return false;
    if (f.hideDrm && needsWidevine(c, f.drmOnlyIds)) return false;
    return true;
  });
}
