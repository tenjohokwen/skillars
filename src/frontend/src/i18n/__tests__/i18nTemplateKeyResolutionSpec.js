// skillars-deferred-156 AC6: closes the half of the skillars-12-5 code-review finding that
// skillars-deferred-155 AC14 explicitly scoped out — localeKeyParitySpec.js (AC14) proves the
// three locale files share the same key SET; nothing proves every `$t()`/`$te()`/`t()` call in
// every template or script actually RESOLVES against that set.
//
// Mechanism this spec exists to defeat, precisely: setup-file.js's global `$t`/`$tc` mock only
// covers the `$t(`/`$te(` form used in `.vue` templates, and it isn't even a pure echo (it
// special-cases `params.date`). What makes the DOMINANT bare composition-API `t('…')` form (used
// in `<script setup>`, far more common than `$t(`) echo its key back under test is the REAL
// `createI18n` instance installed at setup-file.js:18-27 with an empty `en-US` catalog and
// `missingWarn: false, fallbackWarn: false` — a genuinely missing key resolves through real
// vue-i18n machinery to its own key string, not a mock, so no other spec would ever notice a
// typo'd or mis-nested key. `$te` is not stubbed by the global mock at all and always returns
// `false` under test — a second, separately-unflagged blind spot this spec's own exception list
// below names explicitly (e.g. PlayerTimelinePanel.vue:40 renders a raw enum name under test today
// because of it, never a translated label).
//
// Approach: a pragmatic regex-based static scan (not a real parser) over every `.vue` file and
// every `.js` file that calls `i18n.global.t(` directly (a `.vue`-only glob would miss
// errorHandler.js/useErrorHandler.js), extracting every `$t(`/`$te(`/`t(`/`i18n.global.t(` call's
// first argument. Every call whose key argument is a plain string literal is asserted to exist as
// a real leaf in en-US's catalog. A call whose key cannot be statically resolved — a template
// literal with `${…}` interpolation, string concatenation (`'prefix.' + variable`), or a bare
// variable/property-access argument — cannot be checked this way; EVERY such site found by the
// scan must appear in the DYNAMIC_KEY_CALL_SITES list below with a one-line reason, or this spec
// fails. That makes the exception list a closed, enforced record (new dynamic call sites must be
// deliberately added, never silently swallowed) rather than a one-time, ever-stale snapshot.
//
// en-US only, not all three locales: localeKeyParitySpec.js (AC14) already guarantees de-DE/fr-FR
// have the IDENTICAL key set to en-US, so resolving against en-US alone is sufficient — asserting
// the same resolution per-locale would be redundant, not a stronger guarantee.

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'
import enUS from '../en-US/index.js'
import { collectKeyPaths } from '../../../test/vitest/collectKeyPaths.js'

const FRONTEND_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const SRC_ROOT = path.join(FRONTEND_ROOT, 'src')

