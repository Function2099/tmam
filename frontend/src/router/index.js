import { createRouter, createWebHashHistory } from 'vue-router'

export default createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', component: () => import('@/views/TomcatList.vue') },
    { path: '/tomcats/:tomcatId', component: () => import('@/views/ServiceDashboard.vue') },
    { path: '/tomcats/:tomcatId/logs', component: () => import('@/views/TomcatLogs.vue') },
    { path: '/projects', component: () => import('@/views/ProjectList.vue') },
    { path: '/logs/:name', component: () => import('@/views/Logs.vue') },
  ],
})
