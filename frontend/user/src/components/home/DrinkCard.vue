<script setup>
defineProps({
  drink: { type: Object, required: true },
  qty: { type: Number, default: 0 }
})

defineEmits(['open', 'add', 'remove'])
</script>

<template>
  <div class="drink-card" @click="$emit('open', drink)">
    <div class="drink-img">
      <img v-if="drink.image" :src="drink.image" alt="" />
      <span v-else>☕</span>
    </div>
    <div class="drink-info">
      <div class="drink-name">{{ drink.name }}</div>
      <div class="drink-desc">{{ drink.description }}</div>
      <div class="drink-tags" v-if="drink.flavors && drink.flavors.length">
        <span class="drink-tag" v-for="f in drink.flavors.slice(0, 2)" :key="f.name">{{ f.name }}</span>
      </div>
      <div class="drink-bottom">
        <span class="drink-price"><small>¥</small>{{ drink.price }}</span>
        <div class="qty-control" @click.stop>
          <button v-if="qty > 0" class="qty-btn" @click="$emit('remove', drink)">-</button>
          <span v-if="qty > 0" class="qty-num">{{ qty }}</span>
          <button class="qty-btn add" @click="$emit('add', drink)">+</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.drink-card {
  display: flex;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px solid var(--line);
  cursor: pointer;
}
.drink-card:last-child { border-bottom: 0; }
.drink-img {
  width: 72px;
  height: 72px;
  border-radius: 10px;
  background: var(--brand-light);
  flex-shrink: 0;
  display: grid;
  place-items: center;
  font-size: 24px;
  overflow: hidden;
}
.drink-img img { width: 100%; height: 100%; object-fit: cover; }
.drink-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.drink-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 2px;
}
.drink-desc {
  font-size: 11px;
  color: var(--ink-3);
  margin-bottom: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.drink-tags {
  display: flex;
  gap: 4px;
  margin-bottom: 6px;
}
.drink-tag {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--brand-light);
  color: var(--brand);
  font-weight: 500;
}
.drink-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: auto;
}
.drink-price {
  font-family: var(--font-en);
  font-size: 16px;
  font-weight: 700;
  color: var(--brand);
}
.drink-price small { font-size: 11px; font-weight: 400; }

.qty-control {
  display: flex;
  align-items: center;
  gap: 8px;
}
.qty-btn {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  border: 1px solid var(--line-2);
  background: var(--surface);
  color: var(--ink-2);
  display: grid;
  place-items: center;
  cursor: pointer;
  font-size: 14px;
}
.qty-btn.add {
  background: var(--brand);
  border-color: var(--brand);
  color: #fff;
}
.qty-num {
  font-size: 14px;
  font-weight: 600;
  min-width: 16px;
  text-align: center;
}
</style>