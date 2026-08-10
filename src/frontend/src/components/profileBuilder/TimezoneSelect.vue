<template>
  <div>
    <q-select
      :model-value="modelValue"
      @update:model-value="$emit('update:modelValue', $event)"
      :options="filteredOptions"
      :label="t('auth.coach.timezoneLabel')"
      :hint="t('auth.coach.timezoneHint')"
      :loading="loading"
      use-input
      input-debounce="200"
      @filter="filterZones"
      outlined
      dense
      emit-value
      map-options
      class="q-mb-sm"
    >
      <template #no-option>
        <q-item>
          <q-item-section class="text-grey">
            {{
              store.timezonesFailed
                ? t('auth.coach.timezoneLoadFailed')
                : t('auth.coach.timezoneNoMatch')
            }}
          </q-item-section>
        </q-item>
      </template>
    </q-select>

    <!-- The load-failure state needs its own affordance. An empty option list reads as "your search
         matched nothing", so without this the coach sits at a disabled Next button with nothing to
         act on — the same dead end this component exists to remove, just reached a different way. -->
    <q-banner v-if="store.timezonesFailed" class="timezone-hint q-mb-md" rounded dense>
      <template #avatar>
        <q-icon name="error_outline" />
      </template>
      {{ t('auth.coach.timezoneLoadFailed') }}
      <template #action>
        <q-btn flat dense no-caps :label="t('common.retry')" :loading="loading" @click="reload" />
      </template>
    </q-banner>

    <!-- Shown only when the browser reported a zone this server does not know. That is precisely
         the tzdata-lag case that used to make Steps 1 and 4 uncompletable with a validation error
         naming the coach's own machine and no way to override. Now it is an explicit prompt. -->
    <q-banner v-if="showUnknownZoneHint" class="timezone-hint q-mb-md" rounded dense>
      <template #avatar>
        <q-icon name="schedule" />
      </template>
      {{ t('auth.coach.timezoneBrowserUnknown', { zone: browserTimezone }) }}
    </q-banner>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useProfileBuilderStore } from 'src/stores/profileBuilder.store'

const { t } = useI18n()

const props = defineProps({
  modelValue: { type: String, default: null },
})
const emit = defineEmits(['update:modelValue'])

const store = useProfileBuilderStore()
const loading = ref(false)
const filterTerm = ref('')

const browserTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone

const options = computed(() => store.supportedTimezones)

const filteredOptions = computed(() => {
  const needle = filterTerm.value.toLowerCase()
  if (!needle) return options.value
  return options.value.filter((z) => z.toLowerCase().includes(needle))
})

// True once the list has loaded and the browser's own zone is not in it — no point warning about
// an unknown zone while we still have nothing to compare against.
const showUnknownZoneHint = computed(
  () =>
    !props.modelValue &&
    options.value.length > 0 &&
    !options.value.includes(normalizedBrowserZone()),
)

/**
 * A browser reporting the bare string "UTC" (common on Linux and inside containers) maps to
 * "Etc/UTC", which is the one Etc/ entry the server's list carries. Without this a UTC tester would
 * land in the unknown-zone state for no reason.
 */
function normalizedBrowserZone() {
  return browserTimezone === 'UTC' ? 'Etc/UTC' : browserTimezone
}

function filterZones(val, update) {
  update(() => {
    filterTerm.value = val
  })
}

async function reload() {
  // Clear the cached empty list so loadSupportedTimezones actually re-fetches rather than
  // short-circuiting on its own "already loaded" guard.
  store.supportedTimezones = []
  await load()
}

async function load() {
  loading.value = true
  try {
    const zones = await store.loadSupportedTimezones()
    // Preselect only if the SERVER knows the browser's zone. Sending it blind is the original bug:
    // @IanaTimezone validates with ZoneId.of, so a zone newer than the deployed JVM's tzdata 400s
    // on submit with no way for the coach to override.
    if (!props.modelValue) {
      const candidate = normalizedBrowserZone()
      if (zones.includes(candidate)) {
        emit('update:modelValue', candidate)
      }
    }
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style lang="scss" scoped>
.timezone-hint {
  background: var(--surface-warning) !important;
  color: var(--accent-warning) !important;
  border-radius: 8px !important;
  font-size: 13px;
}
</style>
