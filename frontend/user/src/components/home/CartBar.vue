<script setup>
defineProps({
  totalQty: { type: Number, required: true },
  totalPrice: { type: Number, required: true }
})

defineEmits(['open'])
</script>

<template>
  <div class="cart-bar">
    <div class="cart-left" @click="$emit('open')">
      <div class="cart-icon">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="22" height="22">
          <path d="M6 2L3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4z"/><line x1="3" y1="6" x2="21" y2="6"/><path d="M16 10a4 4 0 0 1-8 0"/>
        </svg>
        <span class="cart-badge">{{ totalQty }}</span>
      </div>
      <div class="cart-info">
        <span class="cart-price">¥{{ totalPrice.toFixed(2) }}</span>
      </div>
    </div>
    <van-button type="primary" round size="small" @click="$emit('open')">去下单</van-button>
  </div>
</template>

<style scoped>
.cart-bar {
  position: fixed;
  bottom: 50px;
  left: 0;
  right: 0;
  background: var(--surface);
  border-top: 1px solid var(--line);
  padding: 8px 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  z-index: 100;
}
.cart-left {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
}
.cart-icon {
  position: relative;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: var(--brand);
  color: #fff;
  display: grid;
  place-items: center;
}
.cart-badge {
  position: absolute;
  top: -4px;
  right: -4px;
  background: var(--warn);
  color: #fff;
  font-size: 10px;
  min-width: 18px;
  height: 18px;
  border-radius: 9px;
  display: grid;
  place-items: center;
  font-weight: 600;
}
.cart-price {
  font-size: 18px;
  font-weight: 700;
  color: var(--brand);
}
</style>