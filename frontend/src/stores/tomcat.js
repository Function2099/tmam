import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { extractErrorMessage, tomcatsApi } from '@/api/tmam'

export const useTomcatStore = defineStore('tomcat', () => {
  const currentTomcatId = ref('default')
  const services = ref([])
  const tomcatStatus = ref('STOPPED')
  const externallyManaged = ref(false)
  const currentInstance = ref(null)
  const loading = ref(false)
  const actionLoading = ref(false)
  const enabledDirty = ref(false)
  const savedEnabledByName = ref({})

  const isRunning = computed(() => tomcatStatus.value === 'RUNNING')
  const enabledCount = computed(() => services.value.filter((s) => s.enabled).length)

  function syncSavedEnabled() {
    savedEnabledByName.value = Object.fromEntries(
      services.value.map((s) => [s.name, s.enabled]),
    )
  }

  function getEnabledChanges() {
    const enabled = []
    const disabled = []
    for (const service of services.value) {
      const was = savedEnabledByName.value[service.name]
      if (was === service.enabled) continue
      const label = service.displayName || service.name
      if (service.enabled) enabled.push(label)
      else disabled.push(label)
    }
    return { enabled, disabled }
  }

  function setCurrentTomcat(id) {
    if (currentTomcatId.value !== id) {
      currentTomcatId.value = id
      enabledDirty.value = false
    }
  }

  async function refreshStatus() {
    if (!currentTomcatId.value) return

    try {
      const { data } = await tomcatsApi.status(currentTomcatId.value)
      tomcatStatus.value = data?.status ?? 'STOPPED'
      externallyManaged.value = !!data?.externallyManaged
    } catch {
      tomcatStatus.value = 'STOPPED'
      externallyManaged.value = false
    }
  }

  async function refresh() {
    if (!currentTomcatId.value) return false

    const hadDirty = enabledDirty.value
    enabledDirty.value = false

    loading.value = true
    try {
      const id = currentTomcatId.value
      const [servicesRes, statusRes, instanceRes] = await Promise.all([
        tomcatsApi.services(id),
        tomcatsApi.status(id),
        tomcatsApi.get(id),
      ])
      services.value = servicesRes.data ?? []
      tomcatStatus.value = statusRes.data?.status ?? 'STOPPED'
      externallyManaged.value = !!statusRes.data?.externallyManaged
      currentInstance.value = instanceRes.data ?? null
      syncSavedEnabled()
    } catch {
      services.value = []
      tomcatStatus.value = 'STOPPED'
      externallyManaged.value = false
      currentInstance.value = null
    } finally {
      loading.value = false
    }

    return hadDirty
  }

  function setLocalEnabled(name, enabled) {
    enabledDirty.value = true
    services.value = services.value.map((service) =>
      service.name === name ? { ...service, enabled } : service,
    )
  }

  function setLocalEnabledBulk(updates) {
    if (updates.length === 0) return
    enabledDirty.value = true
    const byName = new Map(updates)
    services.value = services.value.map((service) =>
      byName.has(service.name) ? { ...service, enabled: byName.get(service.name) } : service,
    )
  }

  function enableAllServices() {
    if (services.value.length === 0) return
    enabledDirty.value = true
    services.value = services.value.map((service) => ({ ...service, enabled: true }))
  }

  function enableOnlyFirstService() {
    if (services.value.length === 0) return
    enabledDirty.value = true
    const keepName = services.value[0].name
    services.value = services.value.map((service) => ({
      ...service,
      enabled: service.name === keepName,
    }))
  }

  function getFirstServiceLabel() {
    const first = services.value[0]
    return first?.displayName || first?.name || ''
  }

  function discardEnabledChanges() {
    if (!enabledDirty.value) return false
    services.value = services.value.map((service) => ({
      ...service,
      enabled: savedEnabledByName.value[service.name] ?? service.enabled,
    }))
    enabledDirty.value = false
    return true
  }

  function clearEnabledDirty() {
    enabledDirty.value = false
  }

  async function saveEnabledSelection() {
    const payload = Object.fromEntries(services.value.map((s) => [s.name, s.enabled]))
    await tomcatsApi.updateEnabled(currentTomcatId.value, payload)
    enabledDirty.value = false
    syncSavedEnabled()
  }

  return {
    currentTomcatId,
    currentInstance,
    services,
    tomcatStatus,
    externallyManaged,
    loading,
    actionLoading,
    enabledDirty,
    isRunning,
    enabledCount,
    setCurrentTomcat,
    refresh,
    refreshStatus,
    setLocalEnabled,
    setLocalEnabledBulk,
    enableAllServices,
    enableOnlyFirstService,
    getFirstServiceLabel,
    getEnabledChanges,
    discardEnabledChanges,
    clearEnabledDirty,
    saveEnabledSelection,
    extractErrorMessage,
  }
})
