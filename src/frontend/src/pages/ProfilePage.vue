<template>
  <q-page>
    <div class="app-page fade-in">
      <!-- Page header -->
      <div class="page-header q-mb-xl">
        <div class="text-page-title">{{ t('profile.title') }}</div>
        <div class="text-meta">{{ $t('profile.subtitle') }}</div>
      </div>

      <!-- Loading -->
      <div v-if="isLoading" class="flex flex-center q-py-xl">
        <q-spinner-dots size="40px" style="color: var(--accent-primary)" />
      </div>

      <!-- Error -->
      <div v-else-if="hasError && !profile" class="glass-card--static q-pa-lg">
        <div class="text-meta" style="color: var(--accent-danger)">
          {{ errorMessage }}
          <template v-if="helpCode">
            <br /><small>{{ t('error.helpCode') }}: {{ helpCode }}</small>
          </template>
        </div>
      </div>

      <!-- Profile sections -->
      <template v-else-if="profile">
        <div class="profile-grid">
          <div class="glass-card profile-section">
            <div class="text-label q-mb-lg">{{ $t('profile.sectionAccount') }}</div>

            <div class="profile-row">
              <div>
                <div class="text-label">{{ t('profile.email') }}</div>
                <div class="text-body q-mt-xs">{{ profile.email }}</div>
              </div>
              <q-btn flat dense icon="edit" round class="edit-btn" @click="showEmailDialog = true">
                <q-tooltip>{{ t('profile.edit') }}</q-tooltip>
              </q-btn>
            </div>

            <div class="profile-row">
              <div>
                <div class="text-label">{{ t('profile.password') }}</div>
                <div class="text-body q-mt-xs">{{ t('profile.passwordMasked') }}</div>
              </div>
              <q-btn
                flat
                dense
                icon="edit"
                round
                class="edit-btn"
                @click="showPasswordDialog = true"
              >
                <q-tooltip>{{ t('profile.edit') }}</q-tooltip>
              </q-btn>
            </div>

            <div class="profile-row">
              <div>
                <div class="text-label">{{ t('profile.twoFactorAuth') }}</div>
                <div class="q-mt-xs">
                  <span class="badge-accent" :style="profile.otpEnabled ? '' : 'opacity: 0.5'">
                    {{ profile.otpEnabled ? t('profile.enabled') : t('profile.disabled') }}
                  </span>
                </div>
              </div>
              <q-btn flat dense icon="edit" round class="edit-btn" @click="show2faDialog = true">
                <q-tooltip>{{ t('profile.edit') }}</q-tooltip>
              </q-btn>
            </div>
          </div>

          <div class="glass-card profile-section">
            <div class="text-label q-mb-lg">{{ $t('profile.sectionPersonal') }}</div>

            <div class="profile-row">
              <div>
                <div class="text-label">{{ t('profile.personalInfo') }}</div>
                <div class="text-body q-mt-xs">{{ fullName || '—' }}</div>
                <div class="text-meta q-mt-xs" v-if="profile.nationalId">
                  {{ t('auth.nationalId') }}: {{ profile.nationalId }}
                </div>
                <div class="text-meta q-mt-xs" v-if="profile.gender">
                  {{ t('auth.gender') }}: {{ genderLabel }}
                </div>
                <div class="text-meta q-mt-xs" v-if="profile.langKey">
                  {{ t('auth.preferredLanguage') }}: {{ languageLabel }}
                </div>
              </div>
              <q-btn flat dense icon="edit" round class="edit-btn" @click="showInfoDialog = true">
                <q-tooltip>{{ t('profile.edit') }}</q-tooltip>
              </q-btn>
            </div>

            <div class="profile-row">
              <div>
                <div class="text-label">{{ t('profile.phone') }}</div>
                <div class="text-body q-mt-xs">{{ profile.phone || t('profile.noPhone') }}</div>
              </div>
              <q-btn flat dense icon="edit" round class="edit-btn" @click="showPhoneDialog = true">
                <q-tooltip>{{ t('profile.edit') }}</q-tooltip>
              </q-btn>
            </div>

            <div class="profile-row">
              <div>
                <div class="text-label">{{ t('profile.address') }}</div>
                <div class="text-body q-mt-xs">
                  {{ formattedAddress || t('profile.noAddress') }}
                </div>
              </div>
              <q-btn
                flat
                dense
                icon="edit"
                round
                class="edit-btn"
                @click="showAddressDialog = true"
              >
                <q-tooltip>{{ t('profile.edit') }}</q-tooltip>
              </q-btn>
            </div>
          </div>

          <!-- AC2: Coach Profile section — role-gated, only ever shown to a coach -->
          <div v-if="authStore.isCoach" class="glass-card profile-section">
            <div class="text-label q-mb-lg">{{ $t('profile.sectionCoachProfile') }}</div>

            <!-- skillars-deferred-139 review Patch 1: a swallowed fetch failure must not render the
                 edit dialogs with a blank prefill (which would silently wipe untouched optional
                 fields on save) — hide the edit affordances and show a retry-able error instead. -->
            <div v-if="coachProfileError" class="profile-row">
              <div class="text-meta" style="color: var(--accent-danger)">
                {{ t('profile.coachProfileLoadError') }}
              </div>
              <q-btn flat dense no-caps :label="t('common.retry')" @click="loadCoachProfile" />
            </div>

            <template v-else-if="coachProfile">
              <!-- skillars-deferred-139 review D4, corrected by the review audit: a suspended coach
                   must not be able to rewrite their public display name/bio/specialties/pricing, but
                   must still be able to SEE their own profile. So every row below renders read-only
                   and only the edit buttons drop out — the earlier version replaced the whole section
                   with a bare note, which hid the coach's own data from them. -->
              <div v-if="isCoachSuspended" class="profile-row">
                <div class="text-meta">{{ t('profile.coachSuspendedNote') }}</div>
                <!-- FR-ADM-004: a suspension is an appealable enforcement action. -->
                <AppealAction
                  v-if="coachProfile.coachId"
                  subject-type="COACH_SUSPENSION"
                  :subject-id="coachProfile.coachId"
                  :enforced-at="coachProfile.statusChangedAt || null"
                />
              </div>

              <div class="profile-row">
                <div>
                  <div class="text-label">{{ t('profile.coachIdentity') }}</div>
                  <div class="text-body q-mt-xs">
                    {{ coachProfile?.displayName || '—' }}
                    <span v-if="coachProfile?.city" class="text-meta">
                      · {{ coachProfile.city
                      }}<span v-if="coachProfile.district">, {{ coachProfile.district }}</span>
                    </span>
                  </div>
                </div>
                <q-btn
                  v-if="!isCoachSuspended"
                  flat
                  dense
                  icon="edit"
                  round
                  class="edit-btn"
                  @click="showCoachIdentityDialog = true"
                >
                  <q-tooltip>{{ t('profile.edit') }}</q-tooltip>
                </q-btn>
              </div>

              <div class="profile-row">
                <div>
                  <div class="text-label">{{ t('profile.coachSpecialties') }}</div>
                  <div class="text-body q-mt-xs">
                    {{ (coachProfile?.specialties || []).join(', ') || '—' }}
                  </div>
                </div>
                <q-btn
                  v-if="!isCoachSuspended"
                  flat
                  dense
                  icon="edit"
                  round
                  class="edit-btn"
                  @click="showCoachSpecialtiesDialog = true"
                >
                  <q-tooltip>{{ t('profile.edit') }}</q-tooltip>
                </q-btn>
              </div>

              <div class="profile-row">
                <div>
                  <div class="text-label">{{ t('profile.coachPricing') }}</div>
                  <div class="text-body q-mt-xs">
                    {{
                      coachProfile?.perSessionPrice != null
                        ? `€${coachProfile.perSessionPrice}`
                        : '—'
                    }}
                  </div>
                </div>
                <q-btn
                  v-if="!isCoachSuspended"
                  flat
                  dense
                  icon="edit"
                  round
                  class="edit-btn"
                  @click="showCoachPricingDialog = true"
                >
                  <q-tooltip>{{ t('profile.edit') }}</q-tooltip>
                </q-btn>
              </div>

              <div class="profile-row">
                <div>
                  <div class="text-label">{{ t('profile.coachAvailability') }}</div>
                  <div class="text-body q-mt-xs">
                    {{
                      t('profile.coachAvailabilityWindowCount', {
                        count: (coachProfile?.availabilityWindows || []).length,
                      })
                    }}
                  </div>
                </div>
                <q-btn
                  v-if="!isCoachSuspended"
                  flat
                  dense
                  icon="edit"
                  round
                  class="edit-btn"
                  @click="showCoachAvailabilityDialog = true"
                >
                  <q-tooltip>{{ t('profile.edit') }}</q-tooltip>
                </q-btn>
              </div>

              <div class="profile-row">
                <div>
                  <div class="text-label">{{ t('profile.coachPhoto') }}</div>
                  <div class="text-body q-mt-xs">
                    {{
                      coachProfile?.photoUrl ? t('profile.photoCurrentlySet') : t('profile.noPhoto')
                    }}
                  </div>
                </div>
                <q-btn
                  v-if="!isCoachSuspended"
                  flat
                  dense
                  icon="edit"
                  round
                  class="edit-btn"
                  @click="showCoachPhotoDialog = true"
                >
                  <q-tooltip>{{ t('profile.edit') }}</q-tooltip>
                </q-btn>
              </div>

              <!-- feat/coach-workflow-gaps D9: public-profile media gallery (story 2.3) -->
              <div class="profile-row">
                <div>
                  <div class="text-label">{{ t('profile.coachGalleryTitle') }}</div>
                  <div class="text-body q-mt-xs">{{ t('profile.coachGallerySummary') }}</div>
                </div>
                <q-btn
                  v-if="!isCoachSuspended"
                  flat
                  dense
                  icon="edit"
                  round
                  class="edit-btn"
                  data-test="edit-coach-gallery"
                  @click="showCoachGalleryDialog = true"
                >
                  <q-tooltip>{{ t('profile.edit') }}</q-tooltip>
                </q-btn>
              </div>
            </template>
          </div>

          <!-- skillars-deferred-151 AC3: coach report branding — role-gated, ACADEMY-tier gated.
               Three-state render: loading (coachTier not resolved yet) / locked (not ACADEMY) /
               unlocked. authStore.coachTier is only populated by fetchCoachTier(), whose one other
               caller is the coach player-detail page (CoachPlayerDetailPage.vue, skillars-12-9) —
               a bare read here would be null on any cold /profile load, hiding the feature from
               the coaches entitled to it. -->
          <div v-if="authStore.isCoach" class="glass-card profile-section">
            <div class="text-label q-mb-lg">{{ t('profile.sectionCoachBranding') }}</div>

            <div v-if="brandingTierLoading" class="profile-row">
              <div class="text-body">{{ t('common.loading') }}</div>
            </div>
            <div v-else-if="coachBrandingError" class="profile-row">
              <div class="text-meta" style="color: var(--accent-danger)">
                {{ t('profile.coachBrandingLoadError') }}
              </div>
              <q-btn flat dense no-caps :label="t('common.retry')" @click="loadCoachBranding" />
            </div>
            <!-- UX-DR22 soft teaser: a blurred sample of the branding controls with an upgrade CTA
                 instead of a bare "requires a higher tier" line. The rest of the page stays usable. -->
            <FeatureGateOverlay
              v-else-if="authStore.coachTier !== 'ACADEMY'"
              :required-tier="t('featureGate.tier.ACADEMY')"
              :feature-label="t('profile.sectionCoachBranding')"
              upgrade-to="/coach/subscription"
            >
              <div class="profile-row row items-center q-gutter-md" style="min-height: 240px">
                <q-icon name="image" size="48px" />
                <div class="coach-branding-gate-swatch" />
              </div>
            </FeatureGateOverlay>
            <template v-else>
              <!-- AC8: the logo was already fetched (coachBranding.logoSignedUrl) but never
                   rendered here -- only reachable from inside EditCoachBrandingDialog. -->
              <div v-if="coachBranding?.logoSignedUrl" class="profile-row">
                <div class="row items-center q-gutter-md">
                  <q-img :src="coachBranding.logoSignedUrl" class="coach-branding-logo-preview">
                    <!-- AC8: ReportGenerationService.getBranding re-presigns on every call, but
                         this page fetches once on mount -- a tab left open past the presigned
                         URL's TTL would otherwise show a broken image with no way to recover. -->
                    <template #error>
                      <div
                        class="column items-center justify-center full-height q-gutter-xs"
                        style="color: var(--text-secondary)"
                      >
                        <q-icon name="broken_image" size="24px" />
                        <q-btn
                          flat
                          dense
                          no-caps
                          size="sm"
                          :label="t('common.retry')"
                          @click="loadCoachBranding"
                        />
                      </div>
                    </template>
                  </q-img>
                  <div>
                    <div class="text-label">{{ t('profile.brandColourLabel') }}</div>
                    <div class="text-body q-mt-xs">
                      {{ coachBranding?.brandColour || '—' }}
                    </div>
                  </div>
                </div>
                <q-btn
                  flat
                  dense
                  icon="edit"
                  round
                  class="edit-btn"
                  @click="showCoachBrandingDialog = true"
                >
                  <q-tooltip>{{ t('profile.edit') }}</q-tooltip>
                </q-btn>
              </div>
              <div v-else class="profile-row">
                <div>
                  <div class="text-label">{{ t('profile.brandColourLabel') }}</div>
                  <div class="text-body q-mt-xs">
                    {{ coachBranding?.brandColour || '—' }}
                  </div>
                </div>
                <q-btn
                  flat
                  dense
                  icon="edit"
                  round
                  class="edit-btn"
                  @click="showCoachBrandingDialog = true"
                >
                  <q-tooltip>{{ t('profile.edit') }}</q-tooltip>
                </q-btn>
              </div>
            </template>
          </div>

          <!-- AC4: Player Profile section — role-gated, only ever shown to a player -->
          <div v-if="authStore.isPlayer" class="glass-card profile-section">
            <div class="text-label q-mb-lg">{{ $t('profile.sectionPlayerProfile') }}</div>

            <!-- skillars-deferred-139 review D3: a self-registered adult who never finished the
                 player profile builder has no PlayerProfile row — route to the builder instead of a
                 dead Edit button that would 404. -->
            <div v-if="!playerProfile" class="profile-row">
              <div>
                <div class="text-body q-mt-xs">{{ t('profile.noPlayerProfile') }}</div>
              </div>
              <q-btn
                flat
                dense
                no-caps
                color="primary"
                :label="t('profile.completePlayerProfile')"
                to="/player/profile-builder"
              />
            </div>

            <div v-else class="profile-row">
              <div>
                <div class="text-label">{{ t('auth.player.position') }}</div>
                <div class="text-body q-mt-xs">{{ playerPositionLabel || '—' }}</div>
              </div>
              <q-btn
                flat
                dense
                icon="edit"
                round
                class="edit-btn"
                @click="showPlayerPositionDialog = true"
              >
                <q-tooltip>{{ t('profile.edit') }}</q-tooltip>
              </q-btn>
            </div>
          </div>

          <!-- AC5: My Children's Profiles section — role-gated, only ever shown to a parent -->
          <div v-if="authStore.isParent" class="glass-card profile-section">
            <div class="text-label q-mb-lg">{{ $t('profile.sectionMyChildren') }}</div>

            <div v-if="!playerStore.players.length" class="text-meta">
              {{ t('profile.noChildren') }}
            </div>

            <div v-for="child in playerStore.players" :key="child.id" class="profile-row">
              <div>
                <div class="text-label">{{ child.name }}</div>
                <div class="text-body q-mt-xs">{{ childPositionLabel(child.position) }}</div>
              </div>
              <q-btn
                flat
                dense
                icon="edit"
                round
                class="edit-btn"
                @click="openChildPositionDialog(child.id)"
              >
                <q-tooltip>{{ t('profile.edit') }}</q-tooltip>
              </q-btn>
              <q-btn
                flat
                dense
                icon="privacy_tip"
                round
                class="privacy-btn"
                data-testid="child-privacy-btn"
                @click="openChildPrivacyDialog(child)"
              >
                <q-tooltip>{{ t('privacy.childButtonTooltip') }}</q-tooltip>
              </q-btn>
            </div>
          </div>

          <!-- FR-TSC-007: data export and account deletion, every role -->
          <div class="glass-card profile-section">
            <div class="text-label q-mb-lg">{{ t('profile.sectionPrivacy') }}</div>
            <PrivacyDataSection />
          </div>
        </div>
      </template>
    </div>

    <!-- Dialogs -->
    <UpdateEmailDialog
      v-model="showEmailDialog"
      :current-email="profile?.email"
      @updated="loadProfile"
    />
    <UpdatePasswordDialog v-model="showPasswordDialog" @updated="loadProfile" />
    <UpdatePhoneDialog
      v-model="showPhoneDialog"
      :current-phone="profile?.phone"
      @updated="loadProfile"
    />
    <UpdateAddressDialog
      v-model="showAddressDialog"
      :current-address="profile?.address"
      @updated="loadProfile"
    />
    <UpdateInfoDialog v-model="showInfoDialog" :current-info="currentInfo" @updated="loadProfile" />
    <Toggle2faDialog
      v-model="show2faDialog"
      :current-enabled="profile?.otpEnabled"
      @updated="loadProfile"
    />

    <!-- AC2/AC3 -->
    <EditCoachIdentityDialog
      v-model="showCoachIdentityDialog"
      :current="coachProfile"
      @updated="loadProfile"
    />
    <EditCoachSpecialtiesDialog
      v-model="showCoachSpecialtiesDialog"
      :current="coachProfile"
      @updated="loadProfile"
    />
    <EditCoachPricingDialog
      v-model="showCoachPricingDialog"
      :current="coachProfile"
      @updated="loadProfile"
    />
    <EditCoachAvailabilityDialog
      v-model="showCoachAvailabilityDialog"
      :current="coachProfile"
      @updated="loadProfile"
    />
    <EditCoachPhotoDialog
      v-model="showCoachPhotoDialog"
      :current="coachProfile"
      @updated="loadProfile"
    />
    <EditCoachGalleryDialog v-model="showCoachGalleryDialog" />
    <EditCoachBrandingDialog
      v-model="showCoachBrandingDialog"
      :current="coachBranding"
      @updated="loadCoachBranding"
    />

    <!-- AC4/AC5: shared dialog — no playerId for the player's own position (AC4), a playerId for a
         parent editing one specific child's position (AC5) -->
    <EditPlayerPositionDialog
      v-model="showPlayerPositionDialog"
      :current-position="playerProfile?.position"
      @updated="loadProfile"
    />
    <EditPlayerPositionDialog
      v-model="showChildPositionDialog"
      :player-id="activeChildId"
      :current-position="activeChildPosition"
      @updated="loadProfile"
    />

    <!-- D11: a parent's export/erasure for one child profile -->
    <q-dialog v-model="showChildPrivacyDialog">
      <q-card
        data-testid="child-privacy-dialog"
        style="min-width: 320px; max-width: 560px; width: 100%"
      >
        <q-card-section class="row items-center">
          <div class="text-h6">
            {{ t('privacy.childDialogTitle', { name: privacyChild?.name || '' }) }}
          </div>
          <q-space />
          <q-btn flat round dense icon="close" v-close-popup />
        </q-card-section>
        <q-card-section v-if="privacyChild">
          <PrivacyDataSection
            :player-id="privacyChild.id"
            :player-name="privacyChild.name"
            @erased="showChildPrivacyDialog = false"
          />
        </q-card-section>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { profileApi } from 'src/api/profile.api'
