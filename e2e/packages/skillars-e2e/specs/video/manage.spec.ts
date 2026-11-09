import { randomUUID } from 'node:crypto'
import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import type { Provisioner } from '../../src/data/provisioner.js'
import { t } from '../../src/i18n.js'
import {
  CoachVideosScreen,
  ParentChildVideosScreen,
  VideosScreen,
} from '../../src/screens/index.js'
import { stubBunnyCdn, stubBunnyTus } from '../../src/stubs/bunny.js'

// L · Video, part 2: the coach's review upload with its session picker, a shared review played by
// the family, delete and the quota it frees, retrying a failed upload, and the player dialog.
//
// "Shared" here means a passed scanner scan on the video (markVideoScanned), which is what the S-18
// gate asks for; the gate itself stays on, as in production.

const title = (label: string) => `${label}-${randomUUID().slice(0, 6)}.mp4`
const clip = (name = title('review')) => ({
  name,
  mimeType: 'video/mp4',
  buffer: Buffer.alloc(204800, 1),
})

/** An Instructor coach (a Scout's video quota is 0) and a family who completed a session with them. */
async function coachWithHeldSession(provision: Provisioner) {
  const coach = await provision.coach()
  await provision.saveCard(coach)
  await provision.subscribeCoach(coach, 'INSTRUCTOR')
  const parent = await provision.parent()
  await provision.saveCard(parent)
  const bookingId = await provision.completedSession(parent, coach)
  return { coach, parent, child: parent.children[0]!.playerId, bookingId }
}

