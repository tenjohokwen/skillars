<template>
  <q-page class="session-builder-page">
    <!-- Scout gate overlay -->
    <div v-if="builderStore.isGated" class="session-builder-page__gate flex flex-center">
      <div class="text-center q-pa-xl">
        <q-icon name="lock" size="64px" color="grey-5" />
        <div class="text-h6 q-mt-md">{{ t('session.builder.gatedTitle') }}</div>
        <div class="text-body2 text-secondary q-mt-sm">{{ t('session.builder.gatedSubtitle') }}</div>
      </div>
    </div>

    <template v-else>
      <!-- Header -->
      <div class="session-builder-page__header row items-center q-px-md q-py-sm">
        <q-btn flat round icon="arrow_back" @click="handleBack" />
        <div class="text-h6 q-ml-sm">{{ t('session.builder.title') }}</div>
        <q-space />
        <q-btn
          flat
          icon="bookmark_border"
          :label="t('session.templates.browseTemplates')"
          :to="{ name: 'coach-session-templates' }"
          class="q-mr-xs"
        />
        <q-btn
          v-if="builderStore.sessionId && !builderStore.isGated"
          flat
          icon="bookmark_add"
          :label="t('session.templates.saveAsTemplate')"
          :disable="builderStore.saving"
          class="q-mr-sm"
          @click="saveAsTemplateDialog = true"
        />
        <q-btn
          outline
          :label="t('session.builder.saveDraft')"
          :loading="builderStore.saving"
          :disable="builderStore.developmentFocus.length === 0"
          class="q-mr-sm"
          @click="save('DRAFT')"
        />
        <q-btn
          unelevated
          color="primary"
          :label="t('session.builder.saveSession')"
          :loading="builderStore.saving"
          :disable="builderStore.developmentFocus.length === 0"
          @click="save('SAVED')"
        />
      </div>

      <q-separator />

      <!-- 3-column layout -->
      <div class="session-builder-page__body row no-wrap">
        <!-- Col 1: Drill Library -->
        <div class="session-builder-page__col-library q-pa-md">
          <div class="text-subtitle1 q-mb-sm">{{ t('session.drillLibrary.title') }}</div>

          <q-tabs v-model="selectedLibrary" dense class="q-mb-sm" @update:model-value="fetchDrills">
            <q-tab name="PLATFORM" :label="t('session.drillLibrary.platformTab')" />
            <q-tab name="PRIVATE" :label="t('session.drillLibrary.myLibraryTab')" />
            <q-tab name="SUGGESTED" :label="t('session.suggestions.tabLabel')" />
          </q-tabs>

          <DrillSuggestionPanel
            v-if="selectedLibrary === 'SUGGESTED'"
            :suggestions="builderStore.suggestedDrills"
            :loading="builderStore.suggestionsLoading"
            :is-personalized="builderStore.developmentFocus.length > 0"
            @close="selectedLibrary = 'PLATFORM'"
            @add-drill="addDrillToActiveBlock"
            @open-detail="openDrillDetail"
          />

          <template v-else>
            <q-input
              v-model="drillSearch"
              dense
              outlined
              clearable
              :placeholder="t('session.drillLibrary.searchPlaceholder')"
              class="q-mb-sm"
              @update:model-value="fetchDrills"
            >
              <template #prepend><q-icon name="search" /></template>
            </q-input>

            <div v-if="sessionStore.loading" class="flex flex-center q-pa-lg">
              <q-spinner-dots size="36px" color="primary" />
            </div>

            <div v-else class="session-builder-page__drill-list">
              <DrillCard
                v-for="drill in sessionStore.drills"
                :key="drill.id"
                :drill="drill"
                context="session-builder"
                @open-detail="openDrillDetail(drill)"
                @add-to-session="addDrillToActiveBlock(drill)"
              />
            </div>
          </template>
        </div>

        <!-- Col 2: Session Blocks -->
        <div class="session-builder-page__col-blocks q-pa-md col">
          <div class="row items-center q-mb-sm">
            <div class="text-subtitle1">{{ t('session.builder.blocks') }}</div>
            <q-space />
            <q-btn
              v-if="builderStore.blocks.length < 4"
              flat
              dense
              icon="add"
              :label="t('session.builder.addBlock')"
              @click="addBlock"
            />
          </div>

          <!-- Template provenance banner (AC 8) -->
          <div
            v-if="builderStore.sourceTemplateName && !builderStore.templateBannerDismissed"
            class="session-builder-page__template-banner row items-center q-mb-sm q-pa-sm"
          >
            <q-icon name="bookmark" color="primary" class="q-mr-sm" />
            <router-link
              :to="{ name: 'coach-session-templates' }"
              class="text-caption col text-primary"
              style="text-decoration: none"
            >
              {{ t('session.templates.templateIndicator', { name: builderStore.sourceTemplateName }) }}
            </router-link>
            <q-btn flat dense round icon="close" size="xs"
                   @click="builderStore.templateBannerDismissed = true" />
          </div>

          <draggable
            v-model="builderStore.blocks"
            item-key="_uid"
            handle="[data-drag-handle]"
          >
            <template #item="{ element, index }">
              <div
                class="cursor-pointer"
                :class="{ 'session-builder-page__block--active': builderStore.activeBlockIndex === index }"
                @click="builderStore.setActiveBlock(index)"
              >
                <SessionBlockView
                  :block="element"
                  :block-index="index"
                  :slu-subtotal="builderStore.blockSlu(element)"
                  @update:block="(b) => (builderStore.blocks[index] = b)"
                  @remove="removeBlock(index)"
                />
              </div>
            </template>
          </draggable>

          <div v-if="builderStore.blocks.length === 0" class="text-secondary text-body2 text-center q-pa-xl">
            {{ t('session.builder.noBlocks') }}
          </div>
        </div>

        <!-- Col 3: DNA / Focus / Equipment sidebar (tabbed) -->
        <div class="session-builder-page__col-sidebar">
          <q-tabs v-model="sidebarTab" dense align="left" class="q-px-sm">
            <q-tab name="dna" :label="t('session.builder.sessionDna')" />
            <q-tab name="focus" :label="t('session.builder.developmentFocus')" />
            <q-tab name="equipment" :label="t('session.builder.equipment')" />
          </q-tabs>
          <q-separator />
          <q-tab-panels v-model="sidebarTab" animated class="q-pa-md">
            <q-tab-panel name="dna" class="q-pa-none">
              <SessionDNAChart :session-dna="builderStore.sessionDna" variant="full" :highlight-axes="highlightAxes" />
            </q-tab-panel>
            <q-tab-panel name="focus" class="q-pa-none">
              <DevelopmentFocusSelector v-model="builderStore.developmentFocus" />
            </q-tab-panel>
            <q-tab-panel name="equipment" class="q-pa-none">
              <div v-if="builderStore.equipmentList.length" class="row q-gutter-xs">
                <q-chip v-for="item in builderStore.equipmentList" :key="item" dense outline color="grey-7">
                  {{ item }}
                </q-chip>
              </div>
              <div v-else class="text-caption text-secondary">
                {{ t('session.builder.noEquipment') }}
              </div>
            </q-tab-panel>
          </q-tab-panels>
        </div>
      </div>
    </template>

    <!-- Drill detail panel -->
    <DrillDetailPanel
      :drill="selectedDrill"
      :is-open="isDrillDetailOpen"
      context="session-builder"
      @close="isDrillDetailOpen = false"
      @add-to-session="onAddToSession"
    />

    <!-- Save as Template dialog -->
    <q-dialog v-model="saveAsTemplateDialog">
      <q-card style="min-width: 320px">
        <q-card-section>
          <div class="text-subtitle1">{{ t('session.templates.nameDialogTitle') }}</div>
        </q-card-section>
        <q-card-section>
          <q-input v-model="templateNameInput" dense autofocus :label="t('session.templates.nameLabel')" />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn color="primary" :label="t('session.templates.saveAction')" @click="saveTemplate" />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import draggable from 'vuedraggable'
