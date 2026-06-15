<template>
  <q-page class="coach-profile-page q-pa-md">
    <!-- Loading state -->
    <div v-if="loading" class="row q-col-gutter-md">
      <div class="col-12 col-md-8">
        <q-skeleton type="rect" height="120px" class="q-mb-md" />
        <q-skeleton type="text" class="q-mb-sm" />
        <q-skeleton type="text" width="60%" />
      </div>
      <div class="col-12 col-md-4">
        <q-skeleton type="rect" height="200px" />
      </div>
    </div>

    <!-- 404 / error state -->
    <div v-else-if="notFound" class="flex flex-center column q-gutter-md" style="min-height: 60vh">
      <q-icon name="person_off" size="64px" style="color: var(--text-secondary)" />
      <div class="text-h6" style="color: var(--text-primary)">
        {{ t('marketplace.coachNotFound') }}
      </div>
      <q-btn
        flat
        :label="t('marketplace.backToMarketplace')"
        icon="arrow_back"
        @click="router.push('/marketplace')"
      />
    </div>

    <!-- Profile content -->
    <div v-else-if="profile" class="row q-col-gutter-lg">
      <!-- LEFT / MAIN COLUMN -->
      <div class="col-12 col-md-8">
        <!-- Hero header -->
        <div class="hero-header glass-card q-pa-md q-mb-md row items-center q-gutter-md">
          <q-avatar size="80px">
            <q-img v-if="profile.photoUrl" :src="profile.photoUrl" :ratio="1" />
            <q-icon v-else name="person" size="48px" style="color: var(--text-secondary)" />
          </q-avatar>
          <div class="col">
            <div class="text-h5 text-weight-bold" style="color: var(--text-primary)">
              {{ profile.displayName }}
            </div>
            <div class="row items-center q-gutter-sm q-mt-xs">
              <VerificationBadge :tier="profile.verificationTier" />
              <q-chip
                dense
                :color="profile.available ? 'positive' : 'grey-6'"
                text-color="white"
                :icon="profile.available ? 'circle' : 'remove_circle_outline'"
                size="sm"
              >
                {{
                  profile.available
                    ? t('marketplace.availabilityStatus.available')
                    : t('marketplace.availabilityStatus.unavailable')
                }}
              </q-chip>
            </div>
          </div>
        </div>

        <!-- Capability badges + reliability -->
        <div class="glass-card q-pa-md q-mb-md">
          <CapabilityBadgeSet :badges="profile.capabilityBadges" />
          <div class="q-mt-sm">
            <ReliabilityIndicator :strike-count="profile.reliabilityStrikeCount" />
          </div>
        </div>

        <!-- Star rating -->
        <div class="glass-card q-pa-md q-mb-md row items-center q-gutter-sm">
          <q-rating
            v-if="profile.reviewCount > 0"
            :model-value="profile.aggregateRating"
            readonly
            size="18px"
            color="amber"
          />
          <span class="text-body2" style="color: var(--text-secondary)">
            {{
              profile.reviewCount > 0
                ? `${profile.aggregateRating.toFixed(1)} (${profile.reviewCount})`
                : t('marketplace.noReviewsYet')
            }}
          </span>
        </div>

        <!-- Bio -->
        <div v-if="profile.bio" class="glass-card q-pa-md q-mb-md">
          <div class="text-body1" style="color: var(--text-primary); white-space: pre-wrap">
            {{ profile.bio }}
          </div>
        </div>

        <!-- Info chips -->
        <div class="glass-card q-pa-md q-mb-md">
          <div v-if="profile.languages?.length" class="q-mb-sm">
            <q-chip
              v-for="lang in profile.languages"
              :key="lang"
              dense
              icon="language"
              size="sm"
              class="info-chip"
            >
              {{ lang }}
            </q-chip>
          </div>
          <div v-if="profile.city" class="text-body2 q-mb-sm" style="color: var(--text-secondary)">
            <q-icon name="location_on" size="16px" class="q-mr-xs" />
            {{ profile.city }}<template v-if="profile.district">, {{ profile.district }}</template>
          </div>
          <div v-if="profile.specialties?.length" class="q-mb-sm">
            <q-chip v-for="s in profile.specialties" :key="s" dense size="sm" class="info-chip">
              {{ s }}
            </q-chip>
          </div>
          <div v-if="profile.ageGroupsCoached?.length">
            <q-chip
              v-for="ag in profile.ageGroupsCoached"
              :key="ag"
              dense
              icon="people"
              size="sm"
              class="info-chip"
            >
              {{ ag }}
            </q-chip>
          </div>
        </div>

        <!-- Media gallery -->
        <div v-if="profile.mediaGallery?.length" class="glass-card q-pa-md q-mb-md">
          <div class="gallery-strip">
            <template v-for="item in profile.mediaGallery" :key="item.id">
              <q-img
                v-if="item.mediaType === 'IMAGE'"
                :src="item.fileUrl"
                class="gallery-item"
                fit="cover"
                @click="openLightbox(item)"
              />
              <video
                v-else
                :src="item.fileUrl"
                class="gallery-item"
                preload="none"
                @click="openLightbox(item)"
              />
            </template>
          </div>
        </div>
      </div>

      <!-- RIGHT SIDEBAR -->
      <div class="col-12 col-md-4">
        <div class="glass-card q-pa-md">
          <!-- Hero price -->
          <div
            v-if="profile.perSessionPrice"
            class="text-h4 text-weight-bold q-mb-md"
            style="color: var(--text-primary)"
          >
            {{ formatCurrency(profile.perSessionPrice, profile.currency) }}
            <span class="text-caption text-weight-regular" style="color: var(--text-secondary)">
              / {{ t('marketplace.perSession') }}
            </span>
          </div>

          <!-- Session pack pricing display -->
          <SessionPackPricingDisplay
            :session-packs="profile.sessionPacks"
            :per-session-price="profile.perSessionPrice"
            :currency="profile.currency"
            class="q-mb-md"
          />

          <!-- Credit tracker — always visible before booking CTA for parents -->
          <SessionPackTracker
            v-if="authStore.isParent"
            :credits-remaining="creditsForThisCoach"
            :session-count="activePackSessionCount"
            class="q-mb-md"
            @buy-sessions="handleBuySessions"
          />

          <!-- CTA -->
          <q-btn
            unelevated
            color="primary"
            class="full-width"
            size="md"
            :label="ctaLabel"
            :loading="authStore.isParent && bookingStore.packsLoading"
            @click="handleCta"
          />
        </div>
      </div>
    </div>

    <!-- Lightbox dialog -->
    <q-dialog v-model="lightboxOpen" maximized>
      <q-card class="lightbox-card" style="background: rgba(0, 0, 0, 0.92)">
        <q-btn
          flat
          round
          icon="close"
          color="white"
          class="absolute-top-right q-ma-sm"
          v-close-popup
        />
        <div class="flex flex-center" style="height: 100%">
          <q-img
            v-if="lightboxItem?.mediaType === 'IMAGE'"
            :src="lightboxItem.fileUrl"
            style="max-width: 90vw; max-height: 90vh"
            fit="contain"
          />
          <video
            v-else-if="lightboxItem?.mediaType === 'VIDEO'"
            :src="lightboxItem.fileUrl"
            controls
            autoplay
            style="max-width: 90vw; max-height: 90vh"
          />
        </div>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { getCoachProfile } from 'src/api/marketplace.api'
