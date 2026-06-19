<template>
  <q-card class="drill-card glass-card" @click.self="emit('open-detail')">
    <!-- Video / Thumbnail area -->
    <div class="drill-card__media" @click="emit('open-detail')">
      <video
        v-if="drill.hasVideo && drill.videoUrl && !prefersReducedMotion"
        autoplay
        muted
        loop
        playsinline
        class="drill-card__video"
        :src="drill.videoUrl"
      />
      <div v-else-if="drill.hasVideo" class="drill-card__thumbnail">
        <q-icon name="play_circle" size="48px" class="drill-card__play-icon" />
      </div>
      <div v-else class="drill-card__no-video">
        <q-icon name="sports_soccer" size="48px" />
      </div>
    </div>

    <q-card-section class="drill-card__content">
      <!-- Header row: name + badges -->
      <div class="drill-card__header">
        <div class="text-subtitle1 text-weight-bold drill-card__name">{{ drill.name }}</div>
        <div class="drill-card__badges row items-center q-gutter-xs">
          <q-badge :color="difficultyColor" class="drill-card__difficulty">
            {{ drill.metadata?.difficultyTier }}
          </q-badge>
          <q-badge v-if="drill.addressesNeglectedSkill" color="warning">
            {{ t('development.neglectedSkillTag') }}
          </q-badge>
          <q-icon v-if="drill.metadata?.weakFootBias" name="swap_horiz" size="18px" color="amber">
            <q-tooltip>{{ t('session.drillLibrary.weakFootLabel') }}</q-tooltip>
          </q-icon>
        </div>
      </div>

      <!-- SLU estimate -->
      <div class="drill-card__slu text-caption text-secondary q-mt-xs">
        {{ t('session.drillLibrary.sluEstimate', { count: sluEstimate }) }}
      </div>

      <!-- Equipment icons -->
      <div class="drill-card__equipment row items-center q-gutter-xs q-mt-xs">
        <q-icon
          v-for="eq in drill.metadata?.equipmentRequired"
          :key="eq"
          :name="equipmentIcon(eq)"
          size="16px"
        >
          <q-tooltip>{{ eq }}</q-tooltip>
        </q-icon>
      </div>

      <!-- Coaching points -->
      <ul class="drill-card__points q-mt-sm">
        <li v-for="(point, idx) in visibleCoachingPoints" :key="idx" class="text-caption">
          {{ point }}
        </li>
        <li v-if="extraPointsCount > 0" class="text-caption text-grey">
          +{{ extraPointsCount }} more
        </li>
      </ul>

      <!-- Tags (private drills only) -->
      <div v-if="drill.libraryType === 'COACH'" class="drill-card__tags q-mt-sm">
        <q-chip
          v-for="tag in drill.tags"
          :key="tag"
          removable
          size="sm"
          @remove="handleRemoveTag(tag)"
        >
          {{ tag }}
        </q-chip>

        <template v-if="!showTagInput">
          <q-btn
            flat
            dense
            size="sm"
            :label="t('session.drillLibrary.addTag')"
            icon="add"
            @click="openTagInput"
          />
        </template>
        <template v-else>
          <q-input
            v-model="tagInputValue"
            :placeholder="t('session.drillLibrary.tagPlaceholder')"
            dense
            outlined
            maxlength="50"
            style="max-width: 180px"
            @focus="loadTagSuggestions"
            @keyup.enter="submitTag"
            @blur="closeTagInput"
          >
            <template #append>
              <q-btn flat dense round icon="check" @mousedown.prevent="submitTag" />
            </template>
          </q-input>
          <q-menu v-if="sessionStore.tagSuggestions.length" v-model="tagMenuOpen" no-parent-event>
            <q-list dense>
              <q-item
                v-for="suggestion in sessionStore.tagSuggestions"
                :key="suggestion"
                clickable
                @click="selectSuggestion(suggestion)"
              >
                <q-item-section>{{ suggestion }}</q-item-section>
              </q-item>
            </q-list>
          </q-menu>
        </template>
      </div>

      <!-- Clone indicator for PLATFORM drills — not shown in locker-room context -->
      <div v-if="drill.libraryType === 'PLATFORM' && context !== 'locker-room'" class="drill-card__clone-row q-mt-sm">
        <template v-if="drill.isClonedByMe">
          <q-badge color="positive" class="q-mr-sm">
            {{ t('session.drillLibrary.inYourLibrary') }}
          </q-badge>
          <q-btn
            flat
            dense
            size="sm"
            :label="t('session.drillLibrary.editClone')"
            @click.stop="emit('edit-clone', drill.cloneId)"
          />
        </template>
        <template v-else>
          <q-btn
            flat
            dense
            size="sm"
            :label="t('session.drillLibrary.cloneButton')"
            icon="content_copy"
            @click.stop="emit('clone', drill.id)"
          />
        </template>
      </div>

      <!-- Primary action -->
      <div class="drill-card__actions q-mt-sm">
        <q-btn
          v-if="context === 'session-builder'"
          color="primary"
          :label="t('session.drillLibrary.addToSession')"
          @click.stop="emit('add-to-session', drill)"
        />
        <q-btn
          v-else-if="context === 'homework'"
          color="secondary"
          :label="t('session.drillLibrary.assign')"
          @click.stop="emit('assign', drill)"
        />
      </div>
    </q-card-section>
  </q-card>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { useSessionStore } from 'src/stores/session.store'

