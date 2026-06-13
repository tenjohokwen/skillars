import { defineRouter } from '#q-app/wrappers'
import {
  createRouter,
  createMemoryHistory,
  createWebHistory,
  createWebHashHistory,
} from 'vue-router'
import routes from './routes'
import { useAuthStore } from 'src/stores/auth.store'
import { useProfileBuilderStore } from 'src/stores/profileBuilder.store'

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

  const ROLE_ROUTES = {
    COACH: '/coach/command-center',
    PARENT: '/parent/dashboard',
    PLAYER: '/player/locker-room',
    ADMIN: '/admin/health-dashboard',
  }

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
    const requiresParent = to.matched.some((r) => r.meta.role === 'PARENT')
    const isAuthenticated = authStore.isAuthenticated

    if (requiresAuth && !isAuthenticated) {
      next({ path: '/login', query: { redirect: to.fullPath } })
      return
    }

    if (requiresGuest && isAuthenticated) {
      next(ROLE_ROUTES[authStore.role] || '/dashboard')
      return
    }

    if (requiresCoach && isAuthenticated && !authStore.isCoach) {
      next(ROLE_ROUTES[authStore.role] || '/dashboard')
      return
    }

    if (requiresParent && isAuthenticated && !authStore.isParent) {
      next(ROLE_ROUTES[authStore.role] || '/dashboard')
      return
    }

    if (to.path === '/coach/command-center' && authStore.isCoach) {
      const pbStore = useProfileBuilderStore()
      await pbStore.loadStatus()
      if (!pbStore.isComplete) {
        next('/coach/profile-builder')
        return
      }
    }

    next()
  })

  return Router
})
