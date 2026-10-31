// S-28: the explanation page a limited administrator lands on when opening a full-admin-only page.
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import AdminRequiredPage from 'src/pages/admin/AdminRequiredPage.vue'

describe('AdminRequiredPage.vue (S-28)', () => {
  it('renders the requires-administrator message and a way back', () => {
    const wrapper = mount(AdminRequiredPage)
    expect(wrapper.find('[data-testid="admin-required-title"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="admin-required-message"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="admin-required-back-btn"]').exists()).toBe(true)
  })
})
