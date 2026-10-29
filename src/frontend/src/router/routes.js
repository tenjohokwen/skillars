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
        component: () => import('pages/coach/CoachProfileBuilderPage.vue'),
        // requiresCoach added 2026-10-01: this page calls coach-only APIs, but the route itself
        // only required login, not the COACH role. A stale/forged redirect (or a bookmark, or the
        // browser back button) landing a non-coach account here produced a raw 401
        // ("security.unauthorized") instead of the router guard bouncing them to their own
        // role's home page the way every sibling coach-only route already does.
        meta: { requiresAuth: true, requiresCoach: true },
      },
      {
        path: 'coach/command-center',
        name: 'coach-command-center',
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
        component: () => import('pages/parent/ParentDashboardPage.vue'),
        meta: { requiresAuth: true, role: 'PARENT' },
      },
      {
        // Dual-role (Deferred-81 AC4): a self-registered PLAYER can now purchase their own
        // session pack, mirroring the request-booking route's dual-role widening below.
        path: 'parent/coaches/:coachId/purchase-sessions',
        component: () => import('pages/parent/SessionPackPurchasePage.vue'),
        meta: { requiresAuth: true, roles: ['PARENT', 'PLAYER'] },
      },
      {
        // Dual-role (Deferred-82 AC3): a self-registered PLAYER can now reach their own
        // session-pack dashboard too. The `parent/` prefix is retained for route/code reuse
        // with the parent-managed-player flow, not because this destination is parent-only.
        path: 'parent/players/:playerId(\\d+)/packs',
        component: () => import('pages/parent/SessionPackDashboardPage.vue'),
        meta: { requiresAuth: true, roles: ['PARENT', 'PLAYER'] },
      },
      {
        // Per-child hub: the parent dashboard's child cards, the "My players" drawer entries and
        // ParentChildSwitcher land here; it links on to the child's other per-child pages.
        path: 'parent/players/:playerId(\\d+)/sessions',
        name: 'parent-player-hub',
        component: () => import('pages/parent/ParentPlayerPortalPage.vue'),
        meta: { requiresAuth: true, role: 'PARENT' },
      },
      {
        // A parent's view of one linked child's videos — play and delete (D3).
        path: 'parent/players/:playerId(\\d+)/videos',
        name: 'parent-child-videos',
        component: () => import('pages/parent/ParentChildVideosPage.vue'),
        meta: { requiresAuth: true, role: 'PARENT' },
      },
      {
        path: 'parent/players/:playerId(\\d+)/development',
        name: 'parent-development',
        component: () => import('pages/parent/ParentDevelopmentPortalPage.vue'),
        meta: { requiresAuth: true, role: 'PARENT' },
      },
      {
        // Dual-role (UAT.5 AC4): a self-registered PLAYER can also request a booking, for
        // themselves. The FIRST dual-role route in this codebase — see router/index.js's
        // meta.roles guard.
        path: 'parent/coaches/:coachId/request-booking',
        component: () => import('pages/parent/BookingRequestPage.vue'),
        meta: { requiresAuth: true, roles: ['PARENT', 'PLAYER'] },
      },
      {
        // Dual-role (UAT.5 AC4): reused by a self-registered PLAYER's own bookings list rather
        // than building a parallel page — see ParentBookingsPage.vue's authStore.isParent button
        // guards.
        path: 'parent/bookings',
        name: 'parent-bookings',
        component: () => import('pages/parent/ParentBookingsPage.vue'),
        meta: { requiresAuth: true, roles: ['PARENT', 'PLAYER'] },
      },
      {
        path: 'coach/players',
        name: 'coach-players',
        component: () => import('pages/coach/MyPlayersPage.vue'),
        meta: { requiresAuth: true, requiresCoach: true },
      },
      {
        path: 'coach/players/:playerId(\\d+)',
        name: 'coach-player-detail',
        component: () => import('pages/coach/CoachPlayerDetailPage.vue'),
        meta: { requiresAuth: true, requiresCoach: true },
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
        path: 'coach/payment-settings',
        name: 'coach-payment-settings',
        component: () => import('pages/coach/CoachPaymentSettingsPage.vue'),
        meta: { requiresAuth: true, requiresCoach: true },
      },
      {
        path: 'coach/reliability',
        name: 'coach-reliability',
        component: () => import('pages/coach/CoachReliabilityPage.vue'),
        meta: { requiresAuth: true, requiresCoach: true },
      },
      {
        // Coach video management — the same page as player/videos, role-aware (upload type choice,
        // coach initiate endpoint). GET /api/video/my and /quotas/me already admit ROLE_COACH.
        path: 'coach/videos',
        name: 'coach-video-management',
        component: () => import('pages/VideoManagementPage.vue'),
        meta: { requiresAuth: true, requiresCoach: true },
      },
      {
        path: 'coach/subscription',
        name: 'coach-subscription',
        component: () => import('pages/coach/CoachSubscriptionPage.vue'),
        meta: { requiresAuth: true, requiresCoach: true },
      },
      {
        // skillars-deferred-151 AC1: coach reviews self-service — GET /api/reviews/coaches/me +
        // POST /api/reviews/{id}/response existed with zero frontend caller before this story.
        path: 'coach/reviews',
        name: 'coach-reviews',
        component: () => import('pages/coach/CoachReviewsPage.vue'),
        meta: { requiresAuth: true, requiresCoach: true },
      },
      {
        // skillars-deferred-151 AC2: coach session-pack offer management — current-offer + history
        // model (not a catalog, per the backend's one-active-tier-per-coach DB constraint).
        path: 'coach/session-pack-tiers',
        name: 'coach-session-pack-tiers',
        component: () => import('pages/coach/CoachSessionPackTiersPage.vue'),
        meta: { requiresAuth: true, requiresCoach: true },
      },
      {
        // Dual-role (skillars-deferred-149 AC1): a self-registered PLAYER can now view their own
        // player subscription too — PlayerSubscriptionPage.vue gates the parent-only mutating
        // controls (cancel/subscribe/change-tier) on authStore.isParent for this role.
        path: 'parent/player/:playerId(\\d+)/subscription',
        name: 'player-subscription',
        component: () => import('pages/parent/PlayerSubscriptionPage.vue'),
        meta: { requiresAuth: true, roles: ['PARENT', 'PLAYER'] },
      },
      // Player registration flow (guest only) — adult (18+) self-registration
      {
        path: 'player-register',
        component: () => import('pages/auth/PlayerRegisterPage.vue'),
        meta: { requiresGuest: true },
      },
      {
        path: 'player/email-pending',
        component: () => import('pages/auth/PlayerEmailPendingPage.vue'),
        meta: { requiresGuest: true },
      },
      {
        path: 'player/verify-email',
        component: () => import('pages/auth/PlayerEmailVerifyPage.vue'),
        meta: { requiresGuest: true },
      },
      {
        path: 'player/verify-phone',
        component: () => import('pages/auth/PlayerPhoneVerifyPage.vue'),
        meta: { requiresGuest: true },
      },
      {
        path: 'player/profile-builder',
        component: () => import('pages/auth/PlayerProfileBuilderPage.vue'),
        meta: { requiresAuth: true },
      },
      {
        // ROLE_ROUTES.PLAYER landing target — resolves the caller's own playerId then redirects,
        // since /player/locker-room/:playerId can't be a login redirect target on its own.
        path: 'player/home',
        component: () => import('pages/auth/PlayerHomeRedirectPage.vue'),
        meta: { requiresAuth: true, role: 'PLAYER' },
      },
      {
        // Dual-role (skillars-deferred-148 Finding 4 correction): PlayerOwnershipGuard authorizes
        // both a self-registered PLAYER and a PARENT on their own/their child's player profile —
        // the parent child hub (ParentPlayerPortalPage.vue) links here, relying on the PARENT half.
        path: 'player/locker-room/:playerId(\\d+)',
        name: 'player-locker-room',
        component: () => import('pages/player/PlayerLockerRoomPage.vue'),
        meta: { requiresAuth: true, roles: ['PLAYER', 'PARENT'] },
      },
      {
        path: 'player/development/:playerId(\\d+)',
        name: 'player-development',
        component: () => import('pages/player/PlayerDevelopmentDashboardPage.vue'),
        meta: { requiresAuth: true, role: 'PLAYER' },
      },
      {
        path: 'player/videos',
        name: 'player-video-management',
        component: () => import('pages/VideoManagementPage.vue'),
        meta: { requiresAuth: true, role: 'PLAYER' },
      },
      {
        // Nav-menu destination only — NOT ROLE_ROUTES.PLAYER (that stays 'player/home', the
        // profile-completeness redirect gate). skillars-deferred-141 AC4.2.
        path: 'player/dashboard',
        name: 'player-dashboard',
        component: () => import('pages/player/PlayerDashboardPage.vue'),
        meta: { requiresAuth: true, role: 'PLAYER' },
      },
      {
        // The parental video-approval page is gone (ADR "Minors never upload", 2026-10-28): minors
        // can no longer upload. Kept as a redirect only so links in already-sent approval emails and
        // stored inbox rows land on the parent dashboard instead of the not-found page.
        path: 'parent/approvals',
        redirect: '/parent/dashboard',
      },
      {
        // Dual-role (fix/player-wallet-cashout): a self-registered adult player pays for their own sessions
        // and packs, so the refund credit in their wallet is theirs to cash out under the parent's rules. The
        // backend gates balance + cash-out on the wallet owner (a PARENT, or a PLAYER whose own profile has no
        // parent) and keys the wallet on the caller's own user id.
        path: 'parent/credit-wallet',
        name: 'parent-credit-wallet',
        component: () => import('pages/parent/ParentCreditWalletPage.vue'),
        meta: { requiresAuth: true, roles: ['PARENT', 'PLAYER'] },
      },
      {
        // Dual-role (feat/coach-workflow-gaps A23): a self-registered adult player pays for their own
        // sessions, so their credit statement and receipts are theirs to read. The backend scopes both
        // to the caller's own user id (GET /api/payment/credits/statement and
        // /api/payment/parents/bookings/{id}/receipt). Cash-out (parent-credit-wallet) is dual-role too
        // since fix/player-wallet-cashout.
        path: 'parent/credit-statement',
        name: 'parent-credit-statement',
        component: () => import('pages/parent/CreditStatementPage.vue'),
        meta: { requiresAuth: true, roles: ['PARENT', 'PLAYER'] },
      },
      {
        path: 'parent/bookings/:bookingId/receipt',
        name: 'parent-receipt',
        component: () => import('pages/parent/ParentReceiptView.vue'),
        meta: { requiresAuth: true, roles: ['PARENT', 'PLAYER'] },
      },
      {
        path: 'coach/revenue',
        name: 'coach-revenue',
        component: () => import('pages/coach/RevenueDashboardPage.vue'),
        meta: { requiresAuth: true, role: 'COACH' },
      },
      {
        path: 'messaging',
        name: 'messaging',
        component: () => import('pages/messaging/MessagingPage.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'coach/bookings/:bookingId/receipt',
        name: 'coach-receipt',
        component: () => import('pages/coach/ReceiptView.vue'),
        meta: { requiresAuth: true, role: 'COACH' },
      },

      // Marketplace — public (guests can browse, AC 5, FR-MKT-005)
      {
        path: 'marketplace',
        name: 'marketplace',
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
      // Notification centre (UI-gap chunk C9): full inbox, every authenticated role.
      {
        path: 'notifications',
        name: 'notifications',
        component: () => import('pages/NotificationsPage.vue'),
        meta: { requiresAuth: true },
      },
      // Public landing page for the unsubscribe link in opt-out-able emails — no meta, so it works
      // both logged in and logged out (the signed token is the credential).
      {
        path: 'unsubscribe',
        name: 'unsubscribe',
        component: () => import('pages/UnsubscribePage.vue'),
      },

      // Admin pages (auth required)
      {
        path: 'admin',
        meta: { requiresAuth: true, roles: ['ADMIN'] },
        children: [
          {
            path: 'health-dashboard',
            component: () => import('pages/admin/HealthDashboardPage.vue'),
            meta: { requiresAuth: true, roles: ['ADMIN'] },
          },
          {
            path: 'disputes',
            component: () => import('pages/admin/AdminDisputesPage.vue'),
            meta: { requiresAuth: true, roles: ['ADMIN'] },
          },
          {
            path: 'moderation',
            component: () => import('pages/admin/AdminModerationPage.vue'),
            meta: { requiresAuth: true, roles: ['ADMIN'] },
          },
          {
            path: 'coaches',
            component: () => import('pages/admin/AdminCoachEnforcementPage.vue'),
            meta: { requiresAuth: true, roles: ['ADMIN'] },
          },
          {
            path: 'users',
            component: () => import('pages/admin/AdminUsersPage.vue'),
            meta: { requiresAuth: true, roles: ['ADMIN'] },
          },
          {
            path: 'appeals',
            component: () => import('pages/admin/AdminAppealsPage.vue'),
            meta: { requiresAuth: true, roles: ['ADMIN'] },
          },
          {
            path: 'gdpr',
            component: () => import('pages/admin/AdminGdprRequestsPage.vue'),
            meta: { requiresAuth: true, roles: ['ADMIN'] },
          },
          {
            path: 'finance',
            component: () => import('pages/admin/AdminFinancePage.vue'),
            meta: { requiresAuth: true, roles: ['ADMIN'] },
          },
          {
            path: 'bookings',
            component: () => import('pages/admin/AdminBookingsPage.vue'),
            meta: { requiresAuth: true, roles: ['ADMIN'] },
          },
          {
            path: 'videos',
            component: () => import('pages/admin/AdminVideosPage.vue'),
            meta: { requiresAuth: true, roles: ['ADMIN'] },
          },
          {
            path: 'config',
            component: () => import('pages/admin/AdminConfigPage.vue'),
            meta: { requiresAuth: true, roles: ['ADMIN'] },
          },
        ],
      },
    ],
  },

  // Catch-all 404. meta.notFound lets safeRedirect.js's isSafeRedirect tell "landed on the 404
  // page" apart from "matched a real route" — router.resolve(path).matched.length > 0 is NOT
  // enough on its own, since this catch-all matches every `/`-prefixed path (skillars-deferred-144).
  {
    path: '/:catchAll(.*)*',
    component: () => import('pages/ErrorNotFound.vue'),
    meta: { notFound: true },
  },
]

export default routes