// A real-named-call call-site match, keyed by `${relativeFile}:${lineNumber}` so a future edit to
// one of these exact lines surfaces as a NEW (or disappeared) finding rather than silently
// matching forever. `reason` is the one-line record this AC requires.
const DYNAMIC_KEY_CALL_SITES = [
  // ---- template-literal interpolation (`...${…}...`) ----
  {
    at: 'src/components/booking/SessionDNAChart.vue:69',
    reason: 'axis label keyed by axis.key, a runtime enum value',
  },
  {
    at: 'src/components/booking/SessionDNAChart.vue:80',
    reason: 'same axis-key interpolation, second render site on the same component',
  },
  {
    at: 'src/components/booking/SessionPackTracker.vue:55',
    reason: "status label keyed by the pack state machine's current state value",
  },
  {
    at: 'src/components/development/PlayerTimelinePanel.vue:40',
    reason: "$te probe keyed by the timeline event's own eventType enum",
  },
  {
    at: 'src/components/development/PlayerTimelinePanel.vue:41',
    reason: '$t keyed by the same eventType enum, guarded by the $te probe above',
  },
  {
    at: 'src/components/marketplace/VerificationBadge.vue:42',
    reason: 'badge label keyed by the coach verification tier enum',
  },
  {
    at: 'src/components/marketplace/VerificationBadge.vue:43',
    reason: 'tooltip text keyed by the same tier enum',
  },
  {
    at: 'src/components/session/DevelopmentFocusSelector.vue:14',
    reason: 'option label keyed by the focus-area enum value being iterated',
  },
  {
    at: 'src/components/video/VideoStatusCard.vue:87',
    reason: 'status label keyed by the video processing status enum',
  },
  // ---- string concatenation ('literal.prefix.' + variable) ----
  {
    at: 'src/components/development/DevelopmentCorrelationPanel.vue:72',
    reason: 'insight key built from a camelCased insight-type enum value',
  },
  {
    at: 'src/pages/auth/ParentDashboardPlaceholderPage.vue:28',
    reason: "position label keyed by the player's position enum value",
  },
  // ---- bare variable / property-access first argument ----
  {
    at: 'src/composables/useErrorHandler.js:56',
    reason: '$te probe on a server-supplied errorKey, falls back to raw message if absent',
  },
  {
    at: 'src/composables/useErrorHandler.js:61',
    reason: 't() on the same server-supplied errorKey, guarded by the $te probe above',
  },
  {
    at: 'src/utils/errorHandler.js:122',
    reason:
      'i18n.global.t() on a messageKey resolved earlier in the same function from an HTTP-status lookup table',
  },
  {
    at: 'src/components/booking/BookingStateChip.vue:53',
    reason: "status label keyed by a lookup-table entry's own key field",
  },
  {
    at: 'src/components/development/SluNarrativeSummary.vue:4',
    reason: "$t on a narrative item's own server-supplied key + params",
  },
  {
    at: 'src/components/marketplace/CapabilityBadgeSet.vue:8',
    reason: "badge label keyed by each badge config entry's labelKey",
  },
  {
    at: 'src/components/marketplace/CapabilityBadgeSet.vue:12',
    reason: "tooltip text keyed by the same badge config entry's tooltipKey",
  },
  {
    at: 'src/pages/auth/CoachProfileBuilderPlaceholderPage.vue:32',
    reason: "rail step label keyed by that step config entry's shortKey",
  },
  {
    at: 'src/pages/auth/CoachProfileBuilderPlaceholderPage.vue:47',
    reason: "card title keyed by the current step config entry's titleKey",
  },
  {
    at: 'src/pages/auth/CoachProfileBuilderPlaceholderPage.vue:112',
    reason: 't() on a server-supplied errorKey, guarded by its own $te probe on the same line',
  },
  {
    at: 'src/pages/coach/CoachCommandCenterPage.vue:667',
    reason: 'notify message keyed by a successMessage key chosen earlier in the handler',
  },
  {
    at: 'src/pages/coach/CoachCommandCenterPage.vue:678',
    reason: 'notify message keyed by a messageKey chosen earlier in the handler',
  },
  {
    at: 'src/pages/coach/CoachPlayerDetailPage.vue:59',
    reason: '$t on noContributionsKey, a computed that picks between two real i18n keys at runtime',
  },
  {
    at: 'src/pages/marketplace/CoachPublicProfilePage.vue:176',
    reason: "label keyed by the review-eligibility response's own reasonCode enum",
  },
  {
    at: 'src/pages/parent/ParentDevelopmentPortalPage.vue:82',
    reason: '$t on noContributionsKey, the Parent-portal twin of the Coach-side computed above',
  },
]

const EN_KEY_SET = new Set(collectKeyPaths(enUS))

// skillars-deferred-157 AC11(c): an order-of-magnitude "the scan found something" guard against
// the scan silently matching zero files/calls (e.g. a SRC_ROOT path typo) and every other assertion
// passing vacuously — NOT a precise count. Measured at skillars-deferred-157: 1571 statically-
// resolvable CALL SITES (what literalCalls.length counts — not distinct keys). If a legitimate i18n
// consolidation ever trips this, lower the constant and note why here. Deliberately no upper bound:
// that would make every new translated string a spec edit.
const MIN_LITERAL_CALL_SITES = 500

