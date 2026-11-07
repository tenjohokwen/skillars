import { randomUUID } from 'node:crypto'
import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { t } from '../../src/i18n.js'
import { R } from '../../src/routes.js'
import {
  CoachVideosScreen,
  ParentChildVideosScreen,
  ParentDashboardScreen,
  VideosScreen,
} from '../../src/screens/index.js'
import { stubBunnyTus } from '../../src/stubs/bunny.js'

// L · Video, part 1: a player's homework upload, the quota, the checks before upload, the minor
// refusal, and who sees a coach's review video while the scanner gate is on.
//
// Uploads go the product's way: the app creates the video at Bunny (WireMock), the browser sends
// the bytes to Bunny's TUS endpoint (answered in the page, src/stubs/bunny.ts), and Bunny's
// webhooks, signed as Bunny signs them, move it to READY.

/** The fixture's size, and what the Bunny GET stub reports as its storage size. */
const CLIP_BYTES = 204800

function clip(name = `kick-${randomUUID().slice(0, 6)}.mp4`, bytes = CLIP_BYTES) {
  return { name, mimeType: 'video/mp4', buffer: Buffer.alloc(bytes, 1) }
}

/** As QuotaUsageBar prints a size. */
function size(bytes: number): string {
  if (bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(1024))
  return `${(bytes / Math.pow(1024, i)).toFixed(1)} ${units[i]}`
}

const ATHLETE_STORAGE = 2 * 1024 * 1024 * 1024

