<template>
  <q-card class="session-block-view glass-card q-mb-md">
    <q-card-section class="session-block-view__header row items-center no-wrap q-py-sm">
      <div class="session-block-view__drag-handle q-mr-sm cursor-grab" data-drag-handle>
        <q-icon name="drag_indicator" size="20px" color="grey-6" />
      </div>

      <q-select
        v-model="localBlockType"
        :options="blockTypeOptions"
        emit-value
        map-options
        dense
        outlined
        class="session-block-view__type-select q-mr-sm"
        style="min-width: 130px"
        @update:model-value="emitMeta"
      />

      <q-input
        v-model="localBlockName"
        dense
        outlined
        :placeholder="t('session.builder.blockNamePlaceholder')"
        class="col q-mr-sm"
        @update:model-value="emitMeta"
      />

      <q-input
        v-model.number="localDuration"
        dense
        outlined
        type="number"
        suffix="min"
        style="width: 80px"
        @update:model-value="emitMeta"
      />

      <q-btn
        flat
        round
        dense
        icon="delete_outline"
        color="negative"
        class="q-ml-sm"
        @click="emit('remove')"
      />
    </q-card-section>

    <q-separator />

    <q-card-section class="q-pa-sm">
      <draggable
        v-model="localDrills"
        group="drills"
        item-key="drillId"
        handle="[data-drill-handle]"
        class="session-block-view__drill-list"
        :class="{ 'session-block-view__drill-list--empty': !localDrills.length }"
        @change="onDrillsChanged"
      >
        <template #item="{ element, index }">
          <div class="session-block-view__drill-row row items-center no-wrap q-py-xs q-px-sm">
            <div data-drill-handle class="cursor-grab q-mr-sm">
              <q-icon name="drag_indicator" size="16px" color="grey-5" />
            </div>
            <div class="col text-body2 ellipsis">{{ element.drill?.name ?? element.drillId }}</div>
            <q-btn
              flat
              round
              dense
              icon="close"
              size="xs"
              color="grey-6"
              @click="removeDrill(index)"
            />
          </div>
        </template>
        <template #footer>
          <div
            v-if="!localDrills.length"
            class="text-caption text-secondary text-center q-pa-md"
          >
            {{ t('session.builder.dropDrillsHere') }}
          </div>
        </template>
      </draggable>
    </q-card-section>

    <q-card-section v-if="props.sluSubtotal !== undefined" class="q-pt-none q-pb-sm">
      <div class="text-caption text-secondary">
        {{ t('session.builder.blockSlu') }}: <strong>{{ props.sluSubtotal }}</strong>
      </div>
    </q-card-section>
  </q-card>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import draggable from 'vuedraggable'

defineOptions({ name: 'SessionBlockView' })

const props = defineProps({
  block: { type: Object, required: true },
  blockIndex: { type: Number, required: true },
  sluSubtotal: { type: Number, default: undefined },
})

const emit = defineEmits(['update:block', 'remove'])

const { t } = useI18n()

const blockTypeOptions = [
  { label: t('session.builder.blockType.WARM_UP'), value: 'WARM_UP' },
  { label: t('session.builder.blockType.TECHNICAL_FOUNDATION'), value: 'TECHNICAL_FOUNDATION' },
  { label: t('session.builder.blockType.GAME_INTENSITY'), value: 'GAME_INTENSITY' },
  { label: t('session.builder.blockType.COOL_DOWN_REVIEW'), value: 'COOL_DOWN_REVIEW' },
]

const localBlockType = ref(props.block.blockType)
const localBlockName = ref(props.block.blockName)
const localDuration = ref(props.block.durationMinutes)
const localDrills = ref([...(props.block.drills ?? [])])

watch(() => props.block, (b) => {
  localBlockType.value = b.blockType
  localBlockName.value = b.blockName
  localDuration.value = b.durationMinutes
  localDrills.value = [...(b.drills ?? [])]
}, { deep: true })

function emitMeta() {
  emit('update:block', {
    ...props.block,
    blockType: localBlockType.value,
    blockName: localBlockName.value,
    durationMinutes: localDuration.value,
    drills: localDrills.value,
  })
}

function onDrillsChanged() {
  emit('update:block', {
    ...props.block,
    blockType: localBlockType.value,
    blockName: localBlockName.value,
    durationMinutes: localDuration.value,
    drills: localDrills.value,
  })
}

function removeDrill(index) {
  localDrills.value.splice(index, 1)
  onDrillsChanged()
}
</script>

<style scoped lang="scss">
.session-block-view {
  border: 1px solid var(--border-subtle, rgba(255, 255, 255, 0.1));

  &__header {
    gap: 4px;
  }

  &__drill-list {
    min-height: 48px;

    &--empty {
      border: 1px dashed var(--border-subtle, rgba(255, 255, 255, 0.2));
      border-radius: 6px;
    }
  }

  &__drill-row {
    border-radius: 4px;
    &:hover {
      background: var(--glass-surface, rgba(255, 255, 255, 0.05));
    }
  }
}
</style>
