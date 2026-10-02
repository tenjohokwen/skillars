// skillars-deferred-139 AC2/AC4/AC5/AC6 — ProfilePage.vue role-gated sections.
//
// The page's two pre-existing cards (Account, Personal Info) are role-agnostic and always render.
// This story adds three NEW role-gated sections — Coach Profile (AC2), Player Profile (AC4), My
// Children's Profiles (AC5) — each gated on exactly one authStore role flag. AC6 is the explicit
// assertion that an Admin renders none of the three new sections (no new code path for Admin; this
// pins the absence as a deliberate scope decision, not an oversight to be "fixed" later).

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

vi.mock('src/api/profile.api', () => ({
  profileApi: { getProfile: vi.fn() },
}))
vi.mock('src/api/marketplace.api', () => ({
  getOwnCoachProfile: vi.fn(),
}))
vi.mock('src/api/playerRegistration.api', () => ({
  playerRegistrationApi: { getMyProfile: vi.fn() },
}))

import { profileApi } from 'src/api/profile.api'
import { getOwnCoachProfile } from 'src/api/marketplace.api'
import { playerRegistrationApi } from 'src/api/playerRegistration.api'
import ProfilePage from 'src/pages/ProfilePage.vue'
import EditPlayerPositionDialog from 'src/components/profile/EditPlayerPositionDialog.vue'

const BASE_ACCOUNT_PROFILE = { email: 'user@example.com', otpEnabled: false }

const mounted = []
function mountPage(role, initialState = {}) {
  profileApi.getProfile.mockResolvedValue(BASE_ACCOUNT_PROFILE)
  getOwnCoachProfile.mockResolvedValue({ displayName: 'Coach Name', specialties: [] })
  playerRegistrationApi.getMyProfile.mockResolvedValue({ position: 'DEFENDER' })

  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: {
      auth: { userId: '1', role },
      player: { players: [], ...initialState.player },
    },
  })
  const wrapper = mount(ProfilePage, {
    global: {
      plugins: [pinia],
      stubs: {
        UpdateEmailDialog: true,
        UpdatePasswordDialog: true,
        UpdatePhoneDialog: true,
        UpdateAddressDialog: true,
        UpdateInfoDialog: true,
        Toggle2faDialog: true,
        EditCoachIdentityDialog: true,
        EditCoachSpecialtiesDialog: true,
        EditCoachPricingDialog: true,
        EditCoachAvailabilityDialog: true,
        EditCoachPhotoDialog: true,
        EditPlayerPositionDialog: true,
      },
    },
  })
  mounted.push(wrapper)
  return wrapper
}

