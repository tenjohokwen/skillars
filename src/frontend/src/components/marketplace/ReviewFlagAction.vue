<template>
  <span>
    <q-btn
      flat
      dense
      round
      icon="flag"
      size="sm"
      data-testid="review-flag-btn"
      :aria-label="t('reviews.flagAction')"
      @click="openDialog"
    />

    <q-dialog v-model="dialogOpen">
      <q-card
        data-testid="review-flag-dialog"
        style="min-width: 320px; max-width: 480px; width: 100%"
      >
        <q-card-section>
          <div class="text-h6">{{ t('reviews.flagDialogTitle') }}</div>
        </q-card-section>
        <q-card-section>
          <q-select
            v-model="reason"
            data-testid="review-flag-reason-select"
            :options="reasonOptions"
            :label="t('reviews.flagReasonLabel')"
            emit-value
            map-options
            outlined
            dense
            class="q-mb-md"
          />
          <q-input
            v-model="details"
            data-testid="review-flag-details-input"
            type="textarea"
            outlined
            autogrow
            maxlength="500"
            counter
            :label="t('reviews.flagDetailsLabel')"
          />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn
            unelevated
            color="primary"
            data-testid="review-flag-submit-btn"
            :label="t('reviews.flagSubmit')"
            :loading="submitting"
            :disable="!reason"
            @click="submitFlag"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </span>
</template>

<script setup>
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { flagReview } from 'src/api/reviews.api'

const props = defineProps({
  reviewId: { type: String, required: true },
})

const { t } = useI18n()
const $q = useQuasar()

const dialogOpen = ref(false)
const reason = ref(null)
const details = ref('')
const submitting = ref(false)

const reasonOptions = [
  { label: t('reviews.flagReason.FAKE_REVIEW'), value: 'FAKE_REVIEW' },
  { label: t('reviews.flagReason.OFFENSIVE_CONTENT'), value: 'OFFENSIVE_CONTENT' },
  { label: t('reviews.flagReason.CONFLICT_OF_INTEREST'), value: 'CONFLICT_OF_INTEREST' },
  { label: t('reviews.flagReason.OTHER'), value: 'OTHER' },
]

function openDialog() {
  reason.value = null
  details.value = ''
  dialogOpen.value = true
}

async function submitFlag() {
  submitting.value = true
  try {
    await flagReview(props.reviewId, { reason: reason.value, details: details.value })
    dialogOpen.value = false
    $q.notify({ message: t('reviews.flagSuccess'), type: 'positive' })
  } catch (err) {
    // OperationNotAllowedException (403): ReviewErrorCode's errorKey carries the real reason
    // directly (no generic.requestError indirection — unlike the raw ResponseStatusException
    // pattern DisputeAction.vue's submitDispute uses).
    const errorKey = err?.response?.data?.errorMsg?.errorKey
    if (errorKey === 'reviews.alreadyFlagged') {
      $q.notify({ message: t('reviews.alreadyFlagged'), type: 'negative' })
    } else if (errorKey === 'reviews.cannotFlagOwnReview') {
      $q.notify({ message: t('reviews.cannotFlagOwnReview'), type: 'negative' })
    } else if (errorKey === 'reviews.cannotFlagOwnCoachedReview') {
      $q.notify({ message: t('reviews.cannotFlagOwnCoachedReview'), type: 'negative' })
    } else {
      $q.notify({ message: t('reviews.flagFailed'), type: 'negative' })
    }
  } finally {
    submitting.value = false
  }
}
</script>
