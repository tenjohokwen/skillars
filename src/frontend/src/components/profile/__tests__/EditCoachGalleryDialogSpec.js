// feat/coach-workflow-gaps D9 — EditCoachGalleryDialog.vue: the coach's public-profile image gallery.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

vi.mock('src/api/marketplace.api', () => ({
  signUpload: vi.fn(),
  confirmUpload: vi.fn(),
  getCoachGallery: vi.fn(),
  addCoachGalleryImage: vi.fn(),
  deleteCoachGalleryItem: vi.fn(),
  deleteStorageObject: vi.fn(),
}))

global.fetch = vi.fn().mockResolvedValue({ ok: true })

const { notifyMock } = vi.hoisted(() => ({ notifyMock: vi.fn() }))
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifyMock }) }
})

import {
  signUpload,
  confirmUpload,
  getCoachGallery,
  addCoachGalleryImage,
  deleteCoachGalleryItem,
  deleteStorageObject,
} from 'src/api/marketplace.api'
import EditCoachGalleryDialog from 'src/components/profile/EditCoachGalleryDialog.vue'

const item = (n) => ({
  id: `m-${n}`,
  fileUrl: `https://cdn/x${n}.jpg`,
  mediaType: 'IMAGE',
  displayOrder: n,
})

const mounted = []
async function mountDialog() {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: { auth: { userId: '42', role: 'COACH' } },
  })
  const wrapper = mount(EditCoachGalleryDialog, {
    props: { modelValue: true },
    global: {
      plugins: [pinia],
      stubs: { QDialog: { template: '<div><slot /></div>' }, 'q-file': true, 'q-img': true },
    },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

describe('EditCoachGalleryDialog.vue (feat/coach-workflow-gaps D9)', () => {
  it('loads and renders the current gallery when opened', async () => {
    getCoachGallery.mockResolvedValue([item(0), item(1)])
    const wrapper = await mountDialog()
    expect(getCoachGallery).toHaveBeenCalledTimes(1)
    expect(wrapper.findAll('[data-test="gallery-item"]')).toHaveLength(2)
  })

  it('uploads through sign → PUT → confirm with the coach_gallery entity, then links the key', async () => {
    getCoachGallery.mockResolvedValue([])
    signUpload.mockResolvedValue({
      key: 'coach_gallery/42/2026/10/a.jpg',
      uploadUrl: 'https://s3/put',
    })
    confirmUpload.mockResolvedValue({ key: 'coach_gallery/42/2026/10/a.jpg' })
    addCoachGalleryImage.mockResolvedValue(item(0))
    const wrapper = await mountDialog()

    wrapper.vm.selectedFile = new File(['x'], 'pic.JPG', { type: 'image/jpeg' })
    await wrapper.vm.upload()
    await flushPromises()

    expect(signUpload).toHaveBeenCalledWith(
      expect.objectContaining({ entity: 'coach_gallery', entityId: '42', extension: 'jpg' }),
    )
    expect(global.fetch).toHaveBeenCalledWith(
      'https://s3/put',
      expect.objectContaining({ method: 'PUT' }),
    )
    expect(addCoachGalleryImage).toHaveBeenCalledWith('coach_gallery/42/2026/10/a.jpg')
    expect(wrapper.findAll('[data-test="gallery-item"]')).toHaveLength(1)
    expect(wrapper.emitted('updated')).toBeTruthy()
  })

  it('hides the upload control once the gallery holds 6 images', async () => {
    getCoachGallery.mockResolvedValue([0, 1, 2, 3, 4, 5].map(item))
    const wrapper = await mountDialog()
    expect(wrapper.find('[data-test="gallery-upload"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('profile.coachGalleryFull')
  })

  it('maps the backend galleryFull error to its own message', async () => {
    getCoachGallery.mockResolvedValue([])
    signUpload.mockResolvedValue({ key: 'k', uploadUrl: 'u' })
    confirmUpload.mockResolvedValue({ key: 'k' })
    deleteStorageObject.mockResolvedValue()
    addCoachGalleryImage.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'marketplace.galleryFull' } } },
    })
    const wrapper = await mountDialog()

    wrapper.vm.selectedFile = new File(['x'], 'pic.png', { type: 'image/png' })
    await wrapper.vm.upload()
    await flushPromises()

    expect(wrapper.text()).toContain('profile.coachGalleryFull')
    // the refused, already-confirmed upload is cleaned up rather than left as an orphan
    expect(deleteStorageObject).toHaveBeenCalledWith('k')
  })

  it('deletes an item and drops it from the grid', async () => {
    getCoachGallery.mockResolvedValue([item(0), item(1)])
    deleteCoachGalleryItem.mockResolvedValue()
    const wrapper = await mountDialog()

    await wrapper.findAll('[data-test="gallery-delete"]')[0].trigger('click')
    await flushPromises()

    expect(deleteCoachGalleryItem).toHaveBeenCalledWith('m-0')
    expect(wrapper.findAll('[data-test="gallery-item"]')).toHaveLength(1)
  })
})
