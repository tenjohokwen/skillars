import type { Page } from '@playwright/test'
import type { SuiteEnv } from 'pitchside-core'
import { interpolate } from 'pitchside-core'
import { R } from '../routes.js'
import type { CoachAccount, ParentAccount, PlayerAccount, Provisioner } from './provisioner.js'

// Suite X: the P0 screens a cross-cutting check sweeps, per persona, with the element that says each one has
// loaded its data. One family (a parent with a pack and a confirmed booking with one coach), one adult player
// and the bootstrap admin give every screen something to show.

export type Persona = 'guest' | 'parent' | 'coach' | 'player' | 'admin'

export interface Stop {
  persona: Persona
  name: string
  route: string
  /** A data-testid that appears once the screen has loaded; omitted when the page has no data-bound root. */
  ready?: string
}

export interface SweepWorld {
  coach: CoachAccount
  parent: ParentAccount
  player: PlayerAccount
  stops: Stop[]
}

/** Provision the people the sweep logs in as, and resolve every stop's route. */
export async function sweepWorld(provision: Provisioner): Promise<SweepWorld> {
  const coach = await provision.coach({ city: provision.uniqueCity() })
  const parent = await provision.parent()
  await provision.saveCard(parent)
  await provision.buyPack(parent, coach)
  await provision.confirmedBooking(parent, coach)
  const player = await provision.adultPlayer()
  const child = parent.children[0]!.playerId
  const stops: Stop[] = [
    { persona: 'guest', name: 'login', route: R.login, ready: 'login-email-input' },
    {
      persona: 'guest',
      name: 'marketplace',
      route: R.marketplace,
      ready: 'marketplace-city-input',
    },
    {
      persona: 'guest',
      name: 'coach profile',
      route: interpolate(R.coachProfile, { coachId: coach.coachId! }),
      ready: 'coach-primary-cta',
    },
    { persona: 'parent', name: 'parent dashboard', route: R.parentDashboard, ready: 'child-card' },
    {
      persona: 'parent',
      name: 'parent bookings',
      route: R.parentBookings,
      ready: 'parent-booking',
    },
    {
      persona: 'parent',
      name: 'child packs',
      route: interpolate(R.childPacks, { playerId: child }),
      ready: 'pack-card',
    },
    {
      persona: 'parent',
      name: 'child hub',
      route: interpolate(R.childHub, { playerId: child }),
      ready: 'child-hub-header',
    },
    { persona: 'parent', name: 'credit wallet', route: R.wallet, ready: 'wallet-balance' },
    {
      persona: 'parent',
      name: 'notifications',
      route: R.notifications,
      ready: 'notifications-page',
    },
    { persona: 'parent', name: 'profile', route: R.profile, ready: 'profile-privacy' },
    {
      persona: 'coach',
      name: 'command centre',
      route: R.commandCentre,
      ready: 'booking-requests-link',
    },
    { persona: 'coach', name: 'booking requests', route: R.bookingRequests },
    {
      persona: 'coach',
      name: 'availability',
      route: R.availability,
      ready: 'availability-window-save',
    },
    { persona: 'coach', name: 'revenue', route: R.revenue, ready: 'revenue-summary' },
    { persona: 'coach', name: 'reliability', route: R.reliability, ready: 'reliability-page' },
    { persona: 'coach', name: 'my players', route: R.coachPlayers, ready: 'my-players-page' },
    {
      persona: 'player',
      name: 'player dashboard',
      route: R.playerDashboard,
      ready: 'player-dashboard',
    },
    { persona: 'player', name: 'player videos', route: R.playerVideos },
    { persona: 'player', name: 'messages', route: R.messaging, ready: 'messaging-page' },
    { persona: 'admin', name: 'health dashboard', route: R.adminHealth, ready: 'health-status' },
    { persona: 'admin', name: 'admin users', route: R.adminUsers, ready: 'admin-users-page' },
    {
      persona: 'admin',
      name: 'moderation',
      route: R.adminModeration,
      ready: 'admin-moderation-page',
    },
  ]
  return { coach, parent, player, stops }
}

/**
 * Open a stop and wait until it has loaded. A hash change inside an already-loaded app does not reliably
 * re-render, so each stop is a full load of its URL; then its ready element, or the page body.
 */
export async function visit(page: Page, suite: SuiteEnv, stop: Stop): Promise<void> {
  await page.goto(suite.routing.toUrl(stop.route))
  await page.reload()
  if (stop.ready) {
    await page.getByTestId(stop.ready).first().waitFor({ state: 'visible', timeout: 30_000 })
  } else {
    await page.locator('.q-page').first().waitFor({ state: 'visible', timeout: 30_000 })
  }
}
