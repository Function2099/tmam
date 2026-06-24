<template>
  <el-container class="app-layout">
    <el-header class="app-header">
      <div class="brand">TMAM — Tomcat Service Manager</div>
      <el-menu
        mode="horizontal"
        :ellipsis="false"
        router
        :default-active="activeMenu"
      >
        <el-menu-item index="/">Tomcat 實例</el-menu-item>
        <el-menu-item v-if="showLegacyProjects" index="/projects">專案管理（舊）</el-menu-item>
      </el-menu>
    </el-header>
    <el-main>
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { tomcatApi } from '@/api/tmam'

const route = useRoute()
const showLegacyProjects = ref(false)

const activeMenu = computed(() => {
  if (route.path.startsWith('/tomcats/')) return '/'
  if (route.path.startsWith('/projects')) return '/projects'
  return route.path
})

onMounted(async () => {
  try {
    const { data } = await tomcatApi.meta()
    showLegacyProjects.value = data?.mode === 'multi-instance'
  } catch {
    showLegacyProjects.value = false
  }
})
</script>

<style scoped>
.app-layout {
  min-height: 100vh;
}

.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e4e7ed;
  background: #fff;
  padding: 0 20px;
}

.brand {
  font-weight: 600;
  font-size: 16px;
  margin-right: 24px;
  white-space: nowrap;
  color: #303133;
}

.el-menu {
  border-bottom: none;
  flex: 1;
  justify-content: flex-end;
}
</style>