test.describe('L · Coach review videos', () => {
  test(
    'a coach uploads a review linked to a held session, and cannot without one',
    scenario({
      id: 'VID-09',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@coach', '@parent', '@video', '@slow'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow()
      // No held session yet: the picker says so, and Confirm stays disabled.
      const newcomer = await provision.coach()
      await provision.saveCard(newcomer)
      await provision.subscribeCoach(newcomer, 'INSTRUCTOR')
      const fresh = await actor(asRole(newcomer))
      const empty = await new CoachVideosScreen(fresh.page, suite).open()
      await empty.coachUpload().click()
      await empty.coachUploadType('coach-review').click()
      await expect(empty.noSubjectBookings()).toContainText(t('video.subject.noSessions'))
      await expect(empty.subjectConfirm()).toBeDisabled()

      // With a completed session: pick it, then the file.
      const { coach, child, bookingId } = await coachWithHeldSession(provision)
      const self = await actor(asRole(coach))
      const tus = await stubBunnyTus(self.page)
      const videos = await new CoachVideosScreen(self.page, suite).open()
      await videos.coachUpload().click()
      await videos.coachUploadType('coach-review').click()
      await videos.subjectBooking().click()
      await self.page.getByRole('option').first().click()
      const file = clip()
      const chooser = self.page.waitForEvent('filechooser')
      await videos.subjectConfirm().click()
      await (await chooser).setFiles(file)

      await expect(videos.notification(t('video.management.uploadSuccess'))).toBeVisible()
      await expect(videos.item(file.name)).toBeVisible()
      expect(tus.uploads).toHaveLength(1)
      // The video is about the child of the chosen session.
      const video = await provision.latestVideo(coach.coachId!)
      const link = await provision.videoSubject(video.id)
      expect(link).toEqual({ bookingId, playerId: child })
    },
  )

  test(
    'a shared review appears under "From the coach" for the parent and the player, and plays',
    scenario({
      id: 'VID-10',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@coach', '@parent', '@player', '@video'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const child = parent.children[0]!.playerId
      const childBooking = await provision.confirmedBooking(parent, coach)
      const player = await provision.adultPlayer()
      await provision.saveCard(player)
      const playerBooking = await provision.confirmedBooking(player, coach, { weekday: 3 })
      const aboutChild = title('child-review')
      const aboutPlayer = title('player-review')
      for (const [name, bookingId, playerId] of [
        [aboutChild, childBooking, child],
        [aboutPlayer, playerBooking, player.playerId!],
      ] as const) {
        const id = await provision.seedVideo({
          ownerId: coach.coachId!,
          type: 'COACH_REVIEW',
          title: name,
          subject: { bookingId, playerId },
        })
        await provision.markVideoScanned(id)
      }

      // The parent sees the review about their child, and it plays through a signed URL.
      const parentSide = await actor(asRole(parent))
      const cdn = await stubBunnyCdn(parentSide.page)
      const childPage = await new ParentChildVideosScreen(parentSide.page, suite).open({
        playerId: child,
      })
      await expect(childPage.coachVideos()).toContainText(aboutChild)
      await childPage.play(childPage.coachVideos().getByTestId('video-list-item').first()).click()
      await expect(childPage.playerVideo()).toBeVisible()
      await expect.poll(() => cdn.length).toBeGreaterThan(0)
      expect(cdn[0]).toContain('token=')
      await expect(childPage.playerVideo()).toHaveAttribute('src', cdn[0]!)

      // The player sees the one about them, in their own "From the coach".
      const playerSide = await actor(asRole(player))
      const playerPage = await new VideosScreen(playerSide.page, suite).open()
      await expect(playerPage.coachVideos()).toContainText(aboutPlayer)
      await expect(playerPage.coachVideos()).not.toContainText(aboutChild)
    },
  )
})

test.describe('L · Managing videos', () => {
  test(
    "deleting a video removes it and frees its quota, from the uploader's page and the parent's",
    scenario({
      id: 'VID-11',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@player', '@parent', '@video'],
    }),
    async ({ actor, suite, provision }) => {
      // A player deletes their own.
      const player = await provision.adultPlayer()
      const mine = title('mine')
      await provision.seedVideo({ ownerId: player.playerId!, title: mine, storageBytes: 204800 })
      await provision.setVideoQuotaUsed(player.playerId!, 204800)
      const self = await actor(asRole(player))
      const videos = await new VideosScreen(self.page, suite).open()
      await expect(videos.quotaStorage()).toContainText('200.0 KB /')
      await videos.delete(videos.item(mine)).click()
      await videos.deleteConfirm().click()
      await expect(videos.item(mine)).toHaveCount(0)
      await self.page.reload()
      await expect(videos.empty()).toBeVisible()
      await expect(videos.quotaStorage()).toContainText('0 B /')

      // A parent deletes one of their child's from the child-videos page.
      const parent = await provision.parent()
      const child = parent.children[0]!.playerId
      const theirs = title('child-own')
      await provision.seedVideo({ ownerId: child, title: theirs })
      const parentSide = await actor(asRole(parent))
      const childPage = await new ParentChildVideosScreen(parentSide.page, suite).open({
        playerId: child,
      })
      await childPage.delete(childPage.item(theirs)).click()
      await childPage.deleteConfirm().click()
      await expect(childPage.item(theirs)).toHaveCount(0)
      await expect(childPage.empty()).toBeVisible()
    },
  )

  test(
    'a failed upload offers Retry, which re-sends the file; a concurrent retry is refused',
    scenario({
      id: 'VID-12',
      priority: 'P2',
      type: 'edge',
      tags: ['@regression', '@player', '@video'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow() // the retry claims the video and re-creates it at Bunny before answering
      const player = await provision.adultPlayer()
      const failed = title('failed')
      const videoId = await provision.seedVideo({
        ownerId: player.playerId!,
        title: failed,
        state: 'FAILED',
      })
      const self = await actor(asRole(player))
      const tus = await stubBunnyTus(self.page)
      const videos = await new VideosScreen(self.page, suite).open()
      const item = videos.item(failed)
      await expect(videos.status(item)).toHaveText(t('video.status.FAILED'))

      // Another tab got there first: the server answers 409 for this one.
      await self.page.route('**/api/video/*/retry', (route) =>
        route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({ errorMsg: { errorKey: 'VIDEO_STATE_CONFLICT' } }),
        }),
      )
      await videos.retry(item).click()
      await videos.fileInput().setInputFiles(clip(failed))
      await expect(videos.notification(t('video.management.retryConflict'))).toBeVisible()
      expect(tus.uploads).toHaveLength(0)
      await self.page.unroute('**/api/video/*/retry')

      // A real retry: the server takes it back to uploading and the bytes are sent again.
      await videos.retry(videos.item(failed)).click()
      await videos.fileInput().setInputFiles(clip(failed))
      await expect(videos.notification(t('video.management.uploadSuccess'))).toBeVisible({
        timeout: 30_000,
      })
      expect(tus.uploads).toHaveLength(1)
      await expect.poll(() => provision.videoStateNow(videoId)).not.toBe('FAILED')
    },
  )

  test(
    'only a ready video offers Play, and the player shows an error it can retry',
    scenario({
      id: 'VID-13',
      priority: 'P2',
      type: 'edge',
      tags: ['@regression', '@player', '@video'],
    }),
    async ({ actor, suite, provision }) => {
      const player = await provision.adultPlayer()
      const ready = title('ready')
      const failed = title('failed')
      await provision.seedVideo({ ownerId: player.playerId!, title: ready })
      await provision.seedVideo({ ownerId: player.playerId!, title: failed, state: 'FAILED' })
      const self = await actor(asRole(player))
      await stubBunnyCdn(self.page)
      const videos = await new VideosScreen(self.page, suite).open()
      await expect(videos.play(videos.item(ready))).toBeVisible()
      await expect(videos.play(videos.item(failed))).toHaveCount(0)

      // The video itself fails to load, twice (the dialog refetches the URL once on its own): it
      // says so and offers Retry, which then plays.
      let failures = 2
      await self.page.unroute('https://bunny-cdn.e2e.test/**')
      const cdn = await stubBunnyCdn(self.page, () => failures-- > 0)
      await videos.play(videos.item(ready)).click()
      await expect(videos.playerError()).toBeVisible()
      await videos.playerRetry().click()
      await expect(videos.playerVideo()).toBeVisible()
      // Two failed fetches, then the retry's: recorded as the request goes out, which can trail the element.
      await expect.poll(() => cdn.length).toBeGreaterThanOrEqual(3)
      await expect(videos.playerError()).toHaveCount(0)
    },
  )
})
