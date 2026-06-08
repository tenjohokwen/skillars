import { ref, readonly, onMounted, onUnmounted } from 'vue';
import { onLoadingChange } from 'src/boot/axios';

/**
 * Composable for tracking global loading state from axios requests.
 *
 * @returns {{
 *   isLoading: import('vue').Ref<boolean>
 * }}
 */
export function useLoading() {
  // Internal state
  const isLoadingInternal = ref(false);

  // Unsubscribe function holder
  let unsubscribe = null;

  onMounted(() => {
    // Subscribe to loading state changes from axios
    unsubscribe = onLoadingChange((loading) => {
      isLoadingInternal.value = loading;
    });
  });

  onUnmounted(() => {
    // Clean up subscription to prevent memory leaks
    if (unsubscribe) {
      unsubscribe();
    }
  });

  return {
    isLoading: readonly(isLoadingInternal),
  };
}
