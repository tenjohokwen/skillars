Quasar + Vue UI Design System
Dark & Light Mode Specification
Version 2.0  —  Dual-Mode Edition


1. How the Dual-Mode System Works
   The entire design system is built on CSS custom properties (variables). Dark mode values are defined on :root and are the defaults. A single .light class applied to <body> (or <q-layout>) overrides every token that needs to change. No component logic changes — only the token values swap.

Toggle implementation (one line):
document.body.classList.toggle('light');

Persist the preference:
localStorage.setItem('theme', isLight ? 'light' : 'dark');

ℹ  Dark mode is the default and primary aesthetic. Light mode is a fully accessible, usable alternative — not an afterthought.


2. Static Design Foundations — Mode-Independent
   These values never change between dark and light mode. They define the premium sports analytics aesthetic and must be applied consistently across every page.

2.1 Design Language
The application communicates:
▸  Dark futuristic sports analytics aesthetic
▸  Glassmorphism surfaces — translucent, layered, softly illuminated, floating
▸  Neon-accented data visualization (dark mode) / accessible-toned (light mode)
▸  Spacious premium dashboard layouts — no cramped enterprise grids
▸  High-contrast typography with strong visual hierarchy

Product personality: Elite · Analytical · Trustworthy · Intelligent · Future-focused · Calm
Avoid: corporate enterprise styling, excessive gamification, cartoonish visuals, heavy skeuomorphism, bright saturated backgrounds.

2.2 Typography
Font stack:  font-family: 'Inter', sans-serif
Load weights: 400, 500, 600, 700, 800

Type scale:
Role             Size        Weight   Notes
Hero Metric      56px–72px   800      line-height: 1; apply --hero-gradient as text color
Page Title       28px–36px   700
Section Title    18px–22px   700
Card Title       16px–18px   700
Body             14px–16px   400      line-height: 1.6
Metadata         12px–13px   500      letter-spacing: 0.3px
Label            11px–13px   600      text-transform: uppercase; letter-spacing: 0.5px

2.3 Spacing Rhythm
4px   micro
8px   small
12px  compact
16px  standard (default inside cards)
24px  section / dashboard column gap
32px  large
48px  hero

2.4 Layout System
App container:   max-width: 1450px; padding: 24px–32px; margin: auto;

Desktop grid:
  grid-template-columns: 320px 1fr 320px;
  gap: 24px;

Tablet (< 1200px):   grid-template-columns: 1fr;

Breakpoints:
  Desktop   1200px+
  Tablet    768px–1199px
  Mobile    < 768px

Mobile rules: collapse multi-column layouts; maintain touch targets ≥ 44px;
preserve typography hierarchy; keep spacing breathable — never cramp mobile dashboards.

2.5 Card Padding
Large analytics cards:     24px–28px
Compact cards:             16px–20px
Dense information blocks:  14px–16px

2.6 Component Dimensions
Progress bars:  border-radius: 999px; height: 8px–10px
Avatars:        88px–96px square; border-radius: 24px
Buttons:        height 40px / 44px / 48px; border-radius: 14px; never sharp corners
Sidebar:        width 280px; icon-first navigation; active neon indicators
Modals:         border-radius 24px–32px; blurred backdrop; centered and premium

2.7 Motion Rules
Standard transition:  all 0.2s ease — applied to cards, buttons, inputs, nav items.
Hover lift:           transform: translateY(-2px) — subtle, never jarring.
Avoid: large scale transforms, bouncy easing, aggressive motion, animations > 300ms.

2.8 Data Visualization Philosophy
Charts should:  blend into the UI; use transparent backgrounds; prioritize readability;
                use neon highlights sparingly; use muted grid lines; use smooth gradients;
                animate subtly; prefer smooth curves over rigid lines.
Avoid: cluttered labels, heavy legends, cramped tooltips.
Chart colors use the accent token variables so they automatically adapt to mode.

2.9 Global Utility Classes

.glass-card {
  background: var(--surface-glass);
  border: 1px solid var(--border-soft);
  border-radius: 28px;
  backdrop-filter: blur(18px);
  box-shadow: var(--card-shadow);
}

.gradient-text {
  background: var(--hero-gradient);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}

.soft-hover {
  transition: all 0.2s ease;
}
.soft-hover:hover {
  transform: translateY(-2px);
}

2.10 Quasar Component Mapping
Design System      Quasar Component
Card               q-card
Button             q-btn
Input              q-input
Select             q-select
Dialog             q-dialog
Table              q-table
Tabs               q-tabs
Drawer             q-drawer
Tooltip            q-tooltip
Menu               q-menu
Avatar             q-avatar
Badge              q-badge
Progress bar       q-linear-progress

