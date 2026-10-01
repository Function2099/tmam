<template>
  <span v-if="status" class="nginx-status">
    <el-tag
      :type="status.running ? 'success' : (status.available ? 'danger' : 'info')"
      size="small"
      :title="status.message"
    >
      Nginx {{ status.running ? '運行中' : (status.available ? '未啟動' : '未安裝') }}
    </el-tag>
    <el-button
      v-if="canStart"
      type="danger"
      size="small"
      :loading="starting"
      @click="startNginx"
    >
      啟動 Nginx
    </el-button>
  </span>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { extractErrorMessage, nginxApi } from '@/api/tmam'

const status = ref(null)
const starting = ref(false)
let pollTimer = null

const canStart = computed(() => status.value?.available && !status.value?.running)

onMounted(async () => {
  await refresh()
  pollTimer = setInterval(() => refresh(), 5000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})

async function refresh() {
  try {
    const { data } = await nginxApi.status()
    status.value = data
  }
  catch {
    /* keep last known status */
  }
}

async function startNginx() {
  starting.value = true
  try {
    const { data } = await nginxApi.apply()
    status.value = data
    if (data?.running) {
      ElMessage.success('Nginx 已啟動（未重啟 Tomcat）')
    }
    else {
      ElMessage.warning(data?.message || 'Nginx 未能啟動')
    }
  }
  catch (error) {
    ElMessage.error(extractErrorMessage(error) || '無法啟動 Nginx')
    await refresh()
  }
  finally {
    starting.value = false
  }
}

defineExpose({ refresh })
</script>

<style scoped>
.nginx-status {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
</style>
