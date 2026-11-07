import { Screen } from 'pitchside-core'
import type { Locator } from '@playwright/test'
import { R } from '../routes.js'

// J · Development, part 2: the locker room (homework by assigning coach), open to the player and
// to their parent, and the parent's child hub that links to it.

export class LockerRoomScreen extends Screen {
  readonly route = R.lockerRoom
  readonly readyTestId = 'locker-room'
  empty = () => this.tid('locker-room-empty')
  error = () => this.tid('locker-room-error')
  groups = () => this.tid('locker-room-group')
  groupHeaders = () => this.tid('locker-room-group-header')
  assignments = () => this.tid('locker-room-assignment')
  assignment = (drillName: string) =>
    this.assignments().filter({ has: this.page.getByText(drillName, { exact: true }) })
  done = (assignment: Locator) => this.within(assignment, 'homework-done')
}

export class ChildHubScreen extends Screen {
  readonly route = R.childHub
  readonly readyTestId = 'child-hub-header'
  lockerRoom = () => this.tid('child-locker-room-link')
}
