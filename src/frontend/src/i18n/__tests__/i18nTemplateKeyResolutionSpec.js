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
// Approach: a pragmatic static scan (comment detection is parser-backed, see blankCommentSpans; call-site
// extraction is still line-based regex) over every `.vue` file and
// every `.js` file under `src/` (not just those that call `i18n.global.t(` directly — a `.vue`-only
// glob would miss errorHandler.js/useErrorHandler.js; note every scanned `.js` must therefore parse
// with plain `babelParse`, see blankCommentSpans), extracting every `$t(`/`$te(`/`t(`/`i18n.global.t(` call's
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
import { parse as parseSfc, babelParse } from 'vue/compiler-sfc'
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
    at: 'src/components/booking/DisputeAction.vue:89',
    reason: 'label keyed by disputeStatus.reason, a runtime enum value from GET /api/disputes/{id}',
  },
  {
    at: 'src/components/booking/DisputeAction.vue:92',
    reason: 'label keyed by disputeStatus.status, a runtime enum value from GET /api/disputes/{id}',
  },
  {
    at: 'src/components/booking/DisputeAction.vue:96',
    reason:
      'label keyed by disputeStatus.resolution, a runtime enum value from GET /api/disputes/{id}',
  },
  {
    at: 'src/pages/admin/AdminDisputesPage.vue:27',
    reason: 'table cell keyed by props.row.reason, a runtime enum value from the dispute queue',
  },
  {
    at: 'src/pages/admin/AdminDisputesPage.vue:30',
    reason: 'table cell keyed by props.row.status, a runtime enum value from the dispute queue',
  },
  {
    at: 'src/pages/admin/AdminDisputesPage.vue:81',
    reason: 'label keyed by detail.reason, a runtime enum value from GET /api/admin/disputes/{id}',
  },
  {
    at: 'src/pages/admin/AdminDisputesPage.vue:84',
    reason: 'label keyed by detail.status, a runtime enum value from GET /api/admin/disputes/{id}',
  },
  {
    at: 'src/pages/admin/AdminDisputesPage.vue:95',
    reason:
      'label keyed by detail.resolution, a runtime enum value from GET /api/admin/disputes/{id}',
  },
  {
    at: 'src/components/development/SkillTrendsPanel.vue:81',
    reason:
      'directionLabel: t() on a DIRECTION_LABEL_KEYS lookup (static keys) keyed by trend.direction, a runtime enum value from GET .../slu/skill-trends',
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
    at: 'src/components/video/VideoStatusCard.vue:99',
    reason: 'status label keyed by the video processing status enum',
  },
  // ---- string concatenation ('literal.prefix.' + variable) ----
  {
    at: 'src/components/development/DevelopmentCorrelationPanel.vue:72',
    reason: 'insight key built from a camelCased insight-type enum value',
  },
  {
    at: 'src/pages/parent/ParentDashboardPage.vue:38',
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
    at: 'src/pages/coach/CoachProfileBuilderPage.vue:32',
    reason: "rail step label keyed by that step config entry's shortKey",
  },
  {
    at: 'src/pages/coach/CoachProfileBuilderPage.vue:47',
    reason: "card title keyed by the current step config entry's titleKey",
  },
  {
    at: 'src/pages/coach/CoachProfileBuilderPage.vue:112',
    reason: 't() on a server-supplied errorKey, guarded by its own $te probe on the same line',
  },
  {
    at: 'src/pages/coach/CoachCommandCenterPage.vue:883',
    reason: 'notify message keyed by a successMessage key chosen earlier in the handler',
  },
  {
    at: 'src/pages/coach/CoachCommandCenterPage.vue:894',
    reason: 'notify message keyed by a messageKey chosen earlier in the handler',
  },
  {
    at: 'src/pages/coach/CoachPaymentSettingsPage.vue:43',
    reason:
      't on stripeCallbackErrorKey, a computed mapping ?stripe_error to one of three payment.error.stripeCallback.* keys',
  },
  {
    at: 'src/pages/coach/CoachPlayerDetailPage.vue:202',
    reason: '$t on noContributionsKey, a computed that picks between two real i18n keys at runtime',
  },
  {
    at: 'src/pages/messaging/MessagingPage.vue:109',
    reason:
      'list load error: messagingErrorI18nKey maps a backend code to a messaging.errors.* key',
  },
  {
    at: 'src/pages/messaging/MessagingPage.vue:153',
    reason: 'moderationNotice(msg) picks one of the four messaging.moderation.* keys',
  },
  {
    at: 'src/pages/messaging/MessagingPage.vue:226',
    reason: 'composerLockKey picks messaging.blockedBanner or messaging.cannotSend',
  },
  {
    at: 'src/pages/messaging/MessagingPage.vue:472',
    reason: 'send error: messagingErrorI18nKey maps a backend code to a messaging.errors.* key',
  },
  {
    at: 'src/composables/useStartConversation.js:43',
    reason:
      'start-conversation error: messagingErrorI18nKey maps a backend code to messaging.errors.*',
  },
  {
    at: 'src/pages/coach/CoachSubscriptionPage.vue:266',
    reason:
      'subscribe error: subscriptionErrorI18nKey maps a backend code to subscription.errors.*',
  },
  {
    at: 'src/pages/coach/CoachSubscriptionPage.vue:284',
    reason:
      'tier-change error: subscriptionErrorI18nKey maps a backend code to subscription.errors.*',
  },
  {
    at: 'src/pages/parent/PlayerSubscriptionPage.vue:290',
    reason:
      'subscribe error: subscriptionErrorI18nKey maps a backend code to subscription.errors.*',
  },
  {
    at: 'src/pages/marketplace/CoachPublicProfilePage.vue:214',
    reason: "label keyed by the review-eligibility response's own reasonCode enum",
  },
  {
    at: 'src/pages/parent/ParentDevelopmentPortalPage.vue:90',
    reason: '$t on noContributionsKey, the Parent-portal twin of the Coach-side computed above',
  },
  {
    at: 'src/pages/VideoManagementPage.vue:41',
    reason: 'coach upload-type label keyed by each COACH_VIDEO_TYPES entry (video.type.*)',
  },
  {
    at: 'src/pages/VideoManagementPage.vue:42',
    reason: 'coach upload-type hint keyed by each COACH_VIDEO_TYPES entry (video.typeHint.*)',
  },
  {
    at: 'src/components/video/VideoPlayDialog.vue:28',
    reason: 't() on errorKey, set only from the fixed video.player.* keys in errorKeyFor()',
  },
  {
    at: 'src/components/common/AppealAction.vue:13',
    reason: 'appeal status chip keyed by the AppealStatus enum (OPEN/UPHELD/OVERTURNED)',
  },
  {
    at: 'src/components/common/AppealAction.vue:35',
    reason: 'appeal dialog subtitle keyed by the AppealSubjectType enum prop',
  },
  {
    at: 'src/components/common/AppealAction.vue:148',
    reason:
      't on a lookup-table value mapping a backend error message to a real appeals.errors.* key, with a literal fallback',
  },
  {
    at: 'src/components/profile/PrivacyDataSection.vue:125',
    reason:
      't on each entry of consequenceKeys, a computed list of literal privacy.consequence* keys',
  },
  {
    at: 'src/components/profile/PrivacyDataSection.vue:308',
    reason: 't on errorMessageKey(), which maps a backend error to a literal privacy.errors.* key',
  },
  {
    at: 'src/components/profile/PrivacyDataSection.vue:349',
    reason: 't on errorMessageKey(), the erasure-dialog twin of the call above',
  },
  {
    at: 'src/pages/admin/AdminAppealsPage.vue:36',
    reason: 'queue cell keyed by the AppealSubjectType enum',
  },
  {
    at: 'src/pages/admin/AdminAppealsPage.vue:44',
    reason: 'queue cell keyed by the AppealStatus enum',
  },
  {
    at: 'src/pages/admin/AdminAppealsPage.vue:97',
    reason: 'detail line keyed by the AppealSubjectType enum',
  },
  {
    at: 'src/pages/admin/AdminAppealsPage.vue:106',
    reason: 'detail line keyed by the AppealStatus enum',
  },
  {
    at: 'src/pages/admin/AdminAppealsPage.vue:333',
    reason:
      'subject-summary field label: te()-guarded admin.appeals.subjectField.<backend map key>, raw key fallback',
  },
  {
    at: 'src/pages/parent/ParentBookingsPage.vue:503',
    reason:
      't on a lookup-table value mapping a backend errorKey to a real no-show copy key, with a literal fallback',
  },
]

