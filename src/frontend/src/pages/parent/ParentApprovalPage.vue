<template>
  <q-page class="q-pa-md">
    <div class="text-h5 q-mb-md">{{ t('video.approval.pendingApprovals') }}</div>

    <div v-if="loading" class="q-gutter-md">
      <q-skeleton v-for="i in 3" :key="i" type="QCard" height="120px" />
    </div>

    <div v-else-if="approvals.length === 0" class="text-secondary">
      {{ t('video.approval.noPendingApprovals') }}
    </div>

    <div v-else class="q-gutter-md">
      <q-card
        v-for="approval in approvals"
        :key="approval.id"
        class="glass-card"
      >
        <q-card-section>
          <div class="text-subtitle2">{{ approval.playerName }}</div>
          <div class="text-caption text-secondary">
            {{ approval.videoType }} · {{ formatDate(approval.createdAt) }}
          </div>
        </q-card-section>
        <q-card-actions>
          <q-btn
            flat
            color="positive"
            :label="t('video.approval.approveButton')"
            :loading="actioning === approval.id"
            @click="onApprove(approval)"
          />
          <q-btn
            flat
            color="negative"
            :label="t('video.approval.rejectButton')"
            :loading="actioning === approval.id"
            @click="onReject(approval)"
          />
        </q-card-actions>
      </q-card>
    </div>
  </q-page>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { useRouter } from 'vue-router'
import { videoApi } from 'src/api/video.api'

const { t } = useI18n()
const $q = useQuasar()
const router = useRouter()

const approvals = ref([])
const loading = ref(true)
const actioning = ref(null)

async function fetchApprovals() {
  loading.value = true
  try {
    const { data } = await videoApi.getMyApprovals()
    approvals.value = data
  } catch (err) {
    if (err?.response?.status === 403) {
      router.replace('/parent/dashboard')
    }
  } finally {
    loading.value = false
  }
}

async function onApprove(approval) {
  const confirmed = await new Promise((resolve) => {
    $q.dialog({
      title: t('video.approval.approveButton'),
      message: t('video.approval.confirmApprove'),
      cancel: true,
    }).onOk(() => resolve(true)).onCancel(() => resolve(false))
  })
  if (!confirmed) return

  actioning.value = approval.id
  try {
    await videoApi.approveVideo(approval.id)
    approvals.value = approvals.value.filter((a) => a.id !== approval.id)
    $q.notify({ type: 'positive', message: t('video.approval.approvedNotice') })
  } catch {
    $q.notify({ type: 'negative', message: t('video.approvalAlreadyResolved') })
  } finally {
    actioning.value = null
  }
}

async function onReject(approval) {
  const confirmed = await new Promise((resolve) => {
    $q.dialog({
      title: t('video.approval.rejectButton'),
      message: t('video.approval.confirmReject'),
      cancel: true,
    }).onOk(() => resolve(true)).onCancel(() => resolve(false))
  })
  if (!confirmed) return

  actioning.value = approval.id
  try {
    await videoApi.rejectVideo(approval.id)
    approvals.value = approvals.value.filter((a) => a.id !== approval.id)
    $q.notify({ type: 'negative', message: t('video.approval.rejectedNotice') })
  } catch {
    $q.notify({ type: 'negative', message: t('video.approvalAlreadyResolved') })
  } finally {
    actioning.value = null
  }
}

function formatDate(isoString) {
  if (!isoString) return ''
  return new Date(isoString).toLocaleDateString()
}

onMounted(fetchApprovals)
</script>