import { getOwnCoachProfile } from 'src/api/marketplace.api'
import { getCoachBranding } from 'src/api/development.api'
import { playerRegistrationApi } from 'src/api/playerRegistration.api'
import { useAuthStore } from 'src/stores/auth.store'
import { usePlayerStore } from 'src/stores/playerStore'
import { useErrorHandler } from 'src/composables/useErrorHandler'
import UpdateEmailDialog from 'src/components/profile/UpdateEmailDialog.vue'
import UpdatePasswordDialog from 'src/components/profile/UpdatePasswordDialog.vue'
import UpdatePhoneDialog from 'src/components/profile/UpdatePhoneDialog.vue'
import UpdateAddressDialog from 'src/components/profile/UpdateAddressDialog.vue'
import UpdateInfoDialog from 'src/components/profile/UpdateInfoDialog.vue'
import Toggle2faDialog from 'src/components/profile/Toggle2faDialog.vue'
import EditCoachIdentityDialog from 'src/components/profile/EditCoachIdentityDialog.vue'
import EditCoachSpecialtiesDialog from 'src/components/profile/EditCoachSpecialtiesDialog.vue'
import EditCoachPricingDialog from 'src/components/profile/EditCoachPricingDialog.vue'
import EditCoachAvailabilityDialog from 'src/components/profile/EditCoachAvailabilityDialog.vue'
import EditCoachPhotoDialog from 'src/components/profile/EditCoachPhotoDialog.vue'
import EditCoachBrandingDialog from 'src/components/profile/EditCoachBrandingDialog.vue'
import EditCoachGalleryDialog from 'src/components/profile/EditCoachGalleryDialog.vue'
import EditPlayerPositionDialog from 'src/components/profile/EditPlayerPositionDialog.vue'
import FeatureGateOverlay from 'src/components/common/FeatureGateOverlay.vue'
import PrivacyDataSection from 'src/components/profile/PrivacyDataSection.vue'
import AppealAction from 'src/components/common/AppealAction.vue'

