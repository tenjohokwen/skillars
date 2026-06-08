<template>
  <q-dialog :model-value="modelValue" persistent @update:model-value="$emit('update:modelValue', $event)">
    <q-card style="min-width: 500px">
      <q-card-section>
        <div class="text-h6">API Key Generated</div>
      </q-card-section>
      <q-card-section>
        <p>This key will not be shown again. Copy it now and store it in a secure location.</p>
        <q-input
          :model-value="rawKey"
          readonly
          outlined
          :input-style="{ fontFamily: 'monospace' }"
        >
          <template #append>
            <q-btn flat icon="content_copy" @click="copyKey" />
          </template>
        </q-input>
      </q-card-section>
      <q-card-section>
        <q-checkbox v-model="copied" label="I have copied the key and stored it safely" />
      </q-card-section>
      <q-card-actions align="right">
        <q-btn color="primary" label="Done" :disable="!copied" @click="dismiss" />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'

const props = defineProps({
  rawKey: { type: String, required: true },
  modelValue: Boolean,
})
const emit = defineEmits(['update:modelValue', 'close'])
const copied = ref(false)

watch(() => props.modelValue, (val) => {
  if (val) copied.value = false
})

function copyKey() {
  navigator.clipboard.writeText(props.rawKey)
}

function dismiss() {
  emit('close')
  emit('update:modelValue', false)
}
</script>
