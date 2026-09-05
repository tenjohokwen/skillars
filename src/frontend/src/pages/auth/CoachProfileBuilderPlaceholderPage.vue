<template>
  <q-page class="auth-page">
    <div class="auth-card-container--wide fade-in">
      <div class="auth-brand q-mb-lg">
        <div class="gradient-text auth-brand-name">Skillars</div>
        <div class="text-meta">{{ t('auth.coach.profileBuilderBody') }}</div>
      </div>

      <div class="glass-card--static auth-card profile-builder">
        <div class="text-section-title q-mb-lg">{{ t('auth.coach.profileBuilderTitle') }}</div>

        <!-- Progress rail -->
        <div class="profile-builder__rail">
          <template v-for="step in steps" :key="step.n">
            <div
              class="profile-builder__rail-step"
              :class="{
                'profile-builder__rail-step--active': store.currentStep === step.n,
                'profile-builder__rail-step--done': store.lastCompletedStep >= step.n,
                'profile-builder__rail-step--reachable': step.n <= store.lastCompletedStep + 1,
              }"
              @click="goToStep(step.n)"
            >
              <div class="profile-builder__rail-dot">
                <q-icon
                  v-if="store.lastCompletedStep >= step.n && store.currentStep !== step.n"
                  name="check"
                  size="16px"
                />
                <q-icon v-else :name="step.icon" size="16px" />
              </div>
              <div class="profile-builder__rail-label">{{ t(step.shortKey) }}</div>
            </div>
            <div
              v-if="step.n < steps.length"
              class="profile-builder__rail-connector"
              :class="{
                'profile-builder__rail-connector--done': store.lastCompletedStep >= step.n,
              }"
            />
          </template>
        </div>

        <div class="text-meta q-mt-lg q-mb-xs">
          {{ t('auth.coach.stepOfTotal', { current: store.currentStep, total: steps.length }) }}
        </div>
        <div class="text-card-title q-mb-md">{{ t(currentStep.titleKey) }}</div>

        <q-banner v-if="store.error" class="q-mb-md auth-banner auth-banner--error" rounded>
          {{ store.error?.response?.data?.message || t('error.generic') }}
        </q-banner>

        <div class="profile-builder__panel">
          <ProfileBuilderStep1
            v-if="store.currentStep === 1"
            :loading="store.loading"
            @submit="onStep1"
          />
          <ProfileBuilderStep2
            v-else-if="store.currentStep === 2"
            :loading="store.loading"
            @submit="onStep2"
          />
          <ProfileBuilderStep3
            v-else-if="store.currentStep === 3"
            :loading="store.loading"
            @submit="onStep3"
          />
          <ProfileBuilderStep4
            v-else-if="store.currentStep === 4"
            :loading="store.loading"
            @submit="onStep4"
          />
          <ProfileBuilderStep5
            v-else-if="store.currentStep === 5"
            :loading="store.loading"
            @submit="onStep5WithPhoto"
            @skip="onStep5Skip"
          />
        </div>
      </div>
    </div>
  </q-page>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useProfileBuilderStore } from 'src/stores/profileBuilder.store'
import { useAuthStore } from 'src/stores/auth.store'
import { signUpload, confirmUpload } from 'src/api/marketplace.api'
import ProfileBuilderStep1 from 'src/components/profileBuilder/ProfileBuilderStep1.vue'
import ProfileBuilderStep2 from 'src/components/profileBuilder/ProfileBuilderStep2.vue'
import ProfileBuilderStep3 from 'src/components/profileBuilder/ProfileBuilderStep3.vue'
import ProfileBuilderStep4 from 'src/components/profileBuilder/ProfileBuilderStep4.vue'
import ProfileBuilderStep5 from 'src/components/profileBuilder/ProfileBuilderStep5.vue'

const { t } = useI18n()
const router = useRouter()
const store = useProfileBuilderStore()
const authStore = useAuthStore()

