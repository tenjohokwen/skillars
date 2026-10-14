<template>
  <q-page class="q-pa-md">
    <div class="coach-reviews-page glass-card q-pa-lg">
      <div class="text-h6 q-mb-md">{{ t('reviews.myReviewsTitle') }}</div>

      <div v-if="loading" class="flex flex-center q-py-xl">
        <q-spinner size="48px" />
      </div>

      <!-- skillars-deferred-151 AC1: a coach with no CoachProfile row yet (reachable mid
           profile-builder) gets a plain 404 from GET /api/reviews/coaches/me — render an
           incomplete-profile state instead of a generic error banner. -->
      <div v-else-if="incompleteProfile" class="text-center q-py-xl q-gutter-md">
        <q-icon name="person_off" size="48px" style="color: var(--text-secondary)" />
        <div class="text-body1" style="color: var(--text-primary)">
          {{ t('reviews.incompleteProfile') }}
        </div>
        <q-btn
          no-caps
          color="primary"
          :label="t('reviews.completeProfile')"
          to="/coach/profile-builder"
        />
      </div>

      <div v-else-if="loadError" class="text-center q-py-xl text-body2 text-negative">
        {{ t('common.errorGeneric') }}
      </div>

      <div v-else-if="!reviews.length" class="text-center q-py-xl text-body2 text-secondary">
        {{ t('marketplace.noReviewsYet') }}
      </div>

      <template v-else>
        <div v-for="review in reviews" :key="review.reviewId" class="review-item q-mb-md q-pb-md">
          <div class="row items-center q-gutter-sm q-mb-xs">
            <q-rating :model-value="review.rating" readonly size="16px" color="amber" />
            <span class="text-caption" style="color: var(--text-secondary)">
              {{ review.authorRole === 'PARENT' ? t('parent.nav') : t('player.nav') }}
              · {{ formatDate(review.createdAt) }}
            </span>
          </div>
          <div v-if="review.body" class="text-body2 q-mb-xs" style="color: var(--text-primary)">
            {{ review.body }}
          </div>

          <!-- Non-APPROVED own-review copy — dedicated coach-perspective copy, not the author-
               perspective reviews.pendingModeration/reviews.blocked the public page uses (review
               Patch: a coach reading a parent/player's review of them must not be told "Your review
               ..."). There is no REJECTED status in ReviewModerationStatus. -->
          <div
            v-if="
              review.moderationStatus === 'PENDING' || review.moderationStatus === 'UNDER_REVIEW'
            "
            class="text-caption q-mb-xs"
            style="color: var(--text-secondary)"
          >
            {{ t('reviews.ownReviewPendingModeration') }}
          </div>
          <div
            v-else-if="review.moderationStatus === 'BLOCKED'"
            class="text-caption text-negative q-mb-xs"
          >
            {{ t('reviews.ownReviewBlocked') }}
          </div>

          <!-- Existing response -->
          <div v-if="review.coachResponseBody?.trim()" class="coach-response q-mt-sm q-pa-sm">
            <div class="text-caption text-weight-bold" style="color: var(--text-secondary)">
              {{ t('reviews.coachResponseLabel') }}
            </div>
            <div class="text-body2" style="color: var(--text-primary)">
              {{ review.coachResponseBody }}
            </div>
          </div>

          <!-- Response composer — only for an APPROVED review with no response yet. -->
          <div v-else-if="review.moderationStatus === 'APPROVED'" class="response-composer q-mt-sm">
            <q-input
              v-model="responseDrafts[review.reviewId]"
              type="textarea"
              dense
              filled
              :label="t('reviews.respondLabel')"
              maxlength="500"
            />
            <div v-if="responseErrors[review.reviewId]" class="text-negative text-caption q-mt-xs">
              {{ responseErrors[review.reviewId] }}
            </div>
            <q-btn
              no-caps
              dense
              flat
              color="primary"
              class="q-mt-xs"
              :label="t('reviews.respondSubmit')"
              :disable="!responseDrafts[review.reviewId]?.trim()"
              :loading="submittingReviewId === review.reviewId"
              @click="submitResponse(review)"
            />
          </div>
        </div>

        <div v-if="loadMoreError" class="text-negative text-caption q-mb-sm">
          {{ t('common.errorGeneric') }}
        </div>
        <q-btn
          v-if="hasNext"
          flat
          dense
          no-caps
          :label="t('reviews.loadMore')"
          :loading="loadingMore"
          @click="loadMore"
        />
      </template>
    </div>
  </q-page>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { listMyReviews, submitCoachResponse } from 'src/api/reviews.api'
import { parseApiError } from 'src/utils/errorHandler'

const { t, locale } = useI18n()

const loading = ref(true)
const loadingMore = ref(false)
const loadError = ref(false)
const loadMoreError = ref(false)
const incompleteProfile = ref(false)

const reviews = ref([])
const currentPage = ref(0)
const hasNext = ref(false)

const responseDrafts = reactive({})
const responseErrors = reactive({})
const submittingReviewId = ref(null)

function applyPage(res, page) {
  reviews.value = page === 0 ? res.reviews : reviews.value.concat(res.reviews)
  hasNext.value = res.hasNext
  currentPage.value = res.page
}

async function fetchReviews(page = 0) {
  const res = await listMyReviews(page)
  applyPage(res, page)
}

onMounted(async () => {
  try {
    await fetchReviews(0)
  } catch (err) {
    if (err.response?.status === 404) {
      incompleteProfile.value = true
    } else {
      loadError.value = true
    }
  } finally {
    loading.value = false
  }
})

async function loadMore() {
  loadingMore.value = true
  loadMoreError.value = false
  try {
    await fetchReviews(currentPage.value + 1)
  } catch {
    loadMoreError.value = true
  } finally {
    loadingMore.value = false
  }
}

async function submitResponse(review) {
  const body = responseDrafts[review.reviewId]?.trim()
  if (!body) return
  submittingReviewId.value = review.reviewId
  responseErrors[review.reviewId] = null
  try {
    await submitCoachResponse(review.reviewId, body)
    review.coachResponseBody = body
    review.coachResponseAt = new Date().toISOString()
    delete responseDrafts[review.reviewId]
  } catch (err) {
    const parsed = parseApiError(err)
    responseErrors[review.reviewId] = parsed.message
  } finally {
    submittingReviewId.value = null
  }
}

function formatDate(isoString) {
  if (!isoString) return ''
  return new Date(isoString).toLocaleDateString(locale.value)
}
</script>

<style lang="scss" scoped>
.coach-reviews-page {
  max-width: 800px;
  margin: 0 auto;
}

.review-item {
  border-bottom: 1px solid var(--border-subtle);
}

.review-item:last-child {
  border-bottom: none;
}

.coach-response {
  border-left: 3px solid var(--border-subtle);
  background: var(--surface-raised);
  border-radius: 6px;
}

.response-composer {
  background: var(--surface-raised);
  border-radius: 6px;
  padding: 8px;
}
</style>
