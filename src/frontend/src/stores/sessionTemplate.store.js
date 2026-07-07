import { defineStore } from 'pinia'
import { ref } from 'vue'
import { sessionApi } from 'src/api/session.api'

export const useSessionTemplateStore = defineStore('sessionTemplate', () => {
  const templates = ref([])
  const loading = ref(false)
  const error = ref(null)

  async function fetchTemplates() {
    loading.value = true
    try {
      const res = await sessionApi.listTemplates()
      templates.value = res
    } catch (e) {
      error.value = e
    } finally {
      loading.value = false
    }
  }

  async function createTemplate(sessionId, name) {
    const res = await sessionApi.createTemplate({ sessionId, name })
    templates.value.unshift(res)
    return res
  }

  async function renameTemplate(templateId, name) {
    try {
      await sessionApi.renameTemplate(templateId, { name })
      const t = templates.value.find(t => t.id === templateId)
      if (t) t.name = name
    } catch (e) {
      error.value = e
      throw e
    }
  }

  async function deleteTemplate(templateId) {
    try {
      await sessionApi.deleteTemplate(templateId)
      templates.value = templates.value.filter(t => t.id !== templateId)
    } catch (e) {
      error.value = e
      throw e
    }
  }

  async function deployTemplate(templateId, bookingId) {
    try {
      const res = await sessionApi.deployTemplate(templateId, bookingId)
      const t = templates.value.find(t => t.id === templateId)
      if (t) {
        t.deployCount = (t.deployCount ?? 0) + 1
        t.lastDeployedAt = new Date().toISOString()
      }
      return res
    } catch (e) {
      error.value = e
      throw e
    }
  }

  return { templates, loading, error, fetchTemplates, createTemplate, renameTemplate, deleteTemplate, deployTemplate }
})
