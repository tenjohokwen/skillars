<template>
  <div v-if="narrative.length" class="slu-narrative-summary q-mb-md">
    <p v-for="(item, idx) in narrative" :key="idx" class="narrative-item">
      {{ $t(item.key, item.params) }}
    </p>
  </div>
</template>

<script setup>
defineProps({
  narrative: { type: Array, default: () => [] },
})
</script>
