<template>
  <q-page class="q-pa-md">
    <div class="row items-center q-mb-md">
      <q-btn flat round icon="arrow_back" :to="{ name: 'coach-dashboard' }" />
      <div class="text-h6 q-ml-sm">{{ t('session.templates.title') }}</div>
    </div>

    <div v-if="templateStore.loading" class="flex flex-center q-pa-xl">
      <q-spinner-dots size="36px" color="primary" />
    </div>

    <div v-else-if="!templateStore.templates.length" class="text-center q-pa-xl">
      <q-icon name="bookmark_border" size="64px" color="grey-5" />
      <div class="text-h6 q-mt-md">{{ t('session.templates.emptyTitle') }}</div>
      <div class="text-body2 text-secondary q-mt-sm">{{ t('session.templates.emptySubtitle') }}</div>
    </div>

    <q-list v-else separator>
      <q-item v-for="tmpl in templateStore.templates" :key="tmpl.id">
        <q-item-section>
          <q-item-label class="text-weight-medium">{{ tmpl.name }}</q-item-label>
          <q-item-label caption>
            {{ t('session.templates.drillCount', { count: tmpl.drillCount }) }}
            · {{ t('session.templates.deployCount', { count: tmpl.deployCount }) }}
            <template v-if="tmpl.lastDeployedAt">
              · {{ t('session.templates.lastDeployed', { date: formatDate(tmpl.lastDeployedAt) }) }}
            </template>
          </q-item-label>
        </q-item-section>
        <q-item-section side>
          <SessionDNAChart :session-dna="tmpl.sessionDna ?? defaultDna" variant="compact" />
        </q-item-section>
        <q-item-section side>
          <q-btn flat dense round icon="more_vert">
            <q-menu>
              <q-list>
                <q-item clickable v-close-popup @click="startDeploy(tmpl)">
                  <q-item-section>{{ t('session.templates.useThisSession') }}</q-item-section>
                </q-item>
                <q-item clickable v-close-popup @click="startRename(tmpl)">
                  <q-item-section>{{ t('session.templates.rename') }}</q-item-section>
                </q-item>
                <q-item clickable v-close-popup @click="confirmDelete(tmpl)">
                  <q-item-section class="text-negative">{{ t('session.templates.delete') }}</q-item-section>
                </q-item>
              </q-list>
            </q-menu>
          </q-btn>
        </q-item-section>
      </q-item>
    </q-list>

    <!-- Rename dialog -->
    <q-dialog v-model="renameDialog">
      <q-card style="min-width: 320px">
        <q-card-section>
          <div class="text-subtitle1">{{ t('session.templates.nameDialogTitle') }}</div>
        </q-card-section>
        <q-card-section>
          <q-input v-model="renameValue" dense autofocus :label="t('session.templates.nameLabel')" />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn color="primary" :label="t('session.templates.saveAction')" @click="saveRename" />
        </q-card-actions>
      </q-card>
    </q-dialog>

    <!-- Deploy dialog -->
    <q-dialog v-model="deployDialog">
      <q-card style="min-width: 320px">
        <q-card-section>
          <div class="text-subtitle1">{{ t('session.templates.deployDialogTitle') }}</div>
          <div class="text-caption text-secondary q-mt-xs">{{ t('session.templates.deployDialogSubtitle') }}</div>
        </q-card-section>
        <q-card-section>
          <q-input
            v-model="deployBookingId"
            dense
            autofocus
            :label="t('session.templates.deployBookingIdLabel')"
            :error="!!deployError"
            :error-message="deployError"
          />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn
            color="primary"
            :label="t('session.templates.deployAction')"
            :loading="deploying"
            @click="confirmDeploy"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { useSessionTemplateStore } from 'src/stores/sessionTemplate.store'
import SessionDNAChart from 'src/components/booking/SessionDNAChart.vue'

defineOptions({ name: 'SessionTemplateVault' })

const { t } = useI18n()
const $q = useQuasar()
const templateStore = useSessionTemplateStore()

const renameDialog = ref(false)
const renameValue = ref('')
const renamingTemplate = ref(null)
const deployDialog = ref(false)
const deployBookingId = ref('')
const deployingTemplate = ref(null)
const deploying = ref(false)
const deployError = ref('')
const defaultDna = { technical: 0, physical: 0, cognitive: 0, matchRealism: 0, weakFootFocus: 0 }

onMounted(() => templateStore.fetchTemplates())

function formatDate(iso) {
  return new Date(iso).toLocaleDateString()
}

function startRename(tmpl) {
  renamingTemplate.value = tmpl
  renameValue.value = tmpl.name
  renameDialog.value = true
}

async function saveRename() {
  if (!renameValue.value.trim()) return
  try {
    await templateStore.renameTemplate(renamingTemplate.value.id, renameValue.value.trim())
    renameDialog.value = false
    $q.notify({ type: 'positive', message: t('session.templates.renamed') })
  } catch {
    $q.notify({ type: 'negative', message: t('common.errorGeneric') })
  }
}

function confirmDelete(tmpl) {
  $q.dialog({
    title: t('session.templates.delete'),
    message: t('session.templates.confirmDelete'),
    cancel: true,
    persistent: true,
  }).onOk(async () => {
    try {
      await templateStore.deleteTemplate(tmpl.id)
      $q.notify({ type: 'positive', message: t('session.templates.deleted') })
    } catch {
      $q.notify({ type: 'negative', message: t('common.errorGeneric') })
    }
  })
}

function startDeploy(tmpl) {
  deployingTemplate.value = tmpl
  deployBookingId.value = ''
  deployError.value = ''
  deployDialog.value = true
}

const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

async function confirmDeploy() {
  const bookingId = deployBookingId.value.trim()
  if (!bookingId) {
    deployError.value = t('session.templates.deployBookingIdRequired')
    return
  }
  if (!UUID_REGEX.test(bookingId)) {
    deployError.value = t('session.templates.deployBookingIdInvalidFormat')
    return
  }
  deploying.value = true
  deployError.value = ''
  try {
    await templateStore.deployTemplate(deployingTemplate.value.id, bookingId)
    deployDialog.value = false
    $q.notify({ type: 'positive', message: t('session.templates.deployed') })
  } catch (e) {
    const code = e?.response?.data?.helpCode
    if (code === 'SESSION_ALREADY_EXISTS') {
      deployError.value = t('session.templates.deployAlreadyExists')
    } else if (code === 'SESSION_BOOKING_NOT_OWNED') {
      deployError.value = t('session.templates.deployBookingInvalid')
    } else {
      deployError.value = t('common.errorGeneric')
    }
  } finally {
    deploying.value = false
  }
}
</script>