// Matches a call whose first argument is a quoted (or backtick) string: captures the quote
// character and the literal content between the (possibly escaped) delimiters.
const QUOTED_CALL_RE = /(\$te?|i18n\.global\.t|(?<![.\w$])t)\(\s*(['"`])((?:\\.|(?!\2)[^\\])*)\2/g
// Matches a call whose first argument does NOT start with a quote/backtick at all — a bare
// variable or property-access expression (e.g. `t(messageKey)`, `t(badge.labelKey)`).
const BARE_VAR_CALL_RE = /(\$te?|i18n\.global\.t|(?<![.\w$])t)\(\s*([^'"`\s)])/g

// skillars-deferred-157 AC11(b): comment spans are blanked before the line split, so a translation
// call inside a block comment is not scanned as live code. Blanking replaces every non-newline
// character with a space, so line numbers (which DYNAMIC_KEY_CALL_SITES is keyed by) never move.
//
// Scoped by language region, deliberately NOT a whole-file `/\/\*[\s\S]*?\*\//g`: a template
// attribute such as `accept="video/*"` (DrillDetailPanel.vue, VideoManagementPage.vue) would open a
// fake "comment" that runs to the first real `*/` anywhere later in the file, blanking every call
// in between — and the spec would not fail, those keys would just silently stop being guarded.
//   * `.vue`: `/* … */` only inside <script>/<style> blocks; `<!-- … -->` only outside them (the
//     template, plus the SFC's top level, which is HTML-comment syntax too).
//   * `.js`: `/* … */` only.
const BLOCK_COMMENT_RE = /\/\*[\s\S]*?\*\//g
const HTML_COMMENT_RE = /<!--[\s\S]*?-->/g
const SFC_CODE_BLOCK_RE = /<(script|style)\b[^>]*>[\s\S]*?<\/\1>/g

function blankPreservingNewlines(span) {
  return span.replace(/[^\n]/g, ' ')
}

function blankCommentSpans(content, file) {
  if (!file.endsWith('.vue')) {
    return content.replace(BLOCK_COMMENT_RE, blankPreservingNewlines)
  }
  let out = ''
  let last = 0
  for (const m of content.matchAll(SFC_CODE_BLOCK_RE)) {
    out += content.slice(last, m.index).replace(HTML_COMMENT_RE, blankPreservingNewlines)
    out += m[0].replace(BLOCK_COMMENT_RE, blankPreservingNewlines)
    last = m.index + m[0].length
  }
  return out + content.slice(last).replace(HTML_COMMENT_RE, blankPreservingNewlines)
}

function stripLineComment(line) {
  // Naive, line-based: only strips `// …` preceded by whitespace or at line start, so it does not
  // also eat a `//` embedded mid-string (e.g. a URL) that has no preceding whitespace.
  return line.replace(/(^|\s)\/\/.*$/, '')
}

function listSourceFiles() {
  return fs
    .readdirSync(SRC_ROOT, { recursive: true })
    .filter((f) => f.endsWith('.vue') || f.endsWith('.js'))
    .map((f) => path.posix.join('src', f.split(path.sep).join('/')))
    .filter((f) => !f.includes('__tests__') && !f.startsWith('src/i18n/'))
    .filter((f) => fs.statSync(path.join(FRONTEND_ROOT, f)).isFile())
}

/**
 * Scans every candidate source file and splits every discovered `$t`/`$te`/`t`/`i18n.global.t`
 * call site into two buckets: `literalCalls` (a real, statically-resolvable string key) and
 * `dynamicCalls` (anything this regex-based pass cannot resolve — see the module doc above).
 */
// (code review of skillars-deferred-156) Matches a call whose opening paren ENDS the line, i.e. whose
// first argument sits on the next line — the shape Prettier produces for long argument lists:
//   t(
//     'coach.someVeryLongKeyName',
//     { playerName },
//   )
// Neither QUOTED_CALL_RE nor BARE_VAR_CALL_RE can see such a call: both need something after `(` on
// the same line, and the scan is line-based (`content.split('\n')`), so `\s*` can never cross the
// newline. Before this third bucket, such a site landed in NEITHER list — so it was not key-checked
// AND not forced into the exception list, and both enforcement assertions still passed. That is a
// hole in the module doc's "closed, enforced record" guarantee, so it is asserted empty rather than
// parsed: if one ever appears, the right fix is to teach the scanner multi-line calls, not to
// exempt the site. Verified zero occurrences at the time this was added.
const WRAPPED_CALL_RE = /(\$te?|i18n\.global\.t|(?<![.\w$])t)\(\s*$/

function scanCallSites() {
  const literalCalls = []
  const dynamicCalls = []
  const wrappedCalls = []

  for (const file of listSourceFiles()) {
    const content = blankCommentSpans(fs.readFileSync(path.join(FRONTEND_ROOT, file), 'utf8'), file)
    const lines = content.split('\n')
    for (let i = 0; i < lines.length; i++) {
      const raw = lines[i]
      const line = stripLineComment(raw)
      const lineNumber = i + 1

      const quotedRe = new RegExp(QUOTED_CALL_RE)
      let m
      while ((m = quotedRe.exec(line))) {
        const quote = m[2]
        const captured = m[3]
        const after = line.slice(quotedRe.lastIndex).trimStart()
        const isTemplateInterpolation = quote === '`' && captured.includes('${')
        const isConcatenation = after.startsWith('+')
        if (isTemplateInterpolation || isConcatenation) {
          dynamicCalls.push({ file, line: lineNumber, snippet: raw.trim() })
        } else {
          literalCalls.push({ file, line: lineNumber, key: captured, snippet: raw.trim() })
        }
      }

      const bareRe = new RegExp(BARE_VAR_CALL_RE)
      while ((m = bareRe.exec(line))) {
        dynamicCalls.push({ file, line: lineNumber, snippet: raw.trim() })
      }

      if (WRAPPED_CALL_RE.test(line)) {
        wrappedCalls.push({ file, line: lineNumber, snippet: raw.trim() })
      }
    }
  }

  return { literalCalls, dynamicCalls, wrappedCalls }
}

describe('i18n template-key resolution (skillars-deferred-156 AC6)', () => {
  const { literalCalls, dynamicCalls, wrappedCalls } = scanCallSites()

  it('found a non-trivial number of statically-resolvable call sites (scan is not silently finding nothing)', () => {
    // See MIN_LITERAL_CALL_SITES.
    expect(
      literalCalls.length,
      `only ${literalCalls.length} statically-resolvable call sites found (floor ${MIN_LITERAL_CALL_SITES}) — ` +
        'is the scan broken (SRC_ROOT, globs, regexes), or did a real consolidation shrink the count?',
    ).toBeGreaterThan(MIN_LITERAL_CALL_SITES)
  })

  it('every exception-list entry carries a non-empty reason and a unique location (skillars-deferred-157 AC11a)', () => {
    // The module doc promises "with a one-line reason, or this spec fails" — this is what enforces it.
    const missingReason = DYNAMIC_KEY_CALL_SITES.filter(
      (e) => typeof e.reason !== 'string' || e.reason.trim() === '',
    ).map((e) => e.at)
    expect(missingReason).toEqual([])
    // List hygiene only.
    const ats = DYNAMIC_KEY_CALL_SITES.map((e) => e.at)
    expect(ats.filter((at, i) => ats.indexOf(at) !== i)).toEqual([])
  })

  it('comment blanking is region-scoped: a template glob attribute cannot open a fake block comment (skillars-deferred-157 AC11b)', () => {
    const fixture = [
      '<template>',
      '  <input type="file" accept="video/*" />',
      "  <span>{{ $t('fixture.key') }}</span>",
      "  <!-- {{ $t('fixture.commentedOut') }} -->",
      '</template>',
      '<script setup>',
      '/* c */',
      "const label = t('fixture.scriptKey') /* trailing */",
      '</script>',
    ].join('\n')
    const blanked = blankCommentSpans(fixture, 'Fixture.vue')
    const lines = blanked.split('\n')
    expect(lines).toHaveLength(fixture.split('\n').length)
    expect(lines[2]).toBe("  <span>{{ $t('fixture.key') }}</span>")
    expect(lines[3].trim()).toBe('')
    expect(lines[6].trim()).toBe('')
    expect(lines[7].trimEnd()).toBe("const label = t('fixture.scriptKey')")
  })

  it('every statically-resolved $t()/$te()/t()/i18n.global.t() key exists as a real en-US leaf', () => {
    const missing = literalCalls.filter((c) => !EN_KEY_SET.has(c.key))
    expect(missing.map((m) => `${m.file}:${m.line} -> "${m.key}"`)).toEqual([])
  })

  it('every call site this scan cannot statically resolve is recorded in the exception list, not silently skipped', () => {
    const knownSites = new Set(DYNAMIC_KEY_CALL_SITES.map((e) => e.at))
    const unrecorded = dynamicCalls
      .map((c) => `${c.file}:${c.line}`)
      .filter((at) => !knownSites.has(at))
    expect(unrecorded).toEqual([])
  })

  it('no translation call is wrapped across lines, which this line-based scan would silently skip entirely', () => {
    // Not a style rule: a wrapped call escapes BOTH buckets above, so it is neither key-checked nor
    // forced into the exception list — it just disappears from the scan, which is exactly the
    // silent-staleness failure mode this spec exists to prevent. If this fails, teach scanCallSites
    // to join continuation lines; do not add the site to DYNAMIC_KEY_CALL_SITES, because its key may
    // well be a perfectly resolvable literal that simply needs checking.
    expect(wrappedCalls.map((c) => `${c.file}:${c.line} -> ${c.snippet}`)).toEqual([])
  })

  it('every entry in the exception list still corresponds to a real, currently-detected dynamic call site', () => {
    // The inverse check: a listed exception the scan no longer finds means the code changed (e.g.
    // a dynamic key became a literal one) and the entry is stale — prune it rather than letting
    // the list silently accumulate dead records.
    const detectedSites = new Set(dynamicCalls.map((c) => `${c.file}:${c.line}`))
    const stale = DYNAMIC_KEY_CALL_SITES.map((e) => e.at).filter((at) => !detectedSites.has(at))
    expect(stale).toEqual([])
  })
})
