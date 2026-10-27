import { api } from 'src/boot/axios'

export const listCoachReviews = (coachId, page = 0, sort = 'newest') =>
  api.get(`/api/reviews/coaches/${coachId}`, { params: { page, sort } })

export const getMyReviewForCoach = (coachId) => api.get(`/api/reviews/me/coaches/${coachId}`)

export const checkReviewEligibility = (coachId) =>
  api.get(`/api/reviews/coaches/${coachId}/eligibility`)

export const submitReview = (coachId, payload) =>
  api.post(`/api/reviews/coaches/${coachId}`, payload)

export const updateReview = (reviewId, payload) => api.patch(`/api/reviews/${reviewId}`, payload)

export const listMyReviews = (page = 0) => api.get('/api/reviews/coaches/me', { params: { page } })

export const submitCoachResponse = (reviewId, body) =>
  api.post(`/api/reviews/${reviewId}/response`, { body })

// skillars-deferred-165 AC5: flag a review for moderation (FAKE_REVIEW | OFFENSIVE_CONTENT |
// CONFLICT_OF_INTEREST | OTHER, details optional, max 500).
export const flagReview = (reviewId, payload) => api.post(`/api/reviews/${reviewId}/flag`, payload)