2.11 SCSS File Architecture
src/
  css/
    variables.scss    ← CSS custom properties (:root and .light blocks — see Section 14)
    typography.scss   ← Inter import, type scale classes
    glass.scss        ← .glass-card, .gradient-text, .soft-hover utilities
    layouts.scss      ← .dashboard-grid, .app-page, breakpoints
    animations.scss   ← transitions, hover rules
    app.scss          ← imports all of the above

2.12 Consistency Contract
This design language applies to every surface in the product without exception:
Dashboards · Scouting workflows · Search pages · Tables · Forms · Analytics
AI insights · Reports · Settings · Admin pages · Authentication flows · Mobile layouts

Every screen should feel: part of a unified AI sports intelligence platform — premium,
cinematic, data-rich but uncluttered. The dark neon glass identity and strong dashboard
consistency must be immediately recognizable to every user.


3. Color System — Full Token Reference
   3.1 Background Colors
   Backgrounds form the base layer of the UI. Dark mode uses deep navy. Light mode uses a cool blue-gray that maintains the premium, analytical feel without harsh white.

Token / Variable
Dark Mode Value
Light Mode Value
--bg-primary
#0b1020
#f0f4f8
--bg-secondary
#11182d
#e4eaf3
--bg-tertiary
#151f38
#d8e2ef


ℹ  Do NOT use pure #ffffff or pure #000000 as page backgrounds in either mode. These break the premium glass aesthetic.

3.2 Glass Surface Colors
Glass surfaces sit above the background. In dark mode they are barely-visible white overlays. In light mode they become semi-opaque white panels — still translucent, but legible on the lighter base.

Token / Variable
Dark Mode Value
Light Mode Value
--surface-glass
rgba(255,255,255,0.04)
rgba(255,255,255,0.72)
--surface-glass-hover
rgba(255,255,255,0.06)
rgba(255,255,255,0.85)
--surface-glass-strong
rgba(255,255,255,0.08)
rgba(255,255,255,0.92)


3.3 Border Colors
Borders are always subtle. In dark mode they are white at very low opacity. In light mode they flip to a dark navy at low opacity, preserving the same visual weight.

Token / Variable
Dark Mode Value
Light Mode Value
--border-soft
rgba(255,255,255,0.06)
rgba(30,60,120,0.08)
--border-medium
rgba(255,255,255,0.10)
rgba(30,60,120,0.14)
--border-accent
rgba(0,255,180,0.25)
rgba(0,122,88,0.30)


