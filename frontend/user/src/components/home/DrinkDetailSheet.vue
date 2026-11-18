<script setup>
import { computed } from 'vue'
import { parseFlavorValue } from '../../utils/flavor.js'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  drink: { type: Object, default: null },
  qty: { type: Number, default: 1 },
  selectedFlavors: { type: Object, required: true }
})

const emit = defineEmits(['update:modelValue', 'update:qty', 'select-flavor', 'add'])

const show = computed({
  get: () => props.modelValue,
  set: value => emit('update:modelValue', value)
})

const qtyModel = computed({
  get: () => props.qty,
  set: value => emit('update:qty', value)
})
</script>

<template>
  <van-action-sheet v-model:show="show" :title="drink ? drink.name : ''">
    <div class="detail-sheet" v-if="drink">
      <div class="detail-img">
        <img v-if="drink.image" :src="drink.image" alt="" />
        <span v-else>☕</span>
      </div>
      <div class="detail-info">
        <div class="detail-name">{{ drink.name }}</div>
        <div class="detail-desc">{{ drink.description || '暂无描述' }}</div>
        <div class="detail-price"><small>¥</small>{{ drink.price }}</div>
      </div>

      <!-- 口味选择 -->
      <div class="flavor-section" v-if="drink.flavors && drink.flavors.length">
        <div v-for="flavor in drink.flavors" :key="flavor.name" class="flavor-group">
          <div class="flavor-label">{{ flavor.name }}</div>
          <div class="flavor-options">
            <span
              v-for="opt in parseFlavorValue(flavor.value)"
              :key="opt"
              class="flavor-option"
              :class="{ active: selectedFlavors[flavor.name] === opt }"
              @click="$emit('select-flavor', flavor.name, opt)"
            >
              {{ opt }}
            </span>
          </div>
        </div>
      </div>

      <!-- 数量选择 -->
      <div class="detail-qty-row">
        <span class="detail-qty-label">数量</span>
        <div class="qty-control">
          <button class="qty-btn" @click="qtyModel > 1 && qtyModel--">-</button>
          <span class="qty-num">{{ qty }}</span>
          <button class="qty-btn add" @click="qtyModel++">+</button>
        </div>
      </div>

      <!-- 加入购物车按钮 -->
      <div class="detail-footer">
        <van-button type="primary" block round @click="$emit('add')">
          加入购物车 ¥{{ (drink.price * qty).toFixed(2) }}
        </van-button>
      </div>
    </div>
  </van-action-sheet>
</template>

<style scoped>
.detail-sheet {
  padding: 0 16px 16px;
  max-height: 70vh;
  overflow-y: auto;
}
.detail-img {
  width: 100%;
  height: 180px;
  border-radius: var(--radius);
  background: var(--brand-light);
  display: grid;
  place-items: center;
  font-size: 48px;
  overflow: hidden;
  margin-bottom: 16px;
}
.detail-img img { width: 100%; height: 100%; object-fit: cover; }
.detail-info {
  margin-bottom: 16px;
}
.detail-name {
  font-size: 18px;
  font-weight: 700;
  color: var(--ink);
  margin-bottom: 6px;
}
.detail-desc {
  font-size: 13px;
  color: var(--ink-3);
  margin-bottom: 8px;
  line-height: 1.5;
}
.detail-price {
  font-family: var(--font-en);
  font-size: 22px;
  font-weight: 700;
  color: var(--brand);
}
.detail-price small { font-size: 14px; font-weight: 400; }

/* 口味选择 */
.flavor-section {
  margin-bottom: 16px;
}
.flavor-group {
  margin-bottom: 12px;
}
.flavor-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 8px;
}
.flavor-options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.flavor-option {
  padding: 6px 14px;
  border-radius: 16px;
  font-size: 12px;
  background: var(--bg);
  color: var(--ink-2);
  cursor: pointer;
  transition: .15s;
  border: 1px solid transparent;
}
.flavor-option.active {
  background: var(--brand-light);
  color: var(--brand);
  border-color: var(--brand);
  font-weight: 600;
}

/* 数量选择行 */
.detail-qty-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 0;
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  margin-bottom: 16px;
}
.detail-qty-label {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
}

.detail-footer {
  padding-top: 8px;
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