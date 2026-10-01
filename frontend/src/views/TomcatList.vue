<template>
  <div class="tomcat-list">
    <div class="toolbar">
      <div class="toolbar-left">
        <h2>Tomcat 實例</h2>
        <NginxStatusBadge ref="nginxBadge" />
      </div>
      <div class="toolbar-actions">
        <el-button type="primary" @click="openAddDialog">新增 Tomcat</el-button>
        <el-button :loading="loading" @click="refresh">重新整理</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="每台 Tomcat 可獨立啟停，並勾選要載入的 Service。路徑型系統透過 Nginx 分流，IP 型共用網卡 IP、以 port 區分。"
    />

    <el-row v-loading="loading && instances.length === 0" :gutter="16">
      <el-col v-if="instances.length === 0 && !loading" :span="24">
        <el-empty description="請選擇本機 Tomcat 安裝目錄以開始使用">
          <el-button type="primary" @click="openSetupDialog">選擇 Tomcat</el-button>
        </el-empty>
      </el-col>
      <el-col
        v-for="instance in instances"
        :key="instance.id"
        :xs="24"
        :sm="12"
        :lg="8"
      >
        <TomcatInstanceCard
          :instance="instance"
          :status="displayStatus(instance.id)"
          :loading="actionLoading"
          @manage="goManage"
          @start="handleStart"
          @stop="handleStop"
          @logs="goLogs"
        />
      </el-col>
    </el-row>

    <AddTomcatDialog v-model="showAddDialog" :setup="isFirstSetup" @created="onCreated" />
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import TomcatInstanceCard from '@/components/TomcatInstanceCard.vue'
import AddTomcatDialog from '@/components/AddTomcatDialog.vue'
import NginxStatusBadge from '@/components/NginxStatusBadge.vue'
import { tomcatsApi } from '@/api/tmam'
import { useTomcatInstancesStore } from '@/stores/tomcatInstances'

const router = useRouter()
const store = useTomcatInstancesStore()
const { instances, loading, actionLoading } = storeToRefs(store)

const showAddDialog = ref(false)
const isFirstSetup = ref(false)
const nginxBadge = ref(null)
let pollingTimer = null

function displayStatus(id) {
  return store.displayStatus(id)
}

onMounted(async () => {
  await refresh()
  if (instances.value.length === 0) {
    openSetupDialog()
  }
  pollingTimer = setInterval(() => store.refreshStatus(), 5000)
})

onUnmounted(() => clearInterval(pollingTimer))

async function refresh() {
  await store.refresh()
  await nginxBadge.value?.refresh()
}

function openSetupDialog() {
  isFirstSetup.value = true
  showAddDialog.value = true
}

watch(showAddDialog, (open) => {
  if (!open) {
    isFirstSetup.value = false
  }
})

function openAddDialog() {
  isFirstSetup.value = false
  showAddDialog.value = true
}

function goManage(id) {
  router.push(`/tomcats/${id}`)
}

function goLogs(id) {
  router.push(`/tomcats/${id}/logs`).catch((error) => {
    ElMessage.error(`無法開啟 Log 頁面：${error?.message || '未知錯誤'}`)
  })
}

async function onCreated(instance) {
  await refresh()
  if (instance?.id) {
    router.push(`/tomcats/${instance.id}`)
  }
}

async function withAction(fn) {
  actionLoading.value = true
  try {
    await fn()
    await refresh()
  } finally {
    actionLoading.value = false
  }
}

async function handleStart(id) {
  await withAction(async () => {
    const { data } = await tomcatsApi.start(id)
    if (data?.success === false) throw new Error(data.message)
    ElMessage.success('Tomcat 已啟動')
  }).catch((e) => ElMessage.error(store.extractErrorMessage(e)))
}

async function handleStop(id) {
  await withAction(async () => {
    await tomcatsApi.stop(id)
    ElMessage.success('Tomcat 已停止')
  }).catch((e) => ElMessage.error(store.extractErrorMessage(e)))
}
</script>

<style scoped>
.tomcat-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.toolbar h2 {
  margin: 0;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.toolbar-actions {
  display: flex;
  gap: 8px;
}
</style>
