// Found by Pitchside OPS-06: a DOWN health report (HTTP 503) is the answer, not a server error.

import { describe, it, expect, vi } from 'vitest'

const get = vi.fn().mockResolvedValue({})
vi.mock('src/boot/axios', () => ({ api: { get: (...args) => get(...args) } }))

import { adminApi } from 'src/api/admin.api'

describe('adminApi.getHealth', () => {
  it('treats 200 and 503 as answers, anything else as an error', async () => {
    await adminApi.getHealth()
    const options = get.mock.calls[0][1]
    expect(get.mock.calls[0][0]).toMatch(/:8367\/manage\/health$/)
    expect(options.validateStatus(200)).toBe(true)
    expect(options.validateStatus(503)).toBe(true)
    expect(options.validateStatus(500)).toBe(false)
    expect(options.validateStatus(401)).toBe(false)
  })
})
