// Pitchside X-09: the error state a page shows when its main data call failed.

import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import LoadErrorBanner from 'src/components/common/LoadErrorBanner.vue'

describe('LoadErrorBanner.vue', () => {
  it('shows the given message, or the generic one, and emits retry', async () => {
    const generic = mount(LoadErrorBanner)
    expect(generic.find('[data-testid="load-error"]').text()).toContain('common.loadFailed')

    const specific = mount(LoadErrorBanner, { props: { message: 'Bookings could not be loaded.' } })
    expect(specific.text()).toContain('Bookings could not be loaded.')
    await specific.find('[data-testid="load-retry"]').trigger('click')
    expect(specific.emitted('retry')).toHaveLength(1)
  })
})