import { useSessionStore } from 'src/stores/session.store'
import { useSessionBuilderStore } from 'src/stores/sessionBuilder.store'
import { useSessionTemplateStore } from 'src/stores/sessionTemplate.store'
import SessionBlockView from 'src/components/session/SessionBlockView.vue'
import DevelopmentFocusSelector from 'src/components/session/DevelopmentFocusSelector.vue'
import DrillDetailPanel from 'src/components/session/DrillDetailPanel.vue'
import DrillCard from 'src/components/session/DrillCard.vue'
import DrillSuggestionPanel from 'src/components/session/DrillSuggestionPanel.vue'
import SessionDNAChart from 'src/components/booking/SessionDNAChart.vue'

defineOptions({ name: 'SessionBuilderPage' })

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const $q = useQuasar()
const sessionStore = useSessionStore()
const builderStore = useSessionBuilderStore()
const templateStore = useSessionTemplateStore()

const bookingId = route.params.bookingId
const selectedLibrary = ref('PLATFORM')
const drillSearch = ref('')
const sidebarTab = ref('dna')
const selectedDrill = ref(null)
const isDrillDetailOpen = ref(false)
const saveAsTemplateDialog = ref(false)
const templateNameInput = ref('')
let hasUnsavedChanges = false

const focusAxisMap = {
  technical: 'technical',
  physical: 'physical',
  cognitive: 'cognitive',
  matchRealism: 'matchRealism',
  weakFoot: 'weakFootFocus',
  possession: 'cognitive',
}
const highlightAxes = computed(() =>
  [...new Set(builderStore.developmentFocus.map(f => focusAxisMap[f]).filter(Boolean))]
)

onMounted(async () => {
  await builderStore.fetchExistingPlan(bookingId)
  if (!builderStore.isGated) {
    await fetchDrills()
  }
})