const { t } = useI18n()
const { setError, clearError, hasError, errorMessage, helpCode } = useErrorHandler()
const authStore = useAuthStore()
const playerStore = usePlayerStore()

const profile = ref(null)
const coachProfile = ref(null)
const coachProfileError = ref(false)
const playerProfile = ref(null)
const isLoading = ref(false)

const isCoachSuspended = computed(() => coachProfile.value?.status === 'SUSPENDED')

const showEmailDialog = ref(false)
const showPasswordDialog = ref(false)
const showPhoneDialog = ref(false)
const showAddressDialog = ref(false)
const showInfoDialog = ref(false)
const show2faDialog = ref(false)

const showCoachIdentityDialog = ref(false)
const showCoachSpecialtiesDialog = ref(false)
const showCoachPricingDialog = ref(false)
const showCoachAvailabilityDialog = ref(false)
const showCoachPhotoDialog = ref(false)
const showCoachGalleryDialog = ref(false)
const showCoachBrandingDialog = ref(false)
const coachBranding = ref(null)
const brandingTierLoading = ref(true)
const coachBrandingError = ref(false)

const showPlayerPositionDialog = ref(false)
const showChildPositionDialog = ref(false)
const activeChildId = ref(null)

const activeChildPosition = computed(
  () => playerStore.players.find((p) => p.id === activeChildId.value)?.position || null,
)