describe('ProfilePage.vue — role-gated sections (deferred-139 AC2/AC4/AC5/AC6)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('a Coach sees the Coach Profile section and none of the Player/Parent sections', async () => {
    const wrapper = mountPage('COACH')
    await flushPromises()

    expect(wrapper.text()).toContain('profile.sectionCoachProfile')
    expect(wrapper.text()).not.toContain('profile.sectionPlayerProfile')
    expect(wrapper.text()).not.toContain('profile.sectionMyChildren')
    expect(getOwnCoachProfile).toHaveBeenCalledTimes(1)
  })

  it('a Player sees the Player Profile section and none of the Coach/Parent sections', async () => {
    const wrapper = mountPage('PLAYER')
    await flushPromises()

    expect(wrapper.text()).toContain('profile.sectionPlayerProfile')
    expect(wrapper.text()).not.toContain('profile.sectionCoachProfile')
    expect(wrapper.text()).not.toContain('profile.sectionMyChildren')
    expect(playerRegistrationApi.getMyProfile).toHaveBeenCalledTimes(1)
    expect(getOwnCoachProfile).not.toHaveBeenCalled()
  })

  it("a Parent sees the My Children's Profiles section and none of the Coach/Player sections", async () => {
    const wrapper = mountPage('PARENT', {
      player: { players: [{ id: 5, name: 'Kid', position: 'FORWARD' }] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('profile.sectionMyChildren')
    expect(wrapper.text()).toContain('Kid')
    expect(wrapper.text()).not.toContain('profile.sectionCoachProfile')
    expect(wrapper.text()).not.toContain('profile.sectionPlayerProfile')
    expect(getOwnCoachProfile).not.toHaveBeenCalled()
    expect(playerRegistrationApi.getMyProfile).not.toHaveBeenCalled()
  })

  it('AC6 — an Admin renders exactly the two pre-existing cards and none of the three new sections', async () => {
    const wrapper = mountPage('ADMIN')
    await flushPromises()

    expect(wrapper.text()).toContain('profile.sectionAccount')
    expect(wrapper.text()).toContain('profile.sectionPersonal')
    expect(wrapper.text()).not.toContain('profile.sectionCoachProfile')
    expect(wrapper.text()).not.toContain('profile.sectionPlayerProfile')
    expect(wrapper.text()).not.toContain('profile.sectionMyChildren')
    expect(getOwnCoachProfile).not.toHaveBeenCalled()
    expect(playerRegistrationApi.getMyProfile).not.toHaveBeenCalled()
  })

  it('a failed coach-profile fetch does not block the two pre-existing cards from rendering', async () => {
    getOwnCoachProfile.mockRejectedValue(new Error('403'))
    const wrapper = mountPage('COACH')
    await flushPromises()

    expect(wrapper.text()).toContain('profile.sectionAccount')
    expect(wrapper.text()).toContain('profile.sectionPersonal')
  })

  // skillars-deferred-139 review Patch 1: a swallowed coach-profile fetch failure must not leave
  // the edit dialogs reachable with a blank prefill (which would silently wipe untouched optional
  // fields on save) — the coach section shows a retry-able error instead of any edit button.
  it('Patch 1 — a failed coach-profile fetch hides every coach edit button and shows a retry action', async () => {
    // mountPage() itself stubs a resolved getOwnCoachProfile — override AFTER mounting (still
    // synchronous, before the component's own await chain reaches this call) so this test's
    // rejection, not mountPage's default, is what the component actually observes.
    const wrapper = mountPage('COACH')
    getOwnCoachProfile.mockRejectedValue(new Error('500'))
    await flushPromises()

    const coachSection = wrapper
      .findAll('.profile-section')
      .find((s) => s.text().includes('profile.sectionCoachProfile'))
    expect(coachSection.text()).toContain('profile.coachProfileLoadError')
    expect(coachSection.text()).not.toContain('profile.coachIdentity')
    expect(coachSection.findAll('.edit-btn')).toHaveLength(0)
  })

  // skillars-deferred-139 review D4, corrected by the review audit (item 1). The first version of
  // this fix replaced the whole section with a bare note, so a suspended coach could not even SEE
  // their own display name/specialties/pricing — and this test asserted that by requiring
  // 'profile.coachIdentity' to be ABSENT. "Read-only" means the data still renders and only the
  // edit affordances drop out, so the row labels must now be PRESENT alongside the note.
  it('D4 — a suspended coach still sees their profile data, with the note and no edit buttons', async () => {
    const wrapper = mountPage('COACH')
    getOwnCoachProfile.mockResolvedValue({
      status: 'SUSPENDED',
      displayName: 'Coach Name',
      specialties: [],
    })
    await flushPromises()

    const coachSection = wrapper
      .findAll('.profile-section')
      .find((s) => s.text().includes('profile.sectionCoachProfile'))
    expect(coachSection.text()).toContain('profile.coachSuspendedNote')
    // read-only, not hidden: every row label and the coach's own values still render
    expect(coachSection.text()).toContain('profile.coachIdentity')
    expect(coachSection.text()).toContain('profile.coachSpecialties')
    expect(coachSection.text()).toContain('profile.coachPricing')
    expect(coachSection.text()).toContain('profile.coachAvailability')
    expect(coachSection.text()).toContain('profile.coachPhoto')
    expect(coachSection.text()).toContain('Coach Name')
    // but no way to change any of it
    expect(coachSection.findAll('.edit-btn')).toHaveLength(0)
  })

  // Review audit item 1 regression guard: an ACTIVE coach must keep all five edit buttons, so the
  // suspension gate above can never silently disable editing for everyone.
  it('an active coach keeps all five Coach Profile edit buttons', async () => {
    const wrapper = mountPage('COACH')
    getOwnCoachProfile.mockResolvedValue({
      status: 'ACTIVE',
      displayName: 'Coach Name',
      specialties: [],
    })
    await flushPromises()

    const coachSection = wrapper
      .findAll('.profile-section')
      .find((s) => s.text().includes('profile.sectionCoachProfile'))
    expect(coachSection.text()).not.toContain('profile.coachSuspendedNote')
    expect(coachSection.findAll('.edit-btn')).toHaveLength(5)
  })

  // skillars-deferred-139 review D3
  it('D3 — a player with no PlayerProfile row gets a complete-your-profile CTA, not a dead Edit button', async () => {
    const wrapper = mountPage('PLAYER')
    playerRegistrationApi.getMyProfile.mockRejectedValue(new Error('404'))
    await flushPromises()

    expect(wrapper.text()).toContain('profile.noPlayerProfile')
    expect(wrapper.text()).toContain('profile.completePlayerProfile')
    expect(wrapper.text()).not.toContain('auth.player.position')
  })

  // skillars-deferred-139 review Patch 8: AC5's test plan required pinning that each child row's
  // edit button routes to the shared dialog with THAT child's own id, not pinned anywhere before
  // this fix (the previous test only ever had one child, so a routing bug couldn't have shown up).
  it("Patch 8 — AC5 each child row's Edit button opens the shared dialog with that child's own player-id", async () => {
    const wrapper = mountPage('PARENT', {
      player: {
        players: [
          { id: 5, name: 'Kid A', position: 'FORWARD' },
          { id: 9, name: 'Kid B', position: 'DEFENDER' },
        ],
      },
    })
    await flushPromises()

    const rows = wrapper.findAll('.profile-row')
    const kidBRow = rows.find((r) => r.text().includes('Kid B'))
    await kidBRow.find('.edit-btn').trigger('click')

    const childDialogs = wrapper.findAllComponents(EditPlayerPositionDialog)
    // index 0 is the player's-own-position dialog (AC4, no player-id prop); index 1 is the
    // parent's shared children dialog (AC5).
    expect(childDialogs[1].props('modelValue')).toBe(true)
    expect(childDialogs[1].props('playerId')).toBe(9)
  })
})
