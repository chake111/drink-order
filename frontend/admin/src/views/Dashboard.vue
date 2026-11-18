<script setup>
import { useDashboard } from '../composables/useDashboard.js'
import KpiGrid from '../components/dashboard/KpiGrid.vue'
import TrendChartCard from '../components/dashboard/TrendChartCard.vue'
import TopDrinksCard from '../components/dashboard/TopDrinksCard.vue'

const { todayData, trendData, topData, avgOrderDisplay, pendingDisplay } = useDashboard()
</script>

<template>
  <div class="dashboard-page">
    <div class="page-header">
      <div>
        <h1>工作台</h1>
        <p>今日门店运营数据概览</p>
      </div>
    </div>

    <KpiGrid :today-data="todayData" :avg-order-display="avgOrderDisplay" :pending-display="pendingDisplay" />

    <div class="chart-row">
      <TrendChartCard :trend-data="trendData" />
      <TopDrinksCard :top-data="topData" />
    </div>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 18px;
}

.page-header h1 {
  font-size: 21px;
  font-weight: 600;
}

.page-header p {
  font-size: 13px;
  color: var(--ink-3);
  margin-top: 5px;
}

.chart-row {
  display: grid;
  grid-template-columns: 1fr 360px;
  gap: 18px;
}

@media (max-width: 1200px) {
  .chart-row {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .page-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
}
</style>
