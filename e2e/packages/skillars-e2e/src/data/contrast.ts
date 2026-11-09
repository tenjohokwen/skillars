import type { Page } from '@playwright/test'

/** One piece of visible text whose colour hardly differs from what is behind it. */
export interface LowContrast {
  text: string
  ratio: number
  color: string
  background: string
}

/**
 * Suite X-05: text the eye can't separate from its background. Walks the visible text on the page and, for each
 * element that holds some, composites the colours behind it (a translucent glass card over the page) to the
 * background actually painted, then takes the WCAG contrast ratio. A ratio under `minimum` is reported. Elements
 * over a background image or gradient are skipped: their backdrop is not one colour.
 */
export async function lowContrastText(page: Page, minimum = 3): Promise<LowContrast[]> {
  return page.evaluate((min) => {
    type Rgba = [number, number, number, number]
    const parse = (css: string): Rgba | null => {
      const m = css.match(/rgba?\(([^)]+)\)/)
      if (!m) return null
      const parts = m[1]!
        .split(/[ ,/]+/)
        .filter(Boolean)
        .map(Number)
      return [parts[0]!, parts[1]!, parts[2]!, parts.length > 3 ? parts[3]! : 1]
    }
    const over = (top: Rgba, under: Rgba): Rgba => {
      const a = top[3] + under[3] * (1 - top[3])
      if (a === 0) return [0, 0, 0, 0]
      const mix = (i: number) => (top[i]! * top[3] + under[i]! * under[3] * (1 - top[3])) / a
      return [mix(0), mix(1), mix(2), a]
    }
    const luminance = ([r, g, b]: Rgba) => {
      const lin = (c: number) => {
        const s = c / 255
        return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
      }
      return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
    }
    /** The colour painted behind `el`, or null when an image or gradient is in the way. */
    const backdrop = (el: Element): Rgba | null => {
      const layers: Rgba[] = []
      for (let node: Element | null = el; node; node = node.parentElement) {
        const style = getComputedStyle(node)
        if (style.backgroundImage && style.backgroundImage !== 'none') return null
        const bg = parse(style.backgroundColor)
        if (bg && bg[3] > 0) {
          layers.push(bg)
          if (bg[3] >= 1) break
        }
      }
      let painted: Rgba = [255, 255, 255, 1]
      for (const layer of layers.reverse()) painted = over(layer, painted)
      return painted
    }
    const found: { text: string; ratio: number; color: string; background: string }[] = []
    const seen = new Set<Element>()
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT)
    for (let node = walker.nextNode(); node && found.length < 25; node = walker.nextNode()) {
      const text = node.textContent?.trim()
      const el = node.parentElement
      if (!text || !el || seen.has(el)) continue
      seen.add(el)
      const rect = el.getBoundingClientRect()
      const style = getComputedStyle(el)
      if (rect.width === 0 || rect.height === 0 || style.visibility === 'hidden') continue
      if (Number(style.opacity) === 0 || el.closest('[aria-hidden="true"], .q-skeleton')) continue
      const color = parse(style.color)
      const behind = backdrop(el)
      if (!color || !behind) continue
      const fg = over(color, behind)
      const [l1, l2] = [luminance(fg), luminance(behind)].sort((a, b) => b - a)
      const ratio = (l1! + 0.05) / (l2! + 0.05)
      if (ratio < min) {
        found.push({
          text: text.slice(0, 60),
          ratio: Math.round(ratio * 100) / 100,
          color: style.color,
          background: `rgb(${behind.slice(0, 3).map(Math.round).join(', ')})`,
        })
      }
    }
    return found
  }, minimum)
}

/** Put the app in a theme for every page this browser context opens from now on. */
export async function useTheme(page: Page, theme: 'light' | 'dark'): Promise<void> {
  await page.context().addInitScript((value) => {
    try {
      localStorage.setItem('skillars-theme', value)
    } catch {
      // storage unavailable: the page keeps its default theme
    }
  }, theme)
}