const EN_KEY_SET = new Set(collectKeyPaths(enUS))

// skillars-deferred-157 AC11(c): an order-of-magnitude "the scan found something" guard against
// the scan silently matching zero files/calls (e.g. a SRC_ROOT path typo) and every other assertion
// passing vacuously — NOT a precise count. Measured at skillars-deferred-164: 1641 statically-
// resolvable CALL SITES (what literalCalls.length counts — not distinct keys) — raised from 1571
// (skillars-deferred-157) by ordinary growth. The floor itself was raised from the original 500,
// which left so much headroom that a drop to, say, 600 would pass unnoticed; 1550 is ~95% of the
// measured 1641, close enough to catch exactly that regression while leaving room for ordinary
// future additions. If a legitimate i18n consolidation ever trips this, lower the constant and note
// why here. Deliberately no upper bound: that would make every new translated string a spec edit.
const MIN_LITERAL_CALL_SITES = 1550

// Matches a call whose first argument is a quoted (or backtick) string: captures the quote
// character and the literal content between the (possibly escaped) delimiters.
const QUOTED_CALL_RE = /(\$te?|i18n\.global\.t|(?<![.\w$])t)\(\s*(['"`])((?:\\.|(?!\2)[^\\])*)\2/g
// Matches a call whose first argument does NOT start with a quote/backtick at all — a bare
// variable or property-access expression (e.g. `t(messageKey)`, `t(badge.labelKey)`).
const BARE_VAR_CALL_RE = /(\$te?|i18n\.global\.t|(?<![.\w$])t)\(\s*([^'"`\s)])/g

// skillars-deferred-158 AC1/AC2: comment spans are located by a real parser, not by regex, and blanked
// before the line split so a translation call inside a comment is not scanned as live code. Blanking
// replaces every non-newline character with a space, so line numbers (which DYNAMIC_KEY_CALL_SITES is
// keyed by) never move.
//   * `.js`: `babelParse` over the whole file; its `.comments` carry exact ranges and ignore `/*`,
//     `//` and `<!--` inside string literals.
//   * `.vue`: `parse()` splits the SFC; comments in `<script>`/`<script setup>` come from `babelParse`
//     (ranges are block-relative, so the block's content offset is added) and comments in `<template>`
//     come from the template AST's comment nodes. `comments: true` is required: Vue drops template
//     comment nodes under NODE_ENV=production, which would silently turn this blanking into a no-op.
// A file that cannot be parsed throws, naming the file. It is never scanned raw: a silent fallback
// would recreate the blindness this replaces.
//
// Not blanked (no instances today; a translation call in any of these is scanned as live, which fails
// loudly as a missing key or an unlisted dynamic site, never silently):
//   * `/* */` inside template expressions (`{{ /* $t('x') */ }}`): never covered, old design included.
//   * Deliberate coverage losses vs the previous regex design (code review of skillars-deferred-158,
//     owner decision 2026-10-22: accept rather than re-add regex): `/* */` and `//` inside `<style>`,
//     `<!-- -->` at the SFC top level outside every block, and `// …` outside script blocks (e.g. in an
//     `@click` expression), which the old line-based `stripLineComment` used to strip.
function blankPreservingNewlines(span) {
  return span.replace(/[^\n]/g, ' ')
}

const BABEL_PLUGINS_BY_LANG = { ts: ['typescript'], tsx: ['typescript', 'jsx'], jsx: ['jsx'] }

function scriptCommentRanges(code, offset, lang, file) {
  let ast
  try {
    ast = babelParse(code, { sourceType: 'module', plugins: BABEL_PLUGINS_BY_LANG[lang] ?? [] })
  } catch (e) {
    throw new Error(`${file}: cannot parse script for comment detection: ${e.message}`)
  }
  return (ast.comments ?? []).map((c) => [offset + c.start, offset + c.end])
}

function templateCommentRanges(node, ranges) {
  if (node.type === 3) ranges.push([node.loc.start.offset, node.loc.end.offset])
  for (const child of node.children ?? []) {
    if (typeof child === 'object') templateCommentRanges(child, ranges)
  }
  return ranges
}

function commentRanges(content, file) {
  if (!file.endsWith('.vue')) return scriptCommentRanges(content, 0, undefined, file)
  const { descriptor, errors } = parseSfc(content, {
    filename: file,
    templateParseOptions: { comments: true },
  })
  if (errors.length > 0) {
    throw new Error(
      `${file}: cannot parse SFC for comment detection: ${errors.map((e) => e.message).join('; ')}`,
    )
  }
  const ranges = []
  for (const block of [descriptor.script, descriptor.scriptSetup]) {
    if (block && !block.src) {
      ranges.push(...scriptCommentRanges(block.content, block.loc.start.offset, block.lang, file))
    }
  }
  if (descriptor.template?.ast) templateCommentRanges(descriptor.template.ast, ranges)
  return ranges
}

function blankCommentSpans(content, file) {
  let out = ''
  let last = 0
  for (const [start, end] of commentRanges(content, file).sort((a, b) => a[0] - b[0])) {
    out += content.slice(last, start) + blankPreservingNewlines(content.slice(start, end))
    last = end
  }
  return out + content.slice(last)
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
      const line = lines[i]
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
          dynamicCalls.push({ file, line: lineNumber, snippet: line.trim() })
        } else {
          literalCalls.push({ file, line: lineNumber, key: captured, snippet: line.trim() })
        }
      }

      const bareRe = new RegExp(BARE_VAR_CALL_RE)
      while ((m = bareRe.exec(line))) {
        dynamicCalls.push({ file, line: lineNumber, snippet: line.trim() })
      }

      if (WRAPPED_CALL_RE.test(line)) {
        wrappedCalls.push({ file, line: lineNumber, snippet: line.trim() })
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

  it('comment blanking: a template glob attribute cannot open a fake block comment (skillars-deferred-157 AC11b, parser-backed since skillars-deferred-158)', () => {
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

  // skillars-deferred-158 AC1/AC2: each "stays scannable" fixture deliberately contains a LATER genuine
  // closing delimiter with a live call between it and the decoy. Without that delimiter the old regex
  // heuristic would also pass, and the fixture would prove nothing.
  it('parser-backed blanking: a "/*" inside a JS string literal does not blank the live call after it (skillars-deferred-158 AC1)', () => {
    const fixture = [
      "const a = '/*'",
      "const k = t('fixture.live')",
      '/* real */',
      "const z = t('fixture.after')",
    ].join('\n')
    const lines = blankCommentSpans(fixture, 'Fixture.js').split('\n')
    expect(lines).toHaveLength(fixture.split('\n').length)
    expect(lines[0]).toBe("const a = '/*'")
    expect(lines[1]).toBe("const k = t('fixture.live')")
    expect(lines[2].trim()).toBe('')
    expect(lines[3]).toBe("const z = t('fixture.after')")
  })

  it('parser-backed blanking: a "<!--" inside a template attribute does not blank the live call after it (skillars-deferred-158 AC1)', () => {
    const fixture = [
      '<template>',
      '  <input title="<!--" />',
      "  <span>{{ $t('fixture.live') }}</span>",
      '  <!-- real -->',
      '</template>',
    ].join('\n')
    const lines = blankCommentSpans(fixture, 'Fixture.vue').split('\n')
    expect(lines).toHaveLength(fixture.split('\n').length)
    expect(lines[1]).toBe('  <input title="<!--" />')
    expect(lines[2]).toBe("  <span>{{ $t('fixture.live') }}</span>")
    expect(lines[3].trim()).toBe('')
  })

  it('parser-backed blanking: genuine commented-out calls are blanked in .js, <script> and <template> (skillars-deferred-158 AC1)', () => {
    const js = ["/* $t('fixture.old') */", "// t('fixture.old2')", "t('fixture.keep')"].join('\n')
    const jsLines = blankCommentSpans(js, 'Fixture.js').split('\n')
    expect(jsLines[0].trim()).toBe('')
    expect(jsLines[1].trim()).toBe('')
    expect(jsLines[2]).toBe("t('fixture.keep')")

    const sfc = [
      '<template>',
      "  <!-- $t('fixture.old') -->",
      "  <b>{{ $t('fixture.keep') }}</b>",
      '</template>',
      '<script setup>',
      "/* t('fixture.old') */",
      "const x = t('fixture.keep') // t('fixture.old2')",
      '</script>',
    ].join('\n')
    const lines = blankCommentSpans(sfc, 'Fixture.vue').split('\n')
    expect(lines).toHaveLength(sfc.split('\n').length)
    expect(lines[1].trim()).toBe('')
    expect(lines[2]).toBe("  <b>{{ $t('fixture.keep') }}</b>")
    expect(lines[5].trim()).toBe('')
    expect(lines[6].trimEnd()).toBe("const x = t('fixture.keep')")
  })

  it('parser-backed blanking: an unparseable file fails loudly naming the file, never falls back to a raw scan (skillars-deferred-158 AC2)', () => {
    // "</script>" inside a JS string ends the SFC <script> block early (the real SFC parser does the same),
    // so this is not a valid .vue file. It must throw, not be silently scanned unblanked.
    const sfc = ['<script>', "const s = '</script>'", "t('fixture.live')", '</script>'].join('\n')
    expect(() => blankCommentSpans(sfc, 'Broken.vue')).toThrow(/Broken\.vue/)
    expect(() => blankCommentSpans('const = ;', 'Broken.js')).toThrow(/Broken\.js/)
    // A well-formed SFC whose script Babel rejects reaches the script-parse throw, not the SFC errors one.
    expect(() =>
      blankCommentSpans('<script setup>\nconst = ;\n</script>', 'BrokenScript.vue'),
    ).toThrow(/BrokenScript\.vue: cannot parse script/)
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
