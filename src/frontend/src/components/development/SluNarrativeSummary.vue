<template>
  <div
    v-if="narrative.length"
    class="slu-narrative-summary q-mb-md"
    data-testid="narrative-summary"
  >
    <p
      v-for="(item, idx) in narrative"
      :key="idx"
      class="narrative-item"
      data-testid="narrative-item"
    >
      {{ $t(item.key, item.params) }}
    </p>
  </div>
</template>

<script setup>
defineProps({
  narrative: { type: Array, default: () => [] },
})
</script>
