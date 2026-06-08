import { api } from 'src/boot/axios'

export const adminApi = {
  /**
   * Get the Spring Boot Actuator health response.
   * Admin JWT required to see component details (components field absent for non-admin).
   * Returns: { status: 'UP'|'DOWN', components?: { [name]: { status, details? } } }
   */
  getHealth() {
    // Actuator is on a different port (8367) than the main app (9990).
    const actuatorBase = `${window.location.protocol}//${window.location.hostname}:8367`
    return api.get(`${actuatorBase}/manage/health`)
  },

  // --- Tenant Management (Phase 33) ---

  createTenant(data) {
    return api.post('/v1/admin/tenants', data)
  },

  listTenants(params = {}) {
    return api.get('/v1/admin/tenants', { params })
  },

  getTenantDetail(tenantRef) {
    return api.get(`/v1/admin/tenants/${tenantRef}`)
  },

  getWebhookSecret(tenantRef) {
    return api.get(`/v1/admin/tenants/${tenantRef}/webhook-secret`)
  },

  updateTenantName(tenantRef, data) {
    return api.patch(`/v1/admin/tenants/${tenantRef}/name`, data)
  },

  updateTenantEmail(tenantRef, data) {
    return api.patch(`/v1/admin/tenants/${tenantRef}/email`, data)
  },

  updateTenantWebhookUrl(tenantRef, data) {
    return api.patch(`/v1/admin/tenants/${tenantRef}/webhook-url`, data)
  },

  suspendTenant(tenantRef) {
    return api.post(`/v1/admin/tenants/${tenantRef}/suspend`)
  },

  reactivateTenant(tenantRef) {
    return api.post(`/v1/admin/tenants/${tenantRef}/reactivate`)
  },

  regenerateWebhookSecret(tenantRef) {
    return api.post(`/v1/admin/tenants/${tenantRef}/webhook-secret`)
  },

  generateKey(tenantRef, env) {
    return api.post(`/v1/admin/tenants/${tenantRef}/keys/generate`, null, { params: { env } })
  },

  rotateKey(tenantId, keyId) {
    return api.post(`/v1/admin/tenants/${tenantId}/keys/${keyId}/rotate`)
  },

  revokeKey(tenantId, keyId) {
    return api.delete(`/v1/admin/tenants/${tenantId}/keys/${keyId}`)
  },

  reactivateKey(tenantId, keyId) {
    return api.post(`/v1/admin/tenants/${tenantId}/keys/${keyId}/reactivate`)
  },
}
