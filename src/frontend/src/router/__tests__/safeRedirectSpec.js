// skillars-deferred-144 AC3/AC7 — first spec for this directory (no spec existed for
// roleRoutes.js either). Tests isSafeRedirect against the REAL production route table
// (`import routes from '../routes'`) rather than a hand-rolled mock list: a mock list with no
// catch-all cannot catch the class of defect this guard exists to fix (router.resolve().matched
// .length > 0 is a no-op check against a route table that has one). router.resolve() only matches
// path/meta — it does not trigger any route's lazy `component: () => import(...)` loader, so this
// stays cheap despite importing the whole route tree.

import { describe, it, expect } from 'vitest'
import { createRouter, createMemoryHistory } from 'vue-router'
import routes from '../routes'
import { isSafeRedirect } from '../safeRedirect'

const router = createRouter({ history: createMemoryHistory(), routes })

describe('safeRedirect.js — isSafeRedirect (skillars-deferred-144 AC3)', () => {
  it('rejects a protocol-relative path', () => {
    expect(isSafeRedirect('//evil.example.com', router)).toBe(false)
  })

  it('rejects an absolute URL', () => {
    expect(isSafeRedirect('https://evil.example.com', router)).toBe(false)
  })

  it('rejects a non-string value', () => {
    expect(isSafeRedirect(undefined, router)).toBe(false)
    expect(isSafeRedirect(null, router)).toBe(false)
    expect(isSafeRedirect(['/dashboard'], router)).toBe(false)
  })

  it('accepts a real registered route', () => {
    expect(isSafeRedirect('/dashboard', router)).toBe(true)
  })

  it('rejects a shape-safe path that matches no real route — the defect this guard exists to catch', () => {
    // The obvious `router.resolve(path).matched.length > 0` idiom is a no-op here: routes.js's own
    // catch-all (`/:catchAll(.*)*`) matches every `/`-prefixed path, so an unresolvable path would
    // still report matched.length === 1 without the tagged-catch-all exclusion. This is the one
    // case that actually proves isSafeRedirect excludes it correctly.
    expect(isSafeRedirect('/this-path-does-not-exist', router)).toBe(false)
  })

  it('rejects a backslash payload — incidentally, via the resolvability check, not the shape check', () => {
    // Code review 2026-10-06: the shape check alone would pass this (it starts with '/' and
    // isn't protocol-relative or absolute). It is only rejected today because it matches no real
    // route and lands on the tagged catch-all. If a permissive top-level route is ever added,
    // this case stops being safe-by-accident — re-check this test first if that happens.
    expect(isSafeRedirect('/\\evil.example.com', router)).toBe(false)
  })

  it('rejects a percent-encoded protocol-relative payload — same incidental reason as above', () => {
    expect(isSafeRedirect('/%2F%2Fevil.example.com', router)).toBe(false)
  })
})
