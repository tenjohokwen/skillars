<template>
  <q-dialog v-model="dialogVisible" persistent>
    <q-card style="min-width: 380px">
      <q-card-section>
        <div class="text-h6">{{ $t('profile.updatePlayerPosition') }}</div>
      </q-card-section>

      <q-card-section>
        <q-form @submit.prevent="handleSubmit">
          <q-select
            v-model="position"
            :options="positionOptions"
            :label="t('auth.player.position')"
            outlined
            emit-value
            map-options
            :rules="[(v) => !!v || t('validation.required')]"
            :error="hasFieldError('position')"
            :error-message="getFieldError('position')"
          />

          <q-banner
            v-if="hasError && !isValidationError"
            class="bg-negative text-white q-mt-md"
            rounded
          >
            {{ errorMessage }}
            <template v-if="helpCode">
              <br />
              <small>{{ $t('error.helpCode') }}: {{ helpCode }}</small>
            </template>
          </q-banner>

          <q-card-actions align="right" class="q-px-none q-pb-none">
            <q-btn type="button" flat :label="$t('common.cancel')" @click="close" />
            <q-btn
              type="submit"
              color="primary"
              :label="$t('common.save')"
              :loading="isSubmitting"
              :disable="!position"
            />
          </q-card-actions>
        </q-form>
      </q-card-section>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { ref, watch, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { playerProfileApi } from 'src/api/playerProfile.api'
import { useErrorHandler } from 'src/composables/useErrorHandler'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  currentPosition: { type: String, default: null },
  // When set, this dialog edits a PARENT-owned child's position (AC5); when absent, it edits the
  // logged-in PLAYER's own position (AC4). Same component, same validation — only the submit target
  // (and therefore the API call) differs, per the story's explicit "reuse, don't duplicate" note.
  playerId: { type: [Number, String], default: null },
})

const emit = defineEmits(['update:modelValue', 'updated'])

const { t } = useI18n()
const $q = useQuasar()
const {
  setError,
  clearError,
  hasError,
  errorMessage,
  isValidationError,
  helpCode,
  hasFieldError,
  getFieldError,
} = useErrorHandler()

const dialogVisible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val),
})

const position = ref(null)
const isSubmitting = ref(false)

const positionOptions = computed(() => [
  { label: t('auth.player.positionGoalkeeper'), value: 'GOALKEEPER' },
  { label: t('auth.player.positionDefender'), value: 'DEFENDER' },
  { label: t('auth.player.positionMidfielder'), value: 'MIDFIELDER' },
  { label: t('auth.player.positionForward'), value: 'FORWARD' },
])

watch(
  () => props.currentPosition,
  (val) => {
    position.value = val || null
  },
  { immediate: true },
)

// skillars-deferred-139 review Patch 7: this dialog is a single shared instance re-used across
// every row (the player's own position, or — via the player-id prop — any one of a parent's
// children), so it is never unmounted between edits. Re-deriving `position` from the current prop
// on every open (not just on prop-value change) guarantees a cancelled, unsaved edit never survives
// into the next time this dialog is opened — including the case where a different row's position
// happens to share the same value as the abandoned edit, which a plain value-change watcher alone
// would miss.
watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      position.value = props.currentPosition || null
      clearError()
    }
  },
)

function close() {
  clearError()
  emit('update:modelValue', false)
}

async function handleSubmit() {
  clearError()
  if (!position.value) return
  isSubmitting.value = true
  try {
    if (props.playerId != null) {
      await playerProfileApi.updateChildPosition(props.playerId, position.value)
    } else {
      await playerProfileApi.updateOwnPosition(position.value)
    }

    $q.notify({ type: 'positive', message: t('success.infoChanged') })
    emit('updated')
    close()
  } catch (err) {
    setError(err)
  } finally {
    isSubmitting.value = false
  }
}
</script>