const playerPositionLabel = computed(() => childPositionLabel(playerProfile.value?.position))

function childPositionLabel(position) {
  const m = {
    GOALKEEPER: t('auth.player.positionGoalkeeper'),
    DEFENDER: t('auth.player.positionDefender'),
    MIDFIELDER: t('auth.player.positionMidfielder'),
    FORWARD: t('auth.player.positionForward'),
  }
  return position ? m[position] || position : '—'
}

function openChildPositionDialog(playerId) {
  activeChildId.value = playerId
  showChildPositionDialog.value = true
}

const showChildPrivacyDialog = ref(false)
const privacyChild = ref(null)

function openChildPrivacyDialog(child) {
  privacyChild.value = { id: child.id, name: child.name }
  showChildPrivacyDialog.value = true
}

// skillars-deferred-139 review Patch 1: a separate, retry-able loader rather than an inline
// try/catch in loadProfile — on failure, coachProfileError gates the edit buttons so a stale/blank
// prefill in the Identity/Pricing dialogs can never be resubmitted and wipe real data.
async function loadCoachProfile() {
  try {
    coachProfile.value = await getOwnCoachProfile()
    coachProfileError.value = false
  } catch {
    coachProfile.value = null
    coachProfileError.value = true
  }
}

// skillars-deferred-151 AC3: fetchCoachTier()'s only other caller is CoachPlayerDetailPage
// (skillars-12-9; it used to be PlayerDevelopmentDashboardPage) — so coachTier is still its initial
// null on any direct visit to /profile without this call, reading as "not ACADEMY" for a genuine
// ACADEMY coach.
//
// skillars-deferred-151 review Patch: a failed branding GET must not leave coachBranding null while
// still unlocking the edit card — EditCoachBrandingDialog reads the existing logoKey off
// props.current, so a null current + a colour-only save would resend logoKey: null and silently
// wipe the stored logo. coachBrandingError gates the edit affordance instead, mirroring
// loadCoachProfile/coachProfileError above.
async function loadCoachBranding() {
  brandingTierLoading.value = true
  coachBrandingError.value = false
  try {
    await authStore.fetchCoachTier()
    if (authStore.coachTier === 'ACADEMY') {
      coachBranding.value = await getCoachBranding()
    }
  } catch {
    coachBrandingError.value = true
  } finally {
    brandingTierLoading.value = false
  }
}

