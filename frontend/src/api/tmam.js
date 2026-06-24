import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 120000,
})

export function extractErrorMessage(error) {
  const data = error?.response?.data
  if (typeof data === 'string') return data
  if (data?.message) return data.message
  return error?.message ?? '未知錯誤'
}

export const tomcatsApi = {
  list: () => api.get('/tomcats'),
  discover: () => api.get('/tomcats/discover'),
  allStatus: () => api.get('/tomcats/status'),
  get: (id) => api.get(`/tomcats/${id}`),
  create: (payload) => api.post('/tomcats', payload),
  update: (id, payload) => api.put(`/tomcats/${id}`, payload),
  remove: (id) => api.delete(`/tomcats/${id}`),
  services: (id) => api.get(`/tomcats/${id}/services`),
  status: (id) => api.get(`/tomcats/${id}/status`),
  createService: (id, payload) => api.post(`/tomcats/${id}/services`, payload),
  updateService: (id, name, payload) => api.put(`/tomcats/${id}/services/${name}`, payload),
  deleteService: (id, name) => api.delete(`/tomcats/${id}/services/${name}`),
  updateEnabled: (id, enabledByName) => api.put(`/tomcats/${id}/services/enabled`, enabledByName),
  start: (id) => api.post(`/tomcats/${id}/start`),
  stop: (id) => api.post(`/tomcats/${id}/stop`),
  restart: (id) => api.post(`/tomcats/${id}/restart`),
  apply: (id) => api.post(`/tomcats/${id}/apply`),
  toggle: (id, name) => api.post(`/tomcats/${id}/services/${name}/toggle`),
  import: (id) => api.post(`/tomcats/${id}/import`),
  restoreOriginal: (id) => api.post(`/tomcats/${id}/restore-original`),
  logs: (id, lines = 100) => api.get(`/tomcats/${id}/logs`, { params: { lines } }),
}

/** 向後相容：委派至 default 實例 */
export const tomcatApi = {
  meta: () => api.get('/tomcat/meta'),
  services: () => api.get('/tomcat/services'),
  status: () => api.get('/tomcat/status'),
  createService: (payload) =>
    api.post('/tomcat/services', { type: 'PATH_PROXY', ...payload }),
  updateService: (name, payload) => api.put(`/tomcat/services/${name}`, payload),
  deleteService: (name) => api.delete(`/tomcat/services/${name}`),
  updateEnabled: (enabledByName) => api.put('/tomcat/services/enabled', enabledByName),
  start: () => api.post('/tomcat/start'),
  stop: () => api.post('/tomcat/stop'),
  restart: () => api.post('/tomcat/restart'),
  apply: () => api.post('/tomcat/apply'),
  toggle: (name) => api.post(`/tomcat/services/${name}/toggle`),
  import: () => api.post('/tomcat/import'),
  restoreOriginal: () => api.post('/tomcat/restore-original'),
  logs: (lines = 100) => api.get('/tomcat/logs', { params: { lines } }),
  appLogs: (lines = 200) => api.get('/tomcat/app-logs', { params: { lines } }),
}

export const nginxApi = {
  status: () => api.get('/nginx/status'),
  apply: () => api.post('/nginx/apply'),
}

export const projectApi = {
  list: () => api.get('/projects'),
  add: (project) => api.post('/projects', project),
  update: (name, project) => api.put(`/projects/${name}`, project),
  remove: (name) => api.delete(`/projects/${name}`),
  checkConflicts: () => api.get('/projects/port-conflicts'),
}

export const instanceApi = {
  start: (name) => api.post(`/instances/${name}/start`),
  stop: (name) => api.post(`/instances/${name}/stop`),
  restart: (name) => api.post(`/instances/${name}/restart`),
  status: (name) => api.get(`/instances/${name}/status`),
  allStatus: () => api.get('/instances/status'),
  logs: (name, lines = 100) => api.get(`/instances/${name}/logs`, { params: { lines } }),
}
