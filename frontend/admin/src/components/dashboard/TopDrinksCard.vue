<script setup>
import ChartCard from './ChartCard.vue'

defineProps({
  topData: { type: Array, required: true }
})
</script>

<template>
  <ChartCard title="热销 TOP5">
    <div class="top-list">
      <div v-for="(item, index) in topData" :key="index" class="top-item">
        <span class="top-rank" :class="{ gold: index === 0, silver: index === 1, bronze: index === 2 }">{{ index + 1 }}</span>
        <span class="top-name">{{ item.drinkName }}</span>
        <span class="top-count">{{ item.count }} 杯</span>
      </div>
      <div v-if="topData.length === 0" class="empty-tip">暂无数据</div>
    </div>
  </ChartCard>
</template>

<style scoped>
.top-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: 300px;
  overflow-y: auto;
}

.top-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--line);
}

.top-item:last-child {
  border-bottom: none;
}

.top-rank {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: var(--primary-weak);
  color: var(--primary);
  display: grid;
  place-items: center;
  font-weight: 600;
  font-size: 13px;
  flex-shrink: 0;
}

.top-rank.gold {
  background: #FFF7E6;
  color: #D48806;
}

.top-rank.silver {
  background: #F0F0F0;
  color: #8C8C8C;
}

.top-rank.bronze {
  background: #FFF1E8;
  color: #D46B08;
}

.top-name {
  flex: 1;
  font-size: 14px;
}

.top-count {
  font-size: 13px;
  color: var(--ink-3);
  font-family: var(--font-en);
}

.empty-tip {
  text-align: center;
  color: var(--ink-3);
  padding: 40px 0;
}
</style>