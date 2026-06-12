<template>
  <div>
    <q-input
      v-model.number="form.perSessionPrice"
      :label="t('auth.coach.step3PerSessionPrice')"
      outlined
      type="number"
      prefix="€"
      :rules="[v => !!v && v > 0 || t('validation.required')]"
      class="q-mb-md"
    />

    <div class="text-caption q-mb-xs">{{ t('auth.coach.step3SessionPacks') }}</div>
    <div v-for="(pack, i) in form.sessionPacks" :key="i" class="row q-col-gutter-sm q-mb-sm">
      <div class="col-4">
        <q-input
          v-model.number="pack.sessionCount"
          label="Sessions"
          outlined
          type="number"
          dense
        />
      </div>
      <div class="col-4">
        <q-input
          v-model.number="pack.totalPrice"
          label="Price (€)"
          outlined
          type="number"
          dense
        />
      </div>
      <div class="col-3">
        <q-input v-model="pack.label" label="Label" outlined dense />
      </div>
      <div class="col-1 flex items-center">
        <q-btn icon="close" flat dense @click="removePack(i)" />
      </div>
    </div>
    <q-btn
      :label="t('auth.coach.step3AddPack')"
      flat
      size="sm"
      icon="add"
      @click="addPack"
      class="q-mb-md"
    />

    <q-btn
      :label="t('common.next')"
      color="primary"
      @click="submit"
      :loading="loading"
      unelevated
    />
  </div>
</template>

<script setup>
import { reactive } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

defineProps({ loading: Boolean })
const emit = defineEmits(['submit'])

const form = reactive({
  perSessionPrice: null,
  sessionPacks: [],
})

function addPack() {
  form.sessionPacks.push({ sessionCount: null, totalPrice: null, label: '' })
}

function removePack(i) {
  form.sessionPacks.splice(i, 1)
}

function submit() {
  if (!form.perSessionPrice || form.perSessionPrice <= 0) return
  emit('submit', {
    perSessionPrice: form.perSessionPrice,
    sessionPacks: form.sessionPacks.filter(p => p.sessionCount > 0 && p.totalPrice > 0).map(p => ({
      sessionCount: p.sessionCount,
      totalPrice: p.totalPrice,
      label: p.label || null,
    })),
  })
}
</script>
