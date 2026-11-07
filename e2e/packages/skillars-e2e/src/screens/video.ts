import { Screen } from 'pitchside-core'
import type { Locator } from '@playwright/test'
import { R } from '../routes.js'

// L · Video: the uploader's page (player or coach) and the parent's child-videos page.

/** /player/videos and /coach/videos: the same page, role-aware. */
export class VideosScreen extends Screen {
  readonly route: string = R.playerVideos
  readonly readyTestId = 'video-review-notice'
  reviewNotice = () => this.tid('video-review-notice')
  uploadButton = () => this.tid('player-upload-btn')
  fileInput = () => this.tid('video-file-input')
  progress = () => this.tid('video-upload-progress')
  quotaStorage = () => this.tid('quota-storage')
  empty = () => this.tid('videos-empty')
  items = () => this.tid('video-list-item')
  item = (title: string) =>
    this.items().filter({
      has: this.page.getByTestId('video-title').getByText(title, { exact: true }),
    })
  status = (item: Locator) => this.within(item, 'video-status')
  play = (item: Locator) => this.within(item, 'video-play-btn')
  retry = (item: Locator) => this.within(item, 'video-retry-btn')
  delete = (item: Locator) => this.within(item, 'video-delete-btn')
  // The player's "From the coach" section.
  coachVideos = () => this.tid('coach-videos-section')
  coachVideosEmpty = () => this.tid('coach-videos-empty')
  // The coach's upload menu and the session picker for a review.
  coachUpload = () => this.tid('coach-upload-dropdown')
  coachUploadType = (type: 'coach-review' | 'drill-demo') => this.tid(`coach-upload-${type}`)
  noSubjectBookings = () => this.tid('no-subject-bookings')
  subjectBooking = () => this.tid('subject-booking-select')
  subjectConfirm = () => this.tid('subject-confirm-btn')
  // The player dialog.
  playerDialog = () => this.tid('video-play-dialog')
  playerError = () => this.tid('video-play-error')
  playerRetry = () => this.tid('video-play-retry')
  playerVideo = () => this.tid('video-play-element')
  deleteConfirm = () => this.tid('video-delete-confirm')

  /** Starts the player's homework upload with this file (the hidden input takes it directly). */
  async uploadHomework(file: { name: string; mimeType: string; buffer: Buffer }): Promise<void> {
    await this.uploadButton().click()
    await this.fileInput().setInputFiles(file)
  }

  /** The text of a Quasar notification, if one with this message is showing. */
  notification = (message: string) =>
    this.page.locator('.q-notification').filter({ hasText: message })
}

export class CoachVideosScreen extends VideosScreen {
  override readonly route: string = R.coachVideos
}

/** /parent/players/:playerId/videos: the child's own videos and the coach's videos about them. */
export class ParentChildVideosScreen extends Screen {
  readonly route = R.parentChildVideos
  readonly readyTestId = 'child-name'
  items = () => this.tid('video-list-item')
  empty = () => this.tid('child-videos-empty')
  coachVideos = () => this.tid('child-coach-videos-section')
  coachVideosEmpty = () => this.tid('child-coach-videos-empty')
  item = (title: string) =>
    this.items().filter({
      has: this.page.getByTestId('video-title').getByText(title, { exact: true }),
    })
  delete = (item: Locator) => this.within(item, 'video-delete-btn')
  deleteConfirm = () => this.tid('video-delete-confirm')
  play = (item: Locator) => this.within(item, 'video-play-btn')
  playerVideo = () => this.tid('video-play-element')
}
