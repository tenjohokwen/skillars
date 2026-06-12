<template>
  <q-page class="auth-page">
    <div class="auth-card-container fade-in">
      <div class="glass-card--static auth-card">
        <div class="text-section-title q-mb-xs">{{ t('auth.coach.profileBuilderTitle') }}</div>
        <div class="text-meta q-mb-lg">{{ t('auth.coach.profileBuilderBody') }}</div>

        <q-banner v-if="store.error" class="q-mb-md auth-banner auth-banner--error" rounded>
          {{ store.error?.response?.data?.message || t('error.generic') }}
        </q-banner>

        <q-stepper
          v-model="store.currentStep"
          color="primary"
          animated
          flat
          header-nav
        >
          <q-step :name="1" :title="t('auth.coach.step1Title')" icon="person" :done="store.lastCompletedStep >= 1">
            <ProfileBuilderStep1 :loading="store.loading" @submit="onStep1" />
          </q-step>

          <q-step :name="2" :title="t('auth.coach.step2Title')" icon="sports_soccer" :done="store.lastCompletedStep >= 2">
            <ProfileBuilderStep2 :loading="store.loading" @submit="onStep2" />
          </q-step>

          <q-step :name="3" :title="t('auth.coach.step3Title')" icon="euro" :done="store.lastCompletedStep >= 3">
            <ProfileBuilderStep3 :loading="store.loading" @submit="onStep3" />
          </q-step>

          <q-step :name="4" :title="t('auth.coach.step4Title')" icon="schedule" :done="store.lastCompletedStep >= 4">
            <ProfileBuilderStep4 :loading="store.loading" @submit="onStep4" />
          </q-step>

          <q-step :name="5" :title="t('auth.coach.step5Title')" icon="photo_camera" :done="store.lastCompletedStep >= 5">
            <ProfileBuilderStep5
              :loading="store.loading"
              @submit="onStep5WithPhoto"
              @skip="onStep5Skip"
            />
          </q-step>
        </q-stepper>
      </div>
    </div>
  </q-page>
</template>

<script setup>
import { onMounted } from 'vue'
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

  const { key, uploadUrl } = signRes.data
  await fetch(uploadUrl, {
    method: 'PUT',
    body: file,
    headers: { 'Content-Type': contentType },
  })

  const confirmRes = await confirmUpload(key, {
    contentType,
    fileSizeBytes: file.size,
  })

  const photoUrl = confirmRes.data?.key || key
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
.auth-card { padding: 32px; }
</style>
