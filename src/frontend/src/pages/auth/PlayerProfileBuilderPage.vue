<template>
  <q-page class="auth-page">
    <div class="auth-card-container fade-in">
      <div class="glass-card--static auth-card">
        <div class="text-section-title q-mb-xs">{{ t('auth.player.profileBuilderTitle') }}</div>
        <div class="text-meta q-mb-lg">{{ t('auth.player.profileBuilderBody') }}</div>

        <q-banner v-if="hasError" class="q-mb-md auth-banner auth-banner--error" rounded>
          {{ errorMessage }}
        </q-banner>

        <q-form @submit.prevent="handleSubmit">
          <q-select
            v-model="position"
            :options="positionOptions"
            :label="t('auth.player.position')"
            outlined
            emit-value
            map-options
            :rules="[required]"
          />

          <q-btn
            type="submit"
            class="full-width btn-accent q-mt-lg"
            :label="t('auth.player.finishSetup')"
            :loading="isSubmitting"
            :disable="!position || isSubmitting"
            unelevated
            size="md"
          />
        </q-form>
      </div>
    </div>
  </q-page>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { playerRegistrationApi } from 'src/api/playerRegistration.api'
import { useErrorHandler } from 'src/composables/useErrorHandler'

const router = useRouter()
const { t } = useI18n()
const { setError, clearError, hasError, errorMessage } = useErrorHandler()

const position = ref(null)
const isSubmitting = ref(false)

const positionOptions = computed(() => [
  { label: t('auth.player.positionGoalkeeper'), value: 'GOALKEEPER' },
  { label: t('auth.player.positionDefender'), value: 'DEFENDER' },
  { label: t('auth.player.positionMidfielder'), value: 'MIDFIELDER' },
  { label: t('auth.player.positionForward'), value: 'FORWARD' },
])

const required = (val) => !!val || t('validation.required')

async function handleSubmit() {
  clearError()
  isSubmitting.value = true
  try {
    const profile = await playerRegistrationApi.createProfile({ position: position.value })
    router.push(`/player/locker-room/${profile.id}`)
  } catch (err) {
    setError(err)
  } finally {
    isSubmitting.value = false
  }
}
</script>

<style lang="scss" scoped>
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
</style>
