import { api } from 'src/boot/axios'

export const listCoachReviews = (coachId, page = 0, sort = 'newest') =>
  api.get(`/api/reviews/coaches/${coachId}`, { params: { page, sort } })

export const getMyReviewForCoach = (coachId) => api.get(`/api/reviews/me/coaches/${coachId}`)

export const submitReview = (coachId, payload) => api.post(`/api/reviews/coaches/${coachId}`, payload)

export const updateReview = (reviewId, payload) => api.patch(`/api/reviews/${reviewId}`, payload)