const props = defineProps({
  drill: { type: Object, required: true },
  context: { type: String, default: 'library' },
})

const emit = defineEmits(['open-detail', 'clone', 'edit-clone', 'add-to-session', 'assign'])

const { t } = useI18n()
const $q = useQuasar()
const sessionStore = useSessionStore()

// Reduced motion — reactive, updated when OS preference changes
const prefersReducedMotion = ref(false)
let _mql
const _mqlHandler = (e) => {
  prefersReducedMotion.value = e.matches
}
onMounted(() => {
  _mql = window.matchMedia('(prefers-reduced-motion: reduce)')
  prefersReducedMotion.value = _mql.matches
  _mql.addEventListener('change', _mqlHandler)
})
onUnmounted(() => _mql?.removeEventListener('change', _mqlHandler))

// SLU estimate: Math.round(repDensity * sum(skillWeighting values) / 100)
const sluEstimate = computed(() => {
  const meta = props.drill.metadata
  if (!meta?.skillWeighting || !meta?.repDensity) return 0
  const totalWeight = Object.values(meta.skillWeighting).reduce((a, b) => a + b, 0)
  return Math.round((meta.repDensity * totalWeight) / 100)
})

const difficultyColor = computed(() => {
  const tier = props.drill.metadata?.difficultyTier
  if (tier === 'U8' || tier === 'U10') return 'positive'
  if (tier === 'U12' || tier === 'U14') return 'amber'
  return 'negative'
})

const visibleCoachingPoints = computed(() => {
  return (props.drill.metadata?.coachingPoints ?? []).slice(0, 4)
})

const extraPointsCount = computed(() => {
  const total = props.drill.metadata?.coachingPoints?.length ?? 0
  return Math.max(0, total - 4)
})

function equipmentIcon(eq) {
  const map = {
    ball: 'sports_soccer',
    cones: 'change_history',
    bibs: 'checkroom',
    goals: 'sports',
    poles: 'linear_scale',
  }
  return map[eq] ?? 'fitness_center'
}

// Tag input state
const showTagInput = ref(false)
const tagInputValue = ref('')
const tagMenuOpen = ref(false)

function openTagInput() {
  showTagInput.value = true
  tagMenuOpen.value = false
}

function closeTagInput() {
  tagMenuOpen.value = false
  setTimeout(() => {
    showTagInput.value = false
    tagInputValue.value = ''
  }, 200)
}

async function loadTagSuggestions() {
  if (!sessionStore.tagSuggestions.length) {
    await sessionStore.fetchTagSuggestions()
  }
  if (sessionStore.tagSuggestions.length) {
    tagMenuOpen.value = true
  }
}

async function handleRemoveTag(tag) {
  await sessionStore.removeTag(props.drill.id, tag)
  if (sessionStore.error) {
    $q.notify({ message: sessionStore.error, color: 'negative', icon: 'error' })
  }
}

async function submitTag() {
  const val = tagInputValue.value.trim()
  if (val) {
    await sessionStore.addTag(props.drill.id, val)
    if (sessionStore.error) {
      $q.notify({ message: sessionStore.error, color: 'negative', icon: 'error' })
    }
  }
  showTagInput.value = false
  tagInputValue.value = ''
  tagMenuOpen.value = false
}

async function selectSuggestion(suggestion) {
  tagInputValue.value = suggestion
  tagMenuOpen.value = false
  await submitTag()
}
</script>

<style scoped lang="scss">
.drill-card {
  cursor: pointer;

  &__media {
    position: relative;
    height: 140px;
    background: var(--glass-surface);
    overflow: hidden;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  &__video {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  &__thumbnail,
  &__no-video {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 100%;
    height: 100%;
    color: var(--color-text-secondary);
  }

  &__play-icon {
    color: var(--color-text-primary);
    opacity: 0.8;
  }

  &__header {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 8px;
  }

  &__name {
    flex: 1;
    color: var(--color-text-primary);
  }

  &__badges {
    flex-shrink: 0;
  }

  &__slu {
    color: var(--color-text-secondary);
  }

  &__points {
    margin: 0;
    padding-left: 16px;
    color: var(--color-text-secondary);

    li {
      margin-bottom: 2px;
    }
  }

  &__tags {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    align-items: center;
  }

  &__clone-row {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 4px;
  }

  &__actions {
    display: flex;
    justify-content: flex-end;
  }
}
</style>
