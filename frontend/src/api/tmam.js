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

export const tomcatApi = {
  meta: () => api.get('/tomcat/meta'),
  services: () => api.get('/tomcat/services'),
  status: () => api.get('/tomcat/status'),
  createService: (payload) => api.post('/tomcat/services', payload),
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
