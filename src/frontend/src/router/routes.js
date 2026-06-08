const routes = [
  // All pages wrapped in MainLayout
  {
    path: '/',
    component: () => import('layouts/MainLayout.vue'),
    children: [
      // Root redirect to login
      {
        path: '',
        redirect: '/login',
      },

      // Auth pages (guest only)
      {
        path: 'login',
        component: () => import('pages/auth/LoginPage.vue'),
        meta: { requiresGuest: true },
      },
      {
        path: 'register',
        component: () => import('pages/auth/RegisterPage.vue'),
        meta: { requiresGuest: true },
      },
      {
        path: 'otp',
        component: () => import('pages/auth/OtpPage.vue'),
        meta: { requiresGuest: true },
      },
      {
        path: 'forgot-password',
        component: () => import('pages/auth/ForgotPasswordPage.vue'),
        meta: { requiresGuest: true },
      },
      {
        path: 'reset-password',
        component: () => import('pages/auth/ResetPasswordPage.vue'),
        meta: { requiresGuest: true },
      },
      {
        path: 'activate',
        component: () => import('pages/auth/ActivatePage.vue'),
        meta: { requiresGuest: true },
      },

      // Protected pages (auth required)
      {
        path: 'dashboard',
        component: () => import('pages/DashboardPage.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'profile',
        name: 'profile',
        component: () => import('pages/ProfilePage.vue'),
        meta: { requiresAuth: true },
      },

      // Admin pages (auth required)
      {
        path: 'admin',
        meta: { requiresAuth: true },
        children: [
          {
            path: 'health-dashboard',
            component: () => import('pages/admin/HealthDashboardPage.vue'),
            meta: { requiresAuth: true },
          },
          {
            path: 'tenants',
            component: () => import('pages/admin/TenantListPage.vue'),
            meta: { requiresAuth: true },
          },
          {
            path: 'tenants/:tenantRef',
            component: () => import('pages/admin/TenantDetailPage.vue'),
            meta: { requiresAuth: true },
          },
        ],
      },
    ],
  },

  // Catch-all 404
  {
    path: '/:catchAll(.*)*',
    component: () => import('pages/ErrorNotFound.vue'),
  },
]

export default routes