async function fetchDrills() {
  if (selectedLibrary.value === 'SUGGESTED') {
    if (!builderStore.sessionId) {
      $q.notify({ type: 'warning', message: t('session.builder.saveDraftFirst') })
      selectedLibrary.value = 'PLATFORM'
      return
    }
    await builderStore.fetchSuggestions()
    return
  }
  sessionStore.searchQuery = drillSearch.value
  await sessionStore.searchDrills(selectedLibrary.value)
}

function openDrillDetail(drill) {
  selectedDrill.value = drill
  isDrillDetailOpen.value = true
}

function addDrillToActiveBlock(drill) {
  builderStore.addDrillToBlock(builderStore.activeBlockIndex, drill)
  hasUnsavedChanges = true
  $q.notify({ type: 'positive', message: t('session.builder.drillAdded'), timeout: 1500 })
}

async function saveTemplate() {
  if (!templateNameInput.value.trim()) return
  try {
    await templateStore.createTemplate(builderStore.sessionId, templateNameInput.value.trim())
    saveAsTemplateDialog.value = false
    templateNameInput.value = ''
    $q.notify({ type: 'positive', message: t('session.templates.saved') })
  } catch {
    $q.notify({ type: 'negative', message: t('common.errorGeneric') })
  }
}

function onAddToSession(drill) {
  isDrillDetailOpen.value = false
  addDrillToActiveBlock(drill)
}

function addBlock() {
  if (builderStore.blocks.length >= 4) return
  builderStore.blocks.push({
    _uid: Date.now(),
    blockType: 'TECHNICAL_FOUNDATION',
    blockName: t('session.builder.newBlock'),
    durationMinutes: 15,
    drills: [],
  })
  hasUnsavedChanges = true
}

function removeBlock(index) {
  builderStore.blocks.splice(index, 1)
  builderStore.setActiveBlock(Math.min(index, builderStore.blocks.length - 1))
  hasUnsavedChanges = true
}

async function save(status) {
  try {
    await builderStore.savePlan(status)
    hasUnsavedChanges = false
    $q.notify({ type: 'positive', message: t('session.builder.saved') })
  } catch (e) {
    const helpCode = e?.response?.data?.helpCode
    if (helpCode === 'SESSION_PLAN_LOCKED') {
      $q.notify({ type: 'negative', message: t('session.builder.planLocked') })
    } else if (helpCode === 'SESSION_ALREADY_EXISTS') {
      $q.notify({ type: 'negative', message: t('session.builder.planAlreadyExists') })
    } else {
      $q.notify({ type: 'negative', message: t('session.builder.saveFailed') })
    }
  }
}

function handleBack() {
  if (hasUnsavedChanges) {
    $q.dialog({
      title: t('session.builder.unsavedChangesTitle'),
      message: t('session.builder.unsavedChangesMsg'),
      ok: { label: t('common.leave'), color: 'negative' },
      cancel: { label: t('common.stay') },
    }).onOk(() => { hasUnsavedChanges = false; router.back() })
  } else {
    router.back()
  }
}

onBeforeRouteLeave((_to, _from, next) => {
  if (hasUnsavedChanges) {
    $q.dialog({
      title: t('session.builder.unsavedChangesTitle'),
      message: t('session.builder.unsavedChangesMsg'),
      ok: { label: t('common.leave'), color: 'negative' },
      cancel: { label: t('common.stay') },
    })
      .onOk(() => next())
      .onCancel(() => next(false))
  } else {
    next()
  }
})
</script>

<style scoped lang="scss">
.session-builder-page {
  display: flex;
  flex-direction: column;
  height: 100vh;

  &__header {
    height: 56px;
    flex-shrink: 0;
  }

  &__body {
    flex: 1;
    overflow: hidden;
  }

  &__gate {
    position: absolute;
    inset: 0;
    background: var(--surface-page, rgba(0, 0, 0, 0.85));
    z-index: 10;
  }

  &__col-library {
    width: 280px;
    flex-shrink: 0;
    border-right: 1px solid var(--border-subtle, rgba(255, 255, 255, 0.1));
    overflow-y: auto;
    height: 100%;
  }

  &__col-blocks {
    overflow-y: auto;
    height: 100%;
    min-width: 0;
  }

  &__col-sidebar {
    width: 280px;
    flex-shrink: 0;
    border-left: 1px solid var(--border-subtle, rgba(255, 255, 255, 0.1));
    overflow-y: auto;
    height: 100%;
  }

  &__drill-list {
    max-height: calc(100vh - 220px);
    overflow-y: auto;
  }

  &__template-banner {
    border-radius: 6px;
    background: var(--surface-elevated, rgba(139, 92, 246, 0.08));
    border: 1px solid var(--accent-primary, rgba(139, 92, 246, 0.3));
  }

  &__block--active {
    outline: 2px solid var(--accent-primary, #8b5cf6);
    border-radius: 8px;
  }
}
</style>