import { useAuthStore } from 'src/stores/auth.store'
import { useBookingStore } from 'src/stores/booking.store'
import { usePlayerStore } from 'src/stores/playerStore'
import VerificationBadge from 'src/components/marketplace/VerificationBadge.vue'
import ReliabilityIndicator from 'src/components/marketplace/ReliabilityIndicator.vue'
import CapabilityBadgeSet from 'src/components/marketplace/CapabilityBadgeSet.vue'
import SessionPackPricingDisplay from 'src/components/marketplace/SessionPackPricingDisplay.vue'
import SessionPackTracker from 'src/components/booking/SessionPackTracker.vue'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const authStore = useAuthStore()
const bookingStore = useBookingStore()
const playerStore = usePlayerStore()

const profile = ref(null)
const loading = ref(true)
const notFound = ref(false)
const lightboxOpen = ref(false)
const lightboxItem = ref(null)

const coachId = route.params.coachId

const creditsForThisCoach = computed(() => bookingStore.creditsForCoach(coachId))
const activePackSessionCount = computed(() => {
  const activePack = bookingStore.sessionPacks.find(
    (p) => p.coachId === coachId && p.status === 'ACTIVE',
  )
  return activePack?.sessionCount ?? 0
})
const hasCreditsForCoach = computed(() =>
  bookingStore.sessionPacks.some(
    (p) => p.coachId === coachId && p.status === 'ACTIVE' && p.creditsRemaining > 0,
  ),
)

const ctaLabel = computed(() => {
  if (!authStore.isParent) return t('marketplace.signUpToBook')
  return hasCreditsForCoach.value ? t('marketplace.bookSession') : t('booking.packs.buySessions')
})

function handleBuySessions() {
  const playerId = playerStore.activePlayerId
  if (!playerId) return
  router.push(`/parent/coaches/${coachId}/purchase-sessions?playerId=${playerId}`)
}

onMounted(async () => {
  try {
    const response = await getCoachProfile(coachId)
    profile.value = response.data

    if (authStore.isParent) {
      if (!playerStore.activePlayerId) {
        await playerStore.fetchPlayers()
      }
      if (playerStore.activePlayerId) {
        await bookingStore.loadPlayerPacks(playerStore.activePlayerId)
      }
    }
  } catch (err) {
    if (err.response?.status === 404) {
      notFound.value = true
    }
  } finally {
    loading.value = false
  }
})

function handleCta() {
  if (!profile.value) return
  if (authStore.isParent) {
    if (hasCreditsForCoach.value) {
      const playerId = playerStore.activePlayerId
      const params = new URLSearchParams()
      if (playerId) params.set('playerId', playerId)
      if (profile.value?.displayName) params.set('coachName', profile.value.displayName)
      const qs = params.toString()
      router.push(`/parent/coaches/${coachId}/request-booking${qs ? `?${qs}` : ''}`)
    } else {
      router.push(`/parent/coaches/${coachId}/purchase-sessions`)
    }
  } else {
    router.push(`/login?returnUrl=${encodeURIComponent(route.fullPath)}`)
  }
}

function formatCurrency(value, currency = 'EUR') {
  return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(Number(value))
}

function openLightbox(item) {
  lightboxItem.value = item
  lightboxOpen.value = true
}
</script>

<style lang="scss" scoped>
.coach-profile-page {
  max-width: 1200px;
  margin: 0 auto;
}

.hero-header {
  border-radius: 12px;
}

.info-chip {
  color: var(--text-primary);
  background: var(--surface-raised);
  border: 1px solid var(--border-subtle);
}

.gallery-strip {
  overflow-x: auto;
  display: flex;
  gap: 8px;
}

.gallery-item {
  width: 160px;
  height: 120px;
  border-radius: 8px;
  flex-shrink: 0;
  cursor: pointer;
  object-fit: cover;
}
</style>
