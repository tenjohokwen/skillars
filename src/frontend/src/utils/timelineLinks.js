/** In-page section ids the development pages put on their radar / reports / homework cards. */
export const TIMELINE_ANCHORS = Object.freeze({
  radar: 'dev-section-radar',
  reports: 'dev-section-reports',
  homework: 'dev-section-homework',
})

/**
 * Player-timeline event → its detail destination for the viewing role (skillars-epics Story 5.6:
 * "each event links to the relevant detail if accessible — session → session detail, report → PDF
 * download"). Only destinations that actually exist for that role are returned; everything else is
 * null and renders no link.
 *
 * Result shapes:
 *   { to }     a vue-router location
 *   { href }   an external URL opened in a new tab (a report's signed PDF URL)
 *   { anchor } the id of a section on the SAME page (radar / reports / homework cards); only returned
 *              when the hosting page declared that anchor in `anchors`
 *
 * @param {object} event timeline event ({ eventType, referenceId, metadata })
 * @param {object} ctx
 * @param {'COACH'|'PARENT'|'PLAYER'} ctx.viewer the role viewing the timeline
 * @param {string|number} ctx.playerId PlayerProfile id the timeline belongs to
 * @param {string[]} [ctx.anchors] in-page section ids present on the hosting page
 * @param {object[]} [ctx.reports] the loaded performance reports ({ id, signedDownloadUrl })
 */
export function resolveTimelineLink(event, { viewer, playerId, anchors = [], reports = [] } = {}) {
  if (!event || !viewer) return null
  const anchor = (id) => (anchors.includes(id) ? { anchor: id } : null)
  const familyViewer = viewer === 'PARENT' || viewer === 'PLAYER'

  switch (event.eventType) {
    case 'SESSION_COMPLETED':
      // Parents and self-registered players share the bookings list (parent/bookings admits both);
      // it is the only booking-detail destination those roles have. A coach has no read-only
      // session-detail page (the session builder is an editor), so no link for coaches.
      return familyViewer ? { to: { name: 'parent-bookings' } } : null

    case 'RADAR_ASSESSMENT':
      return anchor(TIMELINE_ANCHORS.radar)

    case 'PERFORMANCE_REPORT': {
      // metadata.reportId is exposed to the player/parent only (TimelineQueryService allowlist).
      const reportId = event.metadata?.reportId
      const report =
        reportId != null ? reports.find((r) => String(r.id) === String(reportId)) : null
      if (report?.signedDownloadUrl) return { href: report.signedDownloadUrl }
      return anchor(TIMELINE_ANCHORS.reports)
    }

    case 'HOMEWORK_ASSIGNED':
      if (familyViewer && playerId != null) {
        return { to: { name: 'player-locker-room', params: { playerId: String(playerId) } } }
      }
      return viewer === 'COACH' ? anchor(TIMELINE_ANCHORS.homework) : null

    default:
      return null
  }
}
