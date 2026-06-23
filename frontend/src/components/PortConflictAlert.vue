<template>
  <el-alert
    v-if="conflicts.length > 0"
    type="warning"
    title="偵測到 Port 衝突"
    :closable="false"
    show-icon
  >
    <ul class="conflict-list">
      <li v-for="(c, i) in conflicts" :key="i">
        Port {{ c.port }}（{{ c.type }}）衝突：{{ c.projectA }} ↔ {{ c.projectB }}
      </li>
    </ul>
  </el-alert>
</template>

<script setup>
import { storeToRefs } from 'pinia'
import { useInstanceStore } from '@/stores/instance'

const store = useInstanceStore()
const { conflicts } = storeToRefs(store)
</script>

<style scoped>
.conflict-list {
  margin: 8px 0 0;
  padding-left: 20px;
}
</style>
