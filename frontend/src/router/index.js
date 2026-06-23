import { createRouter, createWebHashHistory } from 'vue-router'

export default createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', component: () => import('@/views/ServiceDashboard.vue') },
    { path: '/logs', component: () => import('@/views/TomcatLogs.vue') },
    { path: '/projects', component: () => import('@/views/ProjectList.vue') },
    { path: '/logs/:name', component: () => import('@/views/Logs.vue') },
  ],
})
