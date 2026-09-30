<!--
  占位页：M2/M3 阶段待交付模块（脚本中心/任务中心/监控大屏/报告中心）的统一空状态展示，
  并标注当前路由 meta.title 对应的模块名称
-->
<template>
  <el-card shadow="never" class="placeholder-card">
    <el-empty :image-size="120" description="模块开发中（M2/M3 交付）">
      <div class="placeholder-route">
        当前模块：{{ route.meta.title || '未知模块' }}
      </div>
    </el-empty>
  </el-card>
</template>

<script setup>
import { useRoute } from 'vue-router'

const route = useRoute()
</script>

<style scoped>
.placeholder-card {
  min-height: 360px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.placeholder-card :deep(.el-card__body) {
  width: 100%;
}

.placeholder-route {
  margin-top: 8px;
  color: #909399;
  font-size: 14px;
}
</style>
