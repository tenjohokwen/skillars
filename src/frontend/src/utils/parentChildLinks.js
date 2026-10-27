/**
 * Per-child destinations a PARENT can reach for one linked player profile, keyed by purpose.
 *
 * Single source for the parent dashboard's child-card chips, the child hub's (ParentPlayerPortalPage)
 * link bar and MainLayout's "My players" drawer entries, so the three never drift onto different
 * paths. Every path here is a route whose meta admits the PARENT role (see router/routes.js) — note
 * the subscription route's singular `parent/player/` prefix, which is the real registered path.
 *
 * @param {string|number} playerId PlayerProfile PK (NOT a user id)
 */
export function parentChildLinks(playerId) {
  return {
    sessions: `/parent/players/${playerId}/sessions`,
    development: `/parent/players/${playerId}/development`,
    packs: `/parent/players/${playerId}/packs`,
    subscription: `/parent/player/${playerId}/subscription`,
    lockerRoom: `/player/locker-room/${playerId}`,
  }
}
