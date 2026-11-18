<script setup>
defineProps({
  categories: { type: Array, required: true },
  activeCatId: { type: [Number, String], default: null }
})

defineEmits(['select'])
</script>

<template>
  <div class="cat-sidebar">
    <div
      v-for="cat in categories"
      :key="cat.id"
      class="cat-item"
      :class="{ active: activeCatId === cat.id }"
      @click="$emit('select', cat.id)"
    >
      {{ cat.name }}
    </div>
  </div>
</template>

<style scoped>
.cat-sidebar {
  width: 88px;
  flex-shrink: 0;
  background: #FAFAFA;
  overflow-y: auto;
  scrollbar-width: none;
}
.cat-sidebar::-webkit-scrollbar { display: none; }
.cat-item {
  padding: 14px 0;
  text-align: center;
  font-size: 12px;
  color: var(--ink-2);
  position: relative;
  cursor: pointer;
  transition: .15s;
}
.cat-item.active {
  background: var(--surface);
  color: var(--ink);
  font-weight: 600;
}
.cat-item.active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 20%;
  bottom: 20%;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: var(--brand);
}
</style>