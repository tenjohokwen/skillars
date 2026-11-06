import { randomBytes } from 'node:crypto'
import type { Provisioner } from './provisioner.js'

// Messaging threads for suite K. A thread needs a booking: CONFIRMED for a child; for an adult player
// (age verification is off on the stack) a COMPLETED one.

/**
 * A token no other test's message contains, to find this test's text on screen and in WireMock. Letters
 * only: a run of digits reads as a phone number, and a coach's message to a minor would be held (S-04).
 */
export const token = () =>
  `x${[...randomBytes(10)].map((b) => String.fromCharCode(97 + (b % 26))).join('')}`

/** A coach and a self-registered adult player who completed a session together, and their thread. */
export async function adultThread(provision: Provisioner) {
  const coach = await provision.coach()
  const player = await provision.adultPlayer()
  await provision.saveCard(player)
  const bookingId = await provision.confirmedBooking(player, coach)
  await provision.setBooking(bookingId, { startMinutesFromNow: -3 * 60, status: 'COMPLETED' })
  const conversationId = await provision.conversation(coach, coach, player.playerId!)
  return { coach, player, conversationId }
}

/** A coach and a parent whose child of `age` has a confirmed booking with them, and their thread. */
export async function childThread(provision: Provisioner, age: number) {
  const coach = await provision.coach()
  const parent = await provision.parent({ childAges: [age] })
  await provision.saveCard(parent)
  await provision.confirmedBooking(parent, coach)
  const child = parent.children[0]!
  const conversationId = await provision.conversation(coach, coach, child.playerId)
  return { coach, parent, child, conversationId }
}
