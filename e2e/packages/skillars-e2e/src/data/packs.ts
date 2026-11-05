import { expect } from '@playwright/test'
import type { CoachAccount, ParentAccount, Provisioner } from './provisioner.js'

/** A family's pack as GET /payment/session-packs reports it (SessionPackPurchaseResponse). */
export interface PackView {
  purchaseId: string
  sessionCount: number
  remainingSessions: number
  expiresAt: string
  pausedFrom: string | null
  pausedUntil: string | null
  status: string
  revokedReason: string | null
}

/** The family's one pack with this coach, read through the family's own API session. */
export async function packOf(
  provision: Provisioner,
  parent: ParentAccount,
  coach: CoachAccount,
): Promise<PackView> {
  const session = await provision.login(parent)
  try {
    const packs = await session.api.json<PackView[]>(
      'GET',
      `/payment/session-packs?coachId=${coach.coachId}`,
    )
    expect(packs).toHaveLength(1)
    return packs[0]!
  } finally {
    await session.dispose()
  }
}