3.4 Accent Colors
This is the most critical difference between modes. The neon accents (#00ffb4, #0088ff) are designed for dark backgrounds only — they fail WCAG contrast on light backgrounds. Light mode maps each accent to a darkened, accessible equivalent that preserves the color identity while meeting AA contrast.

Token / Variable
Dark Mode Value
Light Mode Value
--accent-primary
#00ffb4  (neon green)
#007a58  (forest green, AA on white)
--accent-secondary
#0088ff  (electric blue)
#1563c4  (navy blue, AA on white)
--accent-purple
#7b61ff  (violet)
#5b3fcf  (deep purple, AA on white)
--accent-warning
#ffb84d  (amber)
#c07a00  (dark amber, AA on white)
--accent-danger
#ff5f7a  (coral red)
#d0294a  (crimson, AA on white)


ℹ  Never use the dark-mode neons (#00ffb4, #0088ff) on light backgrounds. They fail WCAG AA contrast (ratio below 3:1 on white). Always use the light-mode token values.

3.5 Text Colors
Text opacity logic flips in light mode: instead of white-at-opacity on dark, use dark-navy-at-opacity on light. The opacity percentages remain identical, so hierarchy is preserved.

Token / Variable
Dark Mode Value
Light Mode Value
--text-primary
#ffffff
#0d1a2e
--text-secondary
rgba(255,255,255,0.72)
rgba(13,26,46,0.72)
--text-muted
rgba(255,255,255,0.55)
rgba(13,26,46,0.50)
--text-disabled
rgba(255,255,255,0.35)
rgba(13,26,46,0.30)


3.6 Hero Gradient
The primary gradient used for hero numbers, progress fills, avatars, and accent buttons. It adapts to use the accessible light-mode accents.

Token / Variable
Dark Mode Value
Light Mode Value
--hero-gradient
linear-gradient(135deg, #00ffb4, #0088ff)
linear-gradient(135deg, #007a58, #1563c4)

Alternative gradients (dark mode):
linear-gradient(135deg, #00ffb4, #7b61ff)
linear-gradient(135deg, #0088ff, #7b61ff)
Use for: hero numbers, progress bars, avatars, data highlights, interactive focus states, chart highlights.
Do NOT overuse in body text.


4. Background System
   4.1 Global Page Background
   Both modes use a radial lighting effect to create depth. The colors of the radial glows change with the mode, using the same variables defined in Section 3.

Token / Variable
Dark Mode Value
Light Mode Value
--radial-tl
rgba(0,255,180,0.08)
rgba(0,160,110,0.07)
--radial-br
rgba(0,120,255,0.08)
rgba(21,99,196,0.07)


Applied as:
background: var(--bg-primary);
background-image:
radial-gradient(circle at top left, var(--radial-tl), transparent 30%),
radial-gradient(circle at bottom right, var(--radial-br), transparent 30%);

ℹ  The radial glows are intentionally lower opacity in light mode — on a light background, strong glows look garish. Reduce opacity further if needed.




5. Card System
   5.1 Base Card Tokens
   Token / Variable
   Dark Mode Value
   Light Mode Value
   --card-shadow
   0 10px 40px rgba(0,0,0,0.35)
   0 10px 40px rgba(30,60,120,0.10)
   --surface-glass (fill)
   rgba(255,255,255,0.04)
   rgba(255,255,255,0.72)
   --border-soft (stroke)
   rgba(255,255,255,0.06)
   rgba(30,60,120,0.08)
   backdrop-filter
   blur(18px)  ← same both modes
   blur(18px)
   border-radius
   28px  ← same both modes
   28px


ℹ  backdrop-filter: blur() works in both modes and should never change. The blur effect is what makes glass surfaces look glass-like on both light and dark backgrounds.

5.2 Card Hover State
Token / Variable
Dark Mode Value
Light Mode Value
hover border-color
rgba(0,255,180,0.25)
rgba(0,122,88,0.30)
hover background
rgba(255,255,255,0.06)
rgba(255,255,255,0.85)
hover transform
translateY(-2px)  ← same both modes
translateY(-2px)




6. Navigation System
   6.1 Nav Item Tokens
   Token / Variable
   Dark Mode Value
   Light Mode Value
   --nav-active-bg
   rgba(0,255,180,0.10)
   rgba(0,122,88,0.10)
   --nav-active-color
   #00ffb4
   #007a58
   inactive text
   --text-secondary
   --text-secondary  (same token)
   hover background
   --surface-glass-hover
   --surface-glass-hover  (same token)




7. Button System
   7.1 Standard Button
   Token / Variable
   Dark Mode Value
   Light Mode Value
   --btn-bg
   rgba(255,255,255,0.05)
   rgba(255,255,255,0.75)
   --btn-border
   rgba(255,255,255,0.08)
   rgba(30,60,120,0.14)
   --btn-hover
   rgba(255,255,255,0.08)
   rgba(255,255,255,0.95)
   text color
   --text-primary
   --text-primary  (same token)


7.2 Accent Button (Primary Action)
The accent button uses the hero gradient as its background. The gradient token already handles mode switching, so no additional overrides are needed. Text color stays #081018 in both modes (very dark, legible on both the neon green and forest green gradients).

Token / Variable
Dark Mode Value
Light Mode Value
background
var(--hero-gradient)  ← switches automatically
var(--hero-gradient)
color
#081018  ← same both modes
#081018
border
none  ← same both modes
none




8. Input System
   Token / Variable
   Dark Mode Value
   Light Mode Value
   --input-bg
   rgba(255,255,255,0.04)
   rgba(255,255,255,0.82)
   --input-border
   rgba(255,255,255,0.08)
   rgba(30,60,120,0.14)
   --input-focus-border
   rgba(0,255,180,0.35)
   rgba(0,122,88,0.45)
   --input-focus-shadow
   rgba(0,255,180,0.08)
   rgba(0,122,88,0.10)
   text color
   --text-primary
   --text-primary  (same token)
   placeholder
   --text-muted
   --text-muted  (same token)




9. Progress Bars & Metrics
   Token / Variable
   Dark Mode Value
   Light Mode Value
   --progress-track
   rgba(255,255,255,0.08)
   rgba(30,60,120,0.10)
   progress fill
   var(--hero-gradient)
   var(--hero-gradient)  (auto-switches)
   hero metric text
   var(--hero-gradient)  ← gradient text
   var(--hero-gradient)
   metric sub-label
   --text-muted
   --text-muted  (same token)




10. Badge & Avatar System
    10.1 Badge
    Token / Variable
    Dark Mode Value
    Light Mode Value
    --badge-bg
    rgba(0,255,180,0.10)
    rgba(0,122,88,0.10)
    --badge-color
    #00ffb4
    #007a58


10.2 Avatar
Avatar background uses the hero gradient. No token overrides needed — it switches automatically.

Token / Variable
Dark Mode Value
Light Mode Value
background
var(--hero-gradient)  ← auto-switches
var(--hero-gradient)
text color
#081018  ← same both modes
#081018




11. Table System
    Token / Variable
    Dark Mode Value
    Light Mode Value
    --table-row-border
    rgba(255,255,255,0.04)
    rgba(30,60,120,0.06)
    --table-row-hover
    rgba(255,255,255,0.03)
    rgba(30,60,120,0.04)
    background
    transparent  ← same both modes
    transparent




12. Dialog & Modal System
    Token / Variable
    Dark Mode Value
    Light Mode Value
    --modal-backdrop
    rgba(0,0,0,0.60)
    rgba(13,26,46,0.45)
    dialog background
    --surface-glass
    --surface-glass  (auto-switches)
    dialog border
    --border-medium
    --border-medium  (auto-switches)
    border-radius
    24px–32px  ← same both modes
    24px–32px




13. Data Visualization
    13.1 Chart Colors
    Chart data colors follow the same accent token logic. Use the variables, not hardcoded hex, so charts automatically adapt.

Token / Variable
Dark Mode Value
Light Mode Value
Primary data series
#00ffb4  →  var(--accent-primary)
#007a58  →  var(--accent-primary)
Secondary data series
#4b7cff  →  var(--accent-secondary)
#1563c4  →  var(--accent-secondary)
Benchmark / neutral
#ffffff
#0d1a2e
Grid lines
rgba(255,255,255,0.10)
rgba(30,60,120,0.10)
Chart backgrounds
transparent  ← same both modes
transparent




14. Quasar / Vue Implementation
    14.1 SCSS Variable File Structure
    All tokens should be defined in variables.scss as CSS custom properties, not SCSS variables. This is what enables runtime switching without a page reload.

:root {
/* Background */
--bg-primary: #0b1020;
--bg-secondary: #11182d;
--bg-tertiary: #151f38;

/* Surfaces */
--surface-glass: rgba(255,255,255,0.04);
--surface-glass-hover: rgba(255,255,255,0.06);

/* Borders */
--border-soft: rgba(255,255,255,0.06);
--border-medium: rgba(255,255,255,0.10);
--border-accent: rgba(0,255,180,0.25);

/* Accents */
--accent-primary: #00ffb4;
--accent-secondary: #0088ff;
--accent-purple: #7b61ff;
--accent-warning: #ffb84d;
--accent-danger: #ff5f7a;

/* Text */
--text-primary: #ffffff;
--text-secondary: rgba(255,255,255,0.72);
--text-muted: rgba(255,255,255,0.55);
--text-disabled: rgba(255,255,255,0.35);

/* Gradients */
--hero-gradient: linear-gradient(135deg, #00ffb4, #0088ff);
--radial-tl: rgba(0,255,180,0.08);
--radial-br: rgba(0,120,255,0.08);

/* Component tokens */
--card-shadow: 0 10px 40px rgba(0,0,0,0.35);
--progress-track: rgba(255,255,255,0.08);
--btn-bg: rgba(255,255,255,0.05);
--btn-border: rgba(255,255,255,0.08);
--input-bg: rgba(255,255,255,0.04);
--input-border: rgba(255,255,255,0.08);
--badge-bg: rgba(0,255,180,0.10);
--badge-color: #00ffb4;
--nav-active-bg: rgba(0,255,180,0.10);
--nav-active-color: #00ffb4;
}

14.2 Light Mode Override Block
All light mode overrides go in a single .light block. Only tokens that differ are listed here — everything else inherits the dark default.

.light {
/* Background */
--bg-primary: #f0f4f8;
--bg-secondary: #e4eaf3;
--bg-tertiary: #d8e2ef;

/* Surfaces */
--surface-glass: rgba(255,255,255,0.72);
--surface-glass-hover: rgba(255,255,255,0.85);
--surface-glass-strong: rgba(255,255,255,0.92);

/* Borders */
--border-soft: rgba(30,60,120,0.08);
--border-medium: rgba(30,60,120,0.14);
--border-accent: rgba(0,122,88,0.30);

/* Accents  —  WCAG AA compliant on light backgrounds */
--accent-primary: #007a58;
--accent-secondary: #1563c4;
--accent-purple: #5b3fcf;
--accent-warning: #c07a00;
--accent-danger: #d0294a;

/* Text */
--text-primary: #0d1a2e;
--text-secondary: rgba(13,26,46,0.72);
--text-muted: rgba(13,26,46,0.50);
--text-disabled: rgba(13,26,46,0.30);

/* Gradients */
--hero-gradient: linear-gradient(135deg, #007a58, #1563c4);
--radial-tl: rgba(0,160,110,0.07);
--radial-br: rgba(21,99,196,0.07);

/* Component tokens */
--card-shadow: 0 10px 40px rgba(30,60,120,0.10);
--progress-track: rgba(30,60,120,0.10);
--btn-bg: rgba(255,255,255,0.75);
--btn-border: rgba(30,60,120,0.14);
--input-bg: rgba(255,255,255,0.82);
--input-border: rgba(30,60,120,0.14);
--badge-bg: rgba(0,122,88,0.10);
--badge-color: #007a58;
--nav-active-bg: rgba(0,122,88,0.10);
--nav-active-color: #007a58;
}

14.3 Toggle Component (Vue)
The theme toggle button should read and write to localStorage for persistence across sessions. Apply the class to the <body> element or <div id='q-app'>.

<template>
  <q-btn round flat @click='toggleTheme'>
    <q-icon :name='isDark ? "light_mode" : "dark_mode"' />
  </q-btn>
</template>

<script setup>
import { ref, onMounted } from 'vue';

const isDark = ref(true);

onMounted(() => {
  const saved = localStorage.getItem('theme');
  isDark.value = saved !== 'light';
  applyTheme(isDark.value);
});

function toggleTheme() {
  isDark.value = !isDark.value;
  applyTheme(isDark.value);
  localStorage.setItem('theme', isDark.value ? 'dark' : 'light');
}

function applyTheme(dark) {
  document.body.classList.toggle('light', !dark);
}
</script>



15. Designer Rules — Dos and Don'ts
    15.1 Always
    ▸  Use CSS custom properties (var(--token-name)), never hardcode hex values in components.
    ▸  Test every new component or screen in both modes before sign-off.
    ▸  Use the light-mode accent tokens when designing for light mode — never the neon originals.
    ▸  Verify text contrast meets WCAG AA (4.5:1 for body, 3:1 for large text) in both modes.
    ▸  Keep backdrop-filter: blur(18px) on glass cards in both modes.
    ▸  Use the same border-radius values in both modes — shape does not change.
    ▸  Apply transitions to color properties so the mode switch feels smooth, not jarring.
    ▸  Use glass surfaces, large radii, accent gradients, spacious layouts, strong typography hierarchy.

15.2 Never
▸  Never use #00ffb4, #0088ff, or any neon accent directly on a light background.
▸  Never hardcode rgba(255,255,255,X) for text in a component — it will be invisible on light backgrounds. Use var(--text-primary) etc.
▸  Never use pure #000000 or #ffffff as a page background in either mode.
▸  Never add a new color that is not defined as a CSS custom property in both :root and .light.
▸  Never create a component that checks isDark in JS to swap colors — use tokens instead.
▸  Never use sharp corners, dense enterprise tables, tiny text, harsh shadows, or excessive animation.



16. Tokens That Do Not Change Between Modes
    The following values are identical in dark and light mode and should never be overridden in the .light block:

▸  All border-radius values (12px, 16px, 20px, 24px, 28px, 999px)
▸  All transition values (all 0.2s ease)
▸  All backdrop-filter values (blur(10px), blur(18px))
▸  All animation durations and easing functions
▸  All font sizes, weights, and line heights (see Section 2.2)
▸  All spacing values (8px, 12px, 16px, 24px, 32px, 48px)
▸  Grid layout templates and gap values
▸  Accent button text color (#081018 — legible on both gradient variants)
▸  Avatar text color (#081018 — same reason)



17. Quasar Theme Variables (quasar.variables.scss)
    Quasar's own theme variables should also be dual-mode aware. Use the scss variable as a fallback, but prefer the CSS custom property approach above for runtime switching.

// Dark mode (default)
$primary:   #00ffb4;
$secondary: #0088ff;
$accent:    #7b61ff;
$dark:      #0b1020;
$dark-page: #0b1020;
$positive:  #00ffb4;
$negative:  #ff5f7a;
$warning:   #ffb84d;
$info:      #4b7cff;

// Light mode — override at the Quasar plugin level via setCssVar()
// or apply the .light class and let the CSS custom properties take over.
// setCssVar('primary', '#007a58') — call this in your toggleTheme function.
