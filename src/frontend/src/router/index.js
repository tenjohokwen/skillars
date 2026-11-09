import { defineRouter } from '#q-app/wrappers'
import { routeForRole } from './roleRoutes'
import {
  createRouter,
  createMemoryHistory,
  createWebHistory,
  createWebHashHistory,
} from 'vue-router'
import routes from './routes'
import { useAuthStore } from 'src/stores/auth.store'
import { useProfileBuilderStore } from 'src/stores/profileBuilder.store'
import { Notify } from 'quasar'
import { i18n } from 'src/boot/i18n'

/*
 * If not building with SSR mode, you can
 * directly export the Router instantiation;
 *
 * The function below can be async too; either use
 * async/await or return a Promise which resolves
 * with the Router instance.
 */

export default defineRouter(function (/* { store, ssrContext } */) {
  const createHistory = process.env.SERVER
    ? createMemoryHistory
    : process.env.VUE_ROUTER_MODE === 'history'
      ? createWebHistory
      : createWebHashHistory

  const Router = createRouter({
    scrollBehavior: () => ({ left: 0, top: 0 }),
    routes,

    // Leave this as is and make changes in quasar.conf.js instead!
    // quasar.conf.js -> build -> vueRouterMode
    // quasar.conf.js -> build -> publicPath
    history: createHistory(process.env.VUE_ROUTER_BASE),
  })

  let hydrated = false
  Router.beforeEach(async (to, from, next) => {
    const authStore = useAuthStore()
    if (!hydrated) {
      authStore.hydrateFromCookie()
      hydrated = true
    }

    const requiresAuth = to.matched.some((r) => r.meta.requiresAuth)
    const requiresGuest = to.matched.some((r) => r.meta.requiresGuest)
    const requiresCoach = to.matched.some((r) => r.meta.requiresCoach)
    // Single-role gate: EVERY meta.role value is enforced, not just a hard-coded subset. This used
    // to check only 'PARENT' and 'PLAYER', so routes declaring meta.role 'COACH' (coach/revenue,
    // coach/bookings/:bookingId/receipt) were login-gated only — any signed-in role could open them.
    const requiredRoles = to.matched.map((r) => r.meta.role).filter(Boolean)
    // Dual-role routes (UAT.5 AC4): meta.roles is additive to the single-value meta.role gates
    // above, scoped to the handful of routes that need it, rather than migrating every existing
    // route's meta.role/requiresParent/requiresCoach in the same diff.
    const rolesMeta = to.matched.flatMap((r) => r.meta.roles || [])
    const requiresOneOfRoles = rolesMeta.length > 0
    const isAuthenticated = authStore.isAuthenticated

    if (requiresAuth && !isAuthenticated) {
      next({ path: '/login', query: { redirect: to.fullPath } })
      return
    }

    if (requiresGuest && isAuthenticated) {
      next(routeForRole(authStore.role))
      return
    }

    if (requiresCoach && isAuthenticated && !authStore.isCoach) {
      next(routeForRole(authStore.role))
      return
    }

    if (isAuthenticated && requiredRoles.some((role) => role !== authStore.role)) {
      next(routeForRole(authStore.role))
      return
    }

    if (requiresOneOfRoles && isAuthenticated && !rolesMeta.includes(authStore.role)) {
      next(routeForRole(authStore.role))
      return
    }

    // S-28: pages whose every action needs a full administrator (finance, GDPR, config). A limited
    // administrator gets an explanation instead of a page full of 403s. The API enforces this too.
    if (isAuthenticated && authStore.limitedAdmin && to.matched.some((r) => r.meta.fullAdmin)) {
      next('/admin/requires-administrator')
      return
    }

    if (to.matched.some((r) => r.path === '/coach/command-center') && authStore.isCoach) {
      const pbStore = useProfileBuilderStore()
      await pbStore.loadStatus()
      if (!pbStore.isComplete) {
        next('/coach/profile-builder')
        return
      }
    }

    next()
  })

  // Pitchside X-10: a page whose code cannot be fetched (offline, or a deploy replaced the chunk) used to abort the
  // navigation silently: the click did nothing. Say so, and offer a retry. The browser caches a failed module
  // import, so the retry is a full reload into the target route rather than another in-app navigation.
  Router.onError((error, to) => {
    if (!isChunkLoadError(error)) return
    Notify.create({
      type: 'negative',
      message: i18n.global.t('common.pageLoadFailed'),
      timeout: 0,
      attrs: { 'data-testid': 'navigation-error' },
      actions: [
        {
          label: i18n.global.t('common.retry'),
          color: 'white',
          'data-testid': 'navigation-retry',
          handler: () => {
            window.location.hash = `#${to.fullPath}`
            window.location.reload()
          },
        },
      ],
    })
  })

  return Router
})

/**
 * A failed dynamic import (Chrome, Firefox and Safari word it differently), or Vite's own preload of the page's
 * CSS failing first ("Unable to preload CSS for …"), which is what an offline navigation usually hits.
 */
export function isChunkLoadError(error) {
  const message = String(error?.message ?? error ?? '')
  return /dynamically imported module|Importing a module script failed|Failed to fetch|ChunkLoadError|Unable to preload/i.test(
    message,
  )
}
