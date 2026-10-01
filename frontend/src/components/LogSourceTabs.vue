<template>
  <el-tabs :model-value="modelValue" @update:model-value="$emit('update:modelValue', $event)" @tab-change="$emit('change')">
    <el-tab-pane label="TMAM 操作日誌" name="app" />
    <el-tab-pane label="Tomcat catalina 日誌" name="tomcat" />
  </el-tabs>
</template>

<script setup>
defineProps({
  modelValue: { type: String, required: true },
})
defineEmits(['update:modelValue', 'change'])
</script>