onMounted(async () => {
  await loadProfile()
})

async function loadProfile() {
  isLoading.value = true
  clearError()
  try {
    profile.value = await profileApi.getProfile()
  } catch (err) {
    setError(err)
  } finally {
    isLoading.value = false
  }

  // Role-specific sections load independently and swallow their own failures — a problem loading
  // one of these must never block the two role-agnostic cards above from rendering.
  if (authStore.isCoach) {
    await loadCoachProfile()
    await loadCoachBranding()
  }
  if (authStore.isPlayer) {
    try {
      playerProfile.value = await playerRegistrationApi.getMyProfile()
    } catch {
      playerProfile.value = null
    }
  }
  if (authStore.isParent) {
    try {
      await playerStore.fetchPlayers()
    } catch {
      /* ignore — the section just renders empty */
    }
  }
}

const formattedAddress = computed(() => {
  if (!profile.value?.address) return null
  const a = profile.value.address
  const lines = [a.addressLine1, a.addressLine2, a.addressLine3].filter(Boolean)
  const parts = [lines.join(', '), a.city, a.stateProvince, a.postalCode, a.country].filter(Boolean)
  return parts.join(', ')
})

const fullName = computed(() => {
  if (!profile.value) return ''
  return [profile.value.title, profile.value.firstName, profile.value.lastName]
    .filter(Boolean)
    .join(' ')
})

