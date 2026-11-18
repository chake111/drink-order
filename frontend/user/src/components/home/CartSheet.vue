<script setup>
import { computed } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  items: { type: Array, required: true },
  pickupType: { type: String, default: '自取' },
  totalPrice: { type: Number, required: true }
})

const emit = defineEmits(['update:modelValue', 'update:pickupType', 'decrease', 'increase', 'submit'])

const show = computed({
  get: () => props.modelValue,
  set: value => emit('update:modelValue', value)
})

const pickup = computed({
  get: () => props.pickupType,
  set: value => emit('update:pickupType', value)
})
</script>

<template>
  <van-action-sheet v-model:show="show" title="购物车">
    <div class="cart-sheet">
      <div v-for="(item, index) in items" :key="index" class="cart-item">
        <div class="cart-item-info">
          <span class="cart-item-name">{{ item.name }}</span>
          <span class="cart-item-spec" v-if="item.spec">{{ item.spec }}</span>
        </div>
        <span class="cart-item-price">¥{{ (item.price * item.qty).toFixed(2) }}</span>
        <div class="qty-control">
          <button class="qty-btn" @click="$emit('decrease', index)">-</button>
          <span class="qty-num">{{ item.qty }}</span>
          <button class="qty-btn add" @click="$emit('increase', index)">+</button>
        </div>
      </div>
      <div class="cart-sheet-footer">
        <div class="pickup-type">
          <span>取餐方式：</span>
          <van-radio-group v-model="pickup" direction="horizontal">
            <van-radio name="自取">自取</van-radio>
            <van-radio name="外送">外送</van-radio>
          </van-radio-group>
        </div>
        <van-button type="primary" block round @click="$emit('submit')">提交订单 ¥{{ totalPrice.toFixed(2) }}</van-button>
      </div>
    </div>
  </van-action-sheet>
</template>

<style scoped>
.cart-sheet {
  padding: 0 16px 16px;
  max-height: 50vh;
  overflow-y: auto;
}
.cart-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--line);
}
.cart-item-info {
  flex: 1;
  min-width: 0;
}
.cart-item-name {
  font-size: 14px;
  font-weight: 500;
  display: block;
}
.cart-item-spec {
  font-size: 11px;
  color: var(--ink-3);
  display: block;
  margin-top: 2px;
}
.cart-item-price {
  font-size: 14px;
  font-weight: 600;
  color: var(--brand);
  margin-right: 8px;
  white-space: nowrap;
}
.cart-sheet-footer {
  margin-top: 16px;
}
.pickup-type {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  font-size: 14px;
}

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