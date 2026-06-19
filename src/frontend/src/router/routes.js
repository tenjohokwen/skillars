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

      // Coach registration flow (guest only)
      {
        path: 'coach-register',
        component: () => import('pages/auth/CoachRegisterPage.vue'),
        meta: { requiresGuest: true },
      },
      {
        path: 'coach/email-pending',
        component: () => import('pages/auth/CoachEmailPendingPage.vue'),
        meta: { requiresGuest: true },
      },
      {
        path: 'coach/verify-email',
        component: () => import('pages/auth/CoachEmailVerifyPage.vue'),
        meta: { requiresGuest: true },
      },
      {
        path: 'coach/verify-phone',
        component: () => import('pages/auth/CoachPhoneVerifyPage.vue'),
        meta: { requiresGuest: true },
      },
      {
        path: 'coach/profile-builder',
        component: () => import('pages/auth/CoachProfileBuilderPlaceholderPage.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'coach/command-center',
        component: () => import('pages/coach/CoachCommandCenterPage.vue'),
        meta: { requiresAuth: true, requiresCoach: true },
      },
      {
        path: 'coach/availability',
        name: 'coach-availability',
        component: () => import('pages/coach/AvailabilityManagerPage.vue'),
        meta: { requiresAuth: true, requiresCoach: true },
      },

      // Parent registration flow (guest only)
      {
        path: 'parent-register',
        component: () => import('pages/auth/ParentRegisterPage.vue'),
        meta: { requiresGuest: true },
      },
      {
        path: 'parent/email-pending',
        component: () => import('pages/auth/ParentEmailPendingPage.vue'),
        meta: { requiresGuest: true },
      },
      {
        path: 'parent/verify-email',
        component: () => import('pages/auth/ParentEmailVerifyPage.vue'),
        meta: { requiresGuest: true },
      },
      {
        path: 'parent/verify-phone',
        component: () => import('pages/auth/ParentPhoneVerifyPage.vue'),
        meta: { requiresGuest: true },
      },
      {
        path: 'parent/create-player',
        component: () => import('pages/auth/CreatePlayerProfilePage.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'parent/dashboard',
        component: () => import('pages/auth/ParentDashboardPlaceholderPage.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'parent/coaches/:coachId/purchase-sessions',
        component: () => import('pages/parent/SessionPackPurchasePage.vue'),
        meta: { requiresAuth: true, role: 'PARENT' },
      },
      {
        path: 'parent/players/:playerId/packs',
        component: () => import('pages/parent/SessionPackDashboardPage.vue'),
        meta: { requiresAuth: true, role: 'PARENT' },
      },
      {
        path: 'parent/players/:playerId/sessions',
        component: () => import('pages/parent/ParentPlayerPortalPage.vue'),
        meta: { requiresAuth: true, role: 'PARENT' },
      },
      {
        path: 'parent/coaches/:coachId/request-booking',
        component: () => import('pages/parent/BookingRequestPage.vue'),
        meta: { requiresAuth: true, role: 'PARENT' },
      },
      {
        path: 'parent/bookings',
        name: 'parent-bookings',
        component: () => import('pages/parent/ParentBookingsPage.vue'),
        meta: { requiresAuth: true, role: 'PARENT' },
      },
      {
        path: 'coach/booking-requests',
        name: 'coach-booking-requests',
        component: () => import('pages/coach/CoachBookingRequestsPage.vue'),
        meta: { requiresAuth: true, requiresCoach: true },
      },
      {
        path: 'coach/drills',
        name: 'coach-drill-library',
        component: () => import('pages/coach/DrillLibraryPage.vue'),
        meta: { requiresAuth: true, requiresCoach: true },
      },
      {
        path: 'coach/session-builder/:bookingId',
        name: 'coach-session-builder',
        component: () => import('pages/coach/SessionBuilderPage.vue'),
        meta: { requiresAuth: true, requiresCoach: true },
      },
      {
        path: 'coach/session-templates',
        name: 'coach-session-templates',
        component: () => import('pages/coach/SessionTemplateVault.vue'),
        meta: { requiresAuth: true, requiresCoach: true },
      },
      {
        path: 'player/locker-room/:playerId',
        name: 'player-locker-room',
        component: () => import('pages/player/PlayerLockerRoomPlaceholderPage.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'player/development/:playerId',
        name: 'player-development',
        component: () => import('pages/player/PlayerDevelopmentDashboardPage.vue'),
        meta: { requiresAuth: true },
      },

      // Marketplace — public (guests can browse, AC 5, FR-MKT-005)
      {
        path: 'marketplace',
        component: () => import('pages/marketplace/MarketplacePage.vue'),
      },
      {
        path: 'coaches/:coachId',
        component: () => import('pages/marketplace/CoachPublicProfilePage.vue'),
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
