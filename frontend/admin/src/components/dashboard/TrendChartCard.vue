<script setup>
import { computed } from 'vue'
import ChartCard from './ChartCard.vue'
import { useTrendChart } from '../../composables/useTrendChart.js'

const props = defineProps({
  trendData: { type: Array, required: true }
})

const hasTrendData = computed(() => props.trendData.length > 0)
const trendDataRef = computed(() => props.trendData)
const { trendChartRef } = useTrendChart(trendDataRef)
</script>

<template>
  <ChartCard title="近7日销售趋势">
    <div v-if="hasTrendData" ref="trendChartRef" class="chart-container"></div>
    <div v-else class="chart-empty">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" class="empty-icon"><path d="M3 3v18h18"/><path d="m19 9-5 5-4-4-3 3"/></svg>
      <span>暂无趋势数据</span>
    </div>
  </ChartCard>
</template>

<style scoped>
.chart-container {
  height: 300px;
  width: 100%;
}

.chart-empty {
  height: 300px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--ink-3);
  font-size: 13px;
}

.chart-empty .empty-icon {
  width: 48px;
  height: 48px;
  opacity: 0.5;
}
</style>