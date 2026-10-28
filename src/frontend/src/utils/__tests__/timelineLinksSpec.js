// UI-gap chunk C7 / A16 — skillars-epics Story 5.6: each timeline event links to its detail where
// the viewing role has a destination; everything else renders no link.

import { describe, it, expect } from 'vitest'
import { resolveTimelineLink, TIMELINE_ANCHORS } from 'src/utils/timelineLinks'

const ALL_ANCHORS = Object.values(TIMELINE_ANCHORS)
const PLAYER_ID = '893573203704173564'

describe('resolveTimelineLink', () => {
  it('renders nothing without a viewer role or event', () => {
    expect(resolveTimelineLink({ eventType: 'SESSION_COMPLETED' }, {})).toBeNull()
    expect(resolveTimelineLink(null, { viewer: 'PARENT' })).toBeNull()
  })

  it('links a completed session to the bookings list for parents and players, never for coaches', () => {
    const event = { eventType: 'SESSION_COMPLETED' }
    expect(resolveTimelineLink(event, { viewer: 'PARENT' })).toEqual({
      to: { name: 'parent-bookings' },
    })
    expect(resolveTimelineLink(event, { viewer: 'PLAYER' })).toEqual({
      to: { name: 'parent-bookings' },
    })
    expect(resolveTimelineLink(event, { viewer: 'COACH', anchors: ALL_ANCHORS })).toBeNull()
  })

  it('scrolls a radar assessment to the radar card only when the page has one', () => {
    const event = { eventType: 'RADAR_ASSESSMENT' }
    expect(resolveTimelineLink(event, { viewer: 'COACH', anchors: ALL_ANCHORS })).toEqual({
      anchor: TIMELINE_ANCHORS.radar,
    })
    expect(resolveTimelineLink(event, { viewer: 'COACH', anchors: [] })).toBeNull()
  })

  it("opens a report's signed PDF when it is loaded, else falls back to the reports card", () => {
    const event = { eventType: 'PERFORMANCE_REPORT', metadata: { reportId: 42 } }
    const reports = [{ id: '42', signedDownloadUrl: 'https://signed/pdf' }]
    expect(resolveTimelineLink(event, { viewer: 'PARENT', anchors: ALL_ANCHORS, reports })).toEqual(
      { href: 'https://signed/pdf' },
    )
    expect(
      resolveTimelineLink(event, { viewer: 'PARENT', anchors: ALL_ANCHORS, reports: [] }),
    ).toEqual({ anchor: TIMELINE_ANCHORS.reports })
    // Coaches never get metadata.reportId (backend allowlist) — anchor only.
    expect(
      resolveTimelineLink(
        { eventType: 'PERFORMANCE_REPORT', metadata: {} },
        { viewer: 'COACH', anchors: ALL_ANCHORS, reports },
      ),
    ).toEqual({ anchor: TIMELINE_ANCHORS.reports })
  })

  it("links homework to the player's locker room (exact id) or the coach's homework card", () => {
    const event = { eventType: 'HOMEWORK_ASSIGNED' }
    expect(resolveTimelineLink(event, { viewer: 'PARENT', playerId: PLAYER_ID })).toEqual({
      to: { name: 'player-locker-room', params: { playerId: PLAYER_ID } },
    })
    expect(resolveTimelineLink(event, { viewer: 'COACH', anchors: ALL_ANCHORS })).toEqual({
      anchor: TIMELINE_ANCHORS.homework,
    })
    expect(resolveTimelineLink(event, { viewer: 'COACH', anchors: [] })).toBeNull()
  })

  it('renders nothing for unknown event types', () => {
    expect(
      resolveTimelineLink(
        { eventType: 'SOMETHING_NEW' },
        { viewer: 'PARENT', anchors: ALL_ANCHORS },
      ),
    ).toBeNull()
  })
})
