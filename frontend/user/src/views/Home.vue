<template>
  <div class="home-page">
    <!-- 顶部品牌栏 -->
    <div class="home-header">
      <div class="logo-sm">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
          <path d="M6 2h12l-1 7a5 5 0 0 1-10 0L6 2Z"/>
          <path d="M6 8H4a2 2 0 0 0 0 4h2"/>
          <path d="M5 22h14"/>
        </svg>
      </div>
      <span class="brand-text">
        茶优选
        <small>营业中</small>
      </span>
    </div>

    <!-- 主体：左右布局 -->
    <div class="main-body">
      <CategorySidebar
        :categories="categories"
        :active-cat-id="activeCatId"
        @select="selectCategory"
      />

      <!-- 右侧饮品列表 -->
      <div class="drink-list">
        <div class="cat-section-title">{{ activeCatName }}</div>
        <DrinkCard
          v-for="drink in drinks"
          :key="drink.id"
          :drink="drink"
          :qty="getCartQty(drink.id)"
          @open="showDrinkDetail"
          @add="quickAddToCart"
          @remove="decreaseCart"
        />
        <van-empty v-if="!loading && drinks.length === 0" description="暂无饮品" />
      </div>
    </div>

    <!-- 饮品详情弹窗 -->
    <DrinkDetailSheet
      v-model="showDetailSheet"
      v-model:qty="detailQty"
      :drink="detailDrink"
      :selected-flavors="selectedFlavors"
      @select-flavor="selectFlavor"
      @add="addToCartWithFlavor"
    />

    <!-- 购物车底部栏 -->
    <CartBar
      v-if="cartItems.length > 0"
      :total-qty="totalQty"
      :total-price="totalPrice"
      @open="showCartSheet = true"
    />

    <!-- 购物车详情弹窗 -->
    <CartSheet
      v-model="showCartSheet"
      v-model:pickup-type="pickupType"
      :items="cartItems"
      :total-price="totalPrice"
      @decrease="decreaseCartItem"
      @increase="(index) => { cartItems[index].qty++ }"
      @submit="submitOrder"
    />
  </div>
</template>

<script setup>
import CategorySidebar from '../components/home/CategorySidebar.vue'
import DrinkCard from '../components/home/DrinkCard.vue'
import DrinkDetailSheet from '../components/home/DrinkDetailSheet.vue'
import CartBar from '../components/home/CartBar.vue'
import CartSheet from '../components/home/CartSheet.vue'
import { useHomeMenu } from '../composables/useHomeMenu.js'

const {
  categories, drinks, activeCatId, activeCatName, loading,
  cartItems, showCartSheet, pickupType,
  showDetailSheet, detailDrink, detailQty, selectedFlavors,
  totalQty, totalPrice,
  selectCategory, showDrinkDetail, selectFlavor,
  getCartQty, quickAddToCart, addToCartWithFlavor,
  decreaseCartItem, decreaseCart, submitOrder
} = useHomeMenu()
</script>

<style scoped>
.home-page {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.home-header {
  padding: 8px 16px 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
  background: var(--surface);
}
.logo-sm {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: var(--brand);
  color: #fff;
  display: grid;
  place-items: center;
  flex-shrink: 0;
}
.brand-text {
  font-family: var(--font-en);
  font-size: 15px;
  font-weight: 700;
}
.brand-text small {
  display: block;
  font-family: var(--font-cn);
  font-size: 10px;
  font-weight: 400;
  color: var(--ink-3);
}
.main-body {
  flex: 1;
  display: flex;
  overflow: hidden;
}
.drink-list {
  flex: 1;
  overflow-y: auto;
  padding: 0 12px 100px;
}
.drink-list::-webkit-scrollbar { display: none; }
.cat-section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
  padding: 14px 0 8px;
  position: sticky;
  top: 0;
  background: var(--surface);
  z-index: 1;
}
</style>