const steps = [
  { n: 1, icon: 'person', titleKey: 'auth.coach.step1Title', shortKey: 'auth.coach.step1Short' },
  {
    n: 2,
    icon: 'sports_soccer',
    titleKey: 'auth.coach.step2Title',
    shortKey: 'auth.coach.step2Short',
  },
  { n: 3, icon: 'euro', titleKey: 'auth.coach.step3Title', shortKey: 'auth.coach.step3Short' },
  { n: 4, icon: 'schedule', titleKey: 'auth.coach.step4Title', shortKey: 'auth.coach.step4Short' },
  {
    n: 5,
    icon: 'photo_camera',
    titleKey: 'auth.coach.step5Title',
    shortKey: 'auth.coach.step5Short',
  },
]

const currentStep = computed(() => steps[store.currentStep - 1] ?? steps[0])

function goToStep(n) {
  if (n <= store.lastCompletedStep + 1) {
    store.currentStep = n
  }
}

onMounted(async () => {
  await store.loadStatus()
  if (store.isComplete) {
    router.push('/coach/command-center')
  }
})

async function onStep1(data) {
  await store.submitStep(1, data)
}

async function onStep2(data) {
  await store.submitStep(2, data)
}

async function onStep3(data) {
  await store.submitStep(3, data)
}

async function onStep4(data) {
  await store.submitStep(4, data)
}

async function onStep5WithPhoto(file) {
  const userId = authStore.userId
  const extension = file.name.split('.').pop().toLowerCase()
  const contentType = file.type

  const signRes = await signUpload({
    entity: 'coach_profile',
    entityId: String(userId),
    contentType,
    extension,
    fileSizeBytes: file.size,
  })

  const { key, uploadUrl } = signRes
  await fetch(uploadUrl, {
    method: 'PUT',
    body: file,
    headers: { 'Content-Type': contentType },
  })

  const confirmRes = await confirmUpload(key, {
    contentType,
    fileSizeBytes: file.size,
  })

  const photoUrl = confirmRes?.key || key
  await store.submitStep(5, { photoUrl })
  await publishAndRedirect()
}

async function onStep5Skip() {
  await store.submitStep(5, { photoUrl: null })
  await publishAndRedirect()
}

async function publishAndRedirect() {
  await store.finishAndPublish()
  router.push('/coach/command-center')
}
</script>

<style lang="scss" scoped>
.auth-brand {
  text-align: center;
}
.auth-brand-name {
  font-size: 32px;
  font-weight: 800;
  font-family: 'Inter', sans-serif;
  letter-spacing: -1px;
}
.auth-card {
  padding: 32px;
}
.auth-banner {
  border-radius: 12px !important;
  font-size: 14px;
  &--error {
    background: rgba(255, 95, 122, 0.12) !important;
    color: var(--accent-danger) !important;
  }
}

.profile-builder__rail {
  display: flex;
  align-items: flex-start;
}

.profile-builder__rail-step {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  min-width: 64px;

  &--reachable {
    cursor: pointer;
  }
}

.profile-builder__rail-dot {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--surface-glass);
  border: 1px solid var(--border-medium);
  color: var(--text-muted);
  transition:
    background-color 0.2s ease,
    border-color 0.2s ease,
    color 0.2s ease,
    box-shadow 0.2s ease;
}

.profile-builder__rail-step--done .profile-builder__rail-dot {
  background: var(--badge-bg);
  border-color: var(--border-accent);
  color: var(--accent-primary);
}

.profile-builder__rail-step--active .profile-builder__rail-dot {
  background: var(--hero-gradient);
  border-color: transparent;
  color: var(--text-on-accent);
  box-shadow: 0 0 0 4px var(--input-focus-shadow);
}

.profile-builder__rail-label {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.4px;
  color: var(--text-muted);
  text-align: center;
  white-space: nowrap;
}

.profile-builder__rail-step--active .profile-builder__rail-label,
.profile-builder__rail-step--done .profile-builder__rail-label {
  color: var(--text-primary);
}

.profile-builder__rail-connector {
  flex: 1;
  height: 2px;
  background: var(--border-soft);
  margin-top: 19px;
  transition: background 0.2s ease;

  &--done {
    background: var(--accent-primary);
    opacity: 0.5;
  }
}

@media (max-width: 600px) {
  .profile-builder__rail-label {
    display: none;
  }
  .profile-builder__rail-dot {
    width: 32px;
    height: 32px;
  }
  .profile-builder__rail-connector {
    margin-top: 15px;
  }
}
</style>