const genderLabel = computed(() => {
  const m = { MALE: t('auth.male'), FEMALE: t('auth.female'), OTHER: t('auth.other') }
  return m[profile.value?.gender] || profile.value?.gender || ''
})

const languageLabel = computed(() => {
  const m = { en: t('auth.languageEnglish'), fr: t('auth.languageFrench') }
  return m[profile.value?.langKey] || profile.value?.langKey || ''
})

const currentInfo = computed(() => {
  if (!profile.value) return null
  return {
    title: profile.value.title,
    firstName: profile.value.firstName,
    lastName: profile.value.lastName,
    nationalId: profile.value.nationalId,
    gender: profile.value.gender,
    langKey: profile.value.langKey,
  }
})
</script>

<style lang="scss" scoped>
.coach-branding-gate-swatch {
  width: 48px;
  height: 48px;
  border-radius: 8px;
  background: var(--accent-primary, #3b82f6);
}

.page-header {
  border-bottom: 1px solid var(--border-soft);
  padding-bottom: 24px;
}

.profile-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 24px;
}

.profile-section {
  padding: 28px;
}

.profile-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 16px 0;
  border-bottom: 1px solid var(--border-soft);

  &:last-child {
    border-bottom: none;
    padding-bottom: 0;
  }

  &:first-of-type {
    padding-top: 0;
  }
}

.coach-branding-logo-preview {
  width: 64px;
  height: 64px;
  border-radius: 16px;
  border: 1px solid var(--border-soft);
}

.edit-btn,
.privacy-btn {
  color: var(--text-muted) !important;
  flex-shrink: 0;
  margin-left: 12px;

  &:hover {
    color: var(--accent-primary) !important;
    background: var(--nav-active-bg) !important;
  }
}
</style>
