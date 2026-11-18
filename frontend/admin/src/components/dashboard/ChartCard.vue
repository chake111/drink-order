<script setup>
defineProps({
  title: { type: String, required: true }
})
</script>

<template>
  <div class="chart-card">
    <h3>{{ title }}</h3>
    <slot />
  </div>
</template>

<style scoped>
.chart-card {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  padding: 20px;
  box-shadow: var(--shadow);
}

.chart-card h3 {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 16px;
}
</style>