test.describe('L · Homework upload', () => {
  test(
    'an adult player uploads a homework clip and it goes from uploading to ready',
    scenario({
      id: 'VID-01',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@player', '@video', '@slow'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow() // webhook processing runs on a 5 s schedule
      const player = await provision.adultPlayer()
      const self = await actor(asRole(player))
      const tus = await stubBunnyTus(self.page)
      const videos = await new VideosScreen(self.page, suite).open()
      await expect(videos.empty()).toBeVisible()

      const file = clip()
      await videos.uploadHomework(file)
      await expect(videos.notification(t('video.management.uploadSuccess'))).toBeVisible()
      const item = videos.item(file.name)
      await expect(item).toBeVisible()

      // The bytes went to Bunny with the credentials the app issued for this video.
      expect(tus.uploads).toHaveLength(1)
      expect(tus.uploads[0]!.bytes).toBe(CLIP_BYTES)
      const video = await provision.latestVideo(player.playerId!)
      expect(tus.uploads[0]!.headers.VideoId).toBe(video.guid)
      expect(tus.uploads[0]!.headers.AuthorizationSignature).not.toBe('')

      // Bunny reports the upload, then the encoding: the video becomes playable.
      await provision.bunnyWebhook(video.guid!, 7)
      await provision.videoState(video.id, 'TRANSCODING')
      await provision.bunnyWebhook(video.guid!, 3)
      await provision.videoState(video.id, 'READY')
      await self.page.reload()
      await expect(videos.status(videos.item(file.name))).toHaveText(t('video.status.READY'))
      await expect(videos.play(videos.item(file.name))).toBeVisible()
    },
  )

  test(
    'the progress bar shows while the bytes are in flight and goes when they land',
    scenario({
      id: 'VID-02',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@player', '@video'],
    }),
    async ({ actor, suite, provision }) => {
      const player = await provision.adultPlayer()
      const self = await actor(asRole(player))
      const tus = await stubBunnyTus(self.page, { holdPatches: true })
      const videos = await new VideosScreen(self.page, suite).open()

      await videos.uploadHomework(clip())
      await expect(videos.progress()).toBeVisible()
      await expect(videos.notification(t('video.management.uploadSuccess'))).toHaveCount(0)

      tus.release()
      await expect(videos.notification(t('video.management.uploadSuccess'))).toBeVisible()
      await expect(videos.progress()).toHaveCount(0)
    },
  )

  test(
    'the quota bar shows the storage used, before and after an upload',
    scenario({
      id: 'VID-03',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@player', '@video', '@slow'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow()
      const player = await provision.adultPlayer()
      const used = 100 * 1024 * 1024
      await provision.setVideoQuotaUsed(player.playerId!, used)
      const self = await actor(asRole(player))
      await stubBunnyTus(self.page)
      const videos = await new VideosScreen(self.page, suite).open()
      await expect(videos.quotaStorage()).toHaveText(`${size(used)} / ${size(ATHLETE_STORAGE)}`)

      await videos.uploadHomework(clip())
      await expect(videos.notification(t('video.management.uploadSuccess'))).toBeVisible()
      const video = await provision.latestVideo(player.playerId!)
      await provision.bunnyWebhook(video.guid!, 7)
      await provision.videoState(video.id, 'TRANSCODING')
      await provision.bunnyWebhook(video.guid!, 3)
      await provision.videoState(video.id, 'READY')

      // The clip counts once it is ready.
      await self.page.reload()
      await expect(videos.quotaStorage()).toHaveText(
        `${size(used + CLIP_BYTES)} / ${size(ATHLETE_STORAGE)}`,
      )
    },
  )

  test(
    'an upload that would exceed the quota is refused, and says why',
    scenario({
      id: 'VID-04',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@player', '@video'],
    }),
    async ({ actor, suite, provision }) => {
      const player = await provision.adultPlayer()
      await provision.setVideoQuotaUsed(player.playerId!, ATHLETE_STORAGE - CLIP_BYTES / 2)
      const self = await actor(asRole(player))
      const tus = await stubBunnyTus(self.page)
      const videos = await new VideosScreen(self.page, suite).open()

      await videos.uploadHomework(clip())
      await expect(videos.notification(t('video.management.quotaExceeded'))).toBeVisible()
      expect(tus.uploads).toHaveLength(0)
      await expect(videos.items()).toHaveCount(0)
    },
  )

  test(
    'a file type the server does not accept is refused before anything is sent',
    scenario({
      id: 'VID-05',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@player', '@video'],
    }),
    async ({ actor, suite, provision }) => {
      const player = await provision.adultPlayer()
      const self = await actor(asRole(player))
      const tus = await stubBunnyTus(self.page)
      const videos = await new VideosScreen(self.page, suite).open()
      const initiated: string[] = []
      self.page.on('request', (r) => {
        if (r.url().includes('/uploads/initiate')) initiated.push(r.url())
      })

      await videos.uploadHomework({
        name: 'notes.pdf',
        mimeType: 'application/pdf',
        buffer: Buffer.alloc(2048, 1),
      })
      await expect(videos.notification(t('video.management.fileTypeNotAllowed'))).toBeVisible()
      expect(initiated).toHaveLength(0)
      expect(tus.uploads).toHaveLength(0)
    },
  )

  test(
    "a file over the server's size limit is refused before anything is sent, naming the limit",
    scenario({
      id: 'VID-05',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@player', '@video', '@config'],
    }),
    async ({ actor, suite, provision, runtimeConfig }) => {
      // The page reads the limit from the server: lower it, rather than build a 250 MB file.
      await runtimeConfig.set('video.homework.maxSizeBytes', 100 * 1024)
      const player = await provision.adultPlayer()
      const self = await actor(asRole(player))
      const tus = await stubBunnyTus(self.page)
      const videos = await new VideosScreen(self.page, suite).open()
      const initiated: string[] = []
      self.page.on('request', (r) => {
        if (r.url().includes('/uploads/initiate')) initiated.push(r.url())
      })

      await videos.uploadHomework(clip())
      await expect(
        videos.notification(t('video.management.fileTooLarge', { size: '100 KB' })),
      ).toBeVisible()
      expect(initiated).toHaveLength(0)
      expect(tus.uploads).toHaveLength(0)
    },
  )
})

test.describe('L · Who may upload', () => {
  test(
    "a minor's upload is refused",
    scenario({
      id: 'VID-06',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@player', '@video'],
    }),
    async ({ actor, suite, provision }) => {
      const player = await provision.adultPlayer()
      // Minors can't register themselves: age an adult's profile down instead.
      await provision.setDateOfBirth(player.playerId!, 15)
      const self = await actor(asRole(player))
      const tus = await stubBunnyTus(self.page)
      const videos = await new VideosScreen(self.page, suite).open()

      await videos.uploadHomework(clip())
      await expect(videos.notification(t('video.management.minorUploadRefused'))).toBeVisible()
      expect(tus.uploads).toHaveLength(0)
      await expect(videos.items()).toHaveCount(0)
    },
  )

  test(
    'the parental approval queue is gone: no tile, and its old URL goes to the dashboard',
    scenario({
      id: 'VID-07',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@parent', '@video'],
    }),
    async ({ actor, suite, provision }) => {
      const parent = await provision.parent()
      const self = await actor(asRole(parent))
      const dashboard = await new ParentDashboardScreen(self.page, suite).open()
      await expect(self.page.locator('a[href*="/parent/approvals"]')).toHaveCount(0)

      await self.page.goto(suite.routing.toUrl(R.retiredApprovals))
      await dashboard.waitReady()
      expect(dashboard.currentRoute()).toBe(R.parentDashboard)
    },
  )
})

test.describe('L · The scanner gate', () => {
  test(
    'an unscanned coach review is seen only by the coach, until a scan passes',
    scenario({
      id: 'VID-08',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@coach', '@parent', '@player', '@video'],
    }),
    async ({ actor, suite, provision }) => {
      // Owner decision 2026-11-07 (option A): hidden from the family, not listed as unplayable.
      const coach = await provision.coach()
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const child = parent.children[0]!.playerId
      const childBooking = await provision.confirmedBooking(parent, coach)
      const player = await provision.adultPlayer()
      await provision.saveCard(player)
      const playerBooking = await provision.confirmedBooking(player, coach, { weekday: 3 })

      const aboutChild = `review-${randomUUID().slice(0, 6)}.mp4`
      const aboutPlayer = `review-${randomUUID().slice(0, 6)}.mp4`
      const childVideo = await provision.seedVideo({
        ownerId: coach.coachId!,
        type: 'COACH_REVIEW',
        title: aboutChild,
        subject: { bookingId: childBooking, playerId: child },
      })
      const playerVideo = await provision.seedVideo({
        ownerId: coach.coachId!,
        type: 'COACH_REVIEW',
        title: aboutPlayer,
        subject: { bookingId: playerBooking, playerId: player.playerId! },
      })

      // The coach sees both, with the review notice.
      const coachSide = await actor(asRole(coach))
      const own = await new CoachVideosScreen(coachSide.page, suite).open()
      await expect(own.reviewNotice()).toContainText(t('video.management.reviewNotice'))
      await expect(own.item(aboutChild)).toBeVisible()
      await expect(own.item(aboutPlayer)).toBeVisible()

      // The parent and the player see nothing yet.
      const parentSide = await actor(asRole(parent))
      const childPage = await new ParentChildVideosScreen(parentSide.page, suite).open({
        playerId: child,
      })
      await expect(childPage.coachVideosEmpty()).toBeVisible()
      const playerSide = await actor(asRole(player))
      const playerPage = await new VideosScreen(playerSide.page, suite).open()
      await expect(playerPage.coachVideosEmpty()).toBeVisible()

      // Once a scan passes, each sees the review about them, and only that one.
      await provision.markVideoScanned(childVideo)
      await provision.markVideoScanned(playerVideo)
      await parentSide.page.reload()
      await expect(childPage.coachVideos()).toContainText(aboutChild)
      await expect(childPage.coachVideos()).not.toContainText(aboutPlayer)
      await playerSide.page.reload()
      await expect(playerPage.coachVideos()).toContainText(aboutPlayer)
      await expect(playerPage.coachVideos()).not.toContainText(aboutChild)
    },
  )
})
