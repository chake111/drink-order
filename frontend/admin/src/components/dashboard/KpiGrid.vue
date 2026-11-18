<script setup>
defineProps({
  todayData: { type: Object, required: true },
  avgOrderDisplay: { type: String, required: true },
  pendingDisplay: { type: [Number, String], required: true }
})
</script>

<template>
  <div class="kpi-grid">
    <div class="kpi">
      <div class="kpi-icon">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="1" x2="12" y2="23"/><path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/></svg>
      </div>
      <div class="kpi-label">今日营业额</div>
      <div class="kpi-value"><span class="yen">&yen;</span>{{ Number(todayData.todayAmount || 0).toFixed(2).split('.')[0] }}<small>.{{ Number(todayData.todayAmount || 0).toFixed(2).split('.')[1] }}</small></div>
      <div class="kpi-delta" :class="{ up: todayData.amountChange > 0, down: todayData.amountChange < 0 }" v-if="todayData.amountChange != null && todayData.amountChange !== 0">
        <svg v-if="todayData.amountChange > 0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M7 17 17 7M7 7h10v10"/></svg>
        <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M7 7 17 17M17 7v10H7"/></svg>
        较昨日 {{ Math.abs(todayData.amountChange ?? 0) }}%
      </div>
    </div>

    <div class="kpi">
      <div class="kpi-icon">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 2 6 6H2"/><path d="M2 6h20l-2 13a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2L2 6Z"/><path d="M9 11v5"/><path d="M15 11v5"/></svg>
      </div>
      <div class="kpi-label">今日订单数</div>
      <div class="kpi-value">{{ todayData.todayOrders }}</div>
      <div class="kpi-delta" :class="{ up: todayData.ordersChange > 0, down: todayData.ordersChange < 0 }" v-if="todayData.ordersChange != null && todayData.ordersChange !== 0">
        <svg v-if="todayData.ordersChange > 0" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M7 17 17 7M7 7h10v10"/></svg>
        <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M7 7 17 17M17 7v10H7"/></svg>
        较昨日 {{ Math.abs(todayData.ordersChange ?? 0) }}%
      </div>
    </div>

    <div class="kpi">
      <div class="kpi-icon">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>
      </div>
      <div class="kpi-label">待处理订单</div>
      <div class="kpi-value">{{ pendingDisplay }}</div>
      <div class="kpi-delta down">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M7 7 17 17M17 7v10H7"/></svg>
        待接单 {{ todayData.waitingOrders }} · 制作中 {{ todayData.makingOrders }}
      </div>
    </div>

    <div class="kpi">
      <div class="kpi-icon">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/></svg>
      </div>
      <div class="kpi-label">客单价</div>
      <div class="kpi-value"><span class="yen">&yen;</span>{{ avgOrderDisplay.split('.')[0] }}<small>.{{ avgOrderDisplay.split('.')[1] }}</small></div>
      <div class="kpi-delta up" v-if="todayData.todayOrders > 0">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M7 17 17 7M7 7h10v10"/></svg>
        今日 {{ todayData.todayOrders }} 单
      </div>
    </div>
  </div>
</template>

<style scoped>
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

.kpi {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  padding: 20px;
  box-shadow: var(--shadow);
  position: relative;
  overflow: hidden;
}

.kpi-icon {
  width: 42px;
  height: 42px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  background: var(--primary-weak);
  color: var(--primary);
  margin-bottom: 14px;
}

.kpi-icon svg {
  width: 21px;
  height: 21px;
}

.kpi-label {
  font-size: 13px;
  color: var(--ink-3);
}

.kpi-value {
  font-family: var(--font-en);
  font-size: 30px;
  font-weight: 700;
  color: var(--ink);
  margin-top: 4px;
  letter-spacing: -0.01em;
}

.kpi-value .yen {
  font-size: 15px;
  font-weight: 500;
  color: var(--ink-3);
  margin-right: 2px;
}

.kpi-value small {
  font-size: 15px;
  font-weight: 500;
  color: var(--ink-3);
}

.kpi-delta {
  font-size: 12.5px;
  margin-top: 8px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.kpi-delta svg {
  width: 13px;
  height: 13px;
}

.kpi-delta.up {
  color: var(--ok);
}

.kpi-delta.down {
  color: var(--off);
}

@media (max-width: 1200px) {
  .kpi-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 768px) {
  .kpi-grid {
    grid-template-columns: 1fr;
  }
}
</style>