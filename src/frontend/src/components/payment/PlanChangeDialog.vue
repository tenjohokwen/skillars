<template>
  <!-- Confirms a plan change that takes effect at period end (a paid downgrade or a cancel): says what the
       user keeps until when, and offers a way out that changes nothing. -->
  <q-dialog :model-value="modelValue" @update:model-value="emit('update:modelValue', $event)">
    <q-card class="glass-card" style="min-width: 340px; max-width: 460px" :data-testid="testid">
      <q-card-section>
        <div class="text-h6">{{ title }}</div>
      </q-card-section>
      <q-card-section class="q-pt-none text-body2">{{ body }}</q-card-section>
      <q-card-actions align="right">
        <q-btn
          flat
          :label="dismissLabel"
          :data-testid="`${testid}-dismiss`"
          @click="emit('update:modelValue', false)"
        />
        <q-btn
          :color="confirmColor"
          :label="confirmLabel"
          :loading="loading"
          :data-testid="`${testid}-confirm`"
          @click="emit('confirm')"
        />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>

<script setup>
defineProps({
  modelValue: { type: Boolean, required: true },
  title: { type: String, required: true },
  body: { type: String, required: true },
  confirmLabel: { type: String, required: true },
  dismissLabel: { type: String, required: true },
  confirmColor: { type: String, default: 'primary' },
  loading: { type: Boolean, default: false },
  testid: { type: String, required: true },
})
const emit = defineEmits(['update:modelValue', 'confirm'])
</script>
