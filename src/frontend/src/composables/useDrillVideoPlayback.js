import { ref, watch, onBeforeUnmount } from 'vue'
import { useQuasar } from 'quasar'
import { useI18n } from 'vue-i18n'

/**
 * skillars-deferred-99 AC14 — drill-video load-failure UX.
 *
 * Before this, a `<video>` load failure (expired signed URL, network) triggered a silent
 * background refetch and left the element visibly broken with no feedback. This composable makes it:
 *
 *   1. first failure  -> retry ONCE (emit `video-error`, which the parent page handles by
 *      refetching the drill list for a fresh signed URL; a `:key` on `videoUrl` reloads the
 *      element). Silent — no toast.
 *   2. second failure -> stop. Show a dismissible error toast (once per drill id, process-wide)
 *      and expose `videoFailed` so the caller can render an inline "unavailable" panel instead of
 *      the broken element.
 *   3. never loops.
 *
 * Navigation-away safe: an `active` flag set false in `onBeforeUnmount` stops a late `@error`
 * (from a retry whose load resolves after the user left) from firing a toast on a page they no
 * longer see.
 *
 * @param {() => (string|number|null|undefined)} drillIdGetter reactive getter for the current drill id
 * @param {(event: string, ...args: any[]) => void} emit the component's emit function
 */
const toastedDrillIds = new Set()

export function useDrillVideoPlayback(drillIdGetter, emit) {
  const $q = useQuasar()
  const { t } = useI18n()

  const videoRetried = ref(false)
  const videoFailed = ref(false)
  let active = true

  // The panel/card is reused as the selected drill changes without a remount, so per-drill state
  // must reset or one drill's failure would suppress recovery for every drill viewed afterward.
  watch(drillIdGetter, () => {
    videoRetried.value = false
    videoFailed.value = false
  })

  onBeforeUnmount(() => {
    active = false
  })

  function handleVideoError() {
    if (!active || videoFailed.value) return

    if (!videoRetried.value) {
      videoRetried.value = true
      emit('video-error')
      return
    }

    videoFailed.value = true
    const id = drillIdGetter()
    if (id != null && !toastedDrillIds.has(id)) {
      toastedDrillIds.add(id)
      $q.notify({ type: 'negative', message: t('session.drillLibrary.videoUnavailable') })
    }
    emit('video-load-failed', id)
  }

  return { videoRetried, videoFailed, handleVideoError }
}
