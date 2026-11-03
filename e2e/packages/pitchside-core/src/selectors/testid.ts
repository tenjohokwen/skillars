import type { Locator, Page } from '@playwright/test'

/**
 * The one selector policy: `data-testid`, kebab-case `<screen>-<element>-<qualifier>`, declared once
 * in a screen object. Repeated rows put the id on the row container and find children within it, so
 * a list never needs indexed ids. Never a UI library's internal classes, nth-child, XPath or raw
 * UI strings — if a screen can't be addressed without one, it needs a test id.
 */
export const TEST_ID_ATTRIBUTE = 'data-testid'

/** `login-email-input`, `row-42`, `conversation-item-9650000001` */
export const TEST_ID_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/

export function assertTestId(id: string): string {
  if (!TEST_ID_PATTERN.test(id)) {
    throw new Error(`Test id "${id}" breaks the naming policy: use lower-kebab-case`)
  }
  return id
}

/** A row-scoped id such as `conversation-item-${id}`. */
export function rowTestId(prefix: string, key: string | number | bigint): string {
  return assertTestId(`${prefix}-${String(key).toLowerCase()}`)
}

type Scope = Pick<Page, 'getByTestId'> | Pick<Locator, 'getByTestId'>

/** Locate by test id, enforcing the naming policy so a typo fails at the call site. */
export function tid(scope: Scope, id: string): Locator {
  return scope.getByTestId(assertTestId(id))
}
