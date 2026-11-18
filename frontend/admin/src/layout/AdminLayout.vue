<script setup>
import { ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { usePendingOrders } from '../composables/usePendingOrders'

const router = useRouter()
const route = useRoute()

const { pendingCount } = usePendingOrders()

// 搜索
const searchQuery = ref('')

const menuList = [
  {
    group: '概览',
    items: [
      { path: '/dashboard', title: '工作台', icon: 'dashboard' }
    ]
  },
  {
    group: '商品运营',
    items: [
      { path: '/drink', title: '饮品管理', icon: 'drink' },
      { path: '/category', title: '饮品分类', icon: 'category' }
    ]
  },
  {
    group: '交易',
    items: [
      { path: '/order', title: '订单管理', icon: 'order' }
    ]
  },
  {
    group: '系统',
    items: [
      { path: '/employee', title: '员工管理', icon: 'employee' }
    ]
  }
]

const breadcrumb = computed(() => {
  const matched = route.matched.filter(item => item.meta?.title)
  return matched.map(item => item.meta.title)
})

const userInfo = computed(() => {
  const raw = localStorage.getItem('admin_info')
  return raw ? JSON.parse(raw) : { name: '管理员', role: '店长' }
})

function handleMenuClick(path) {
  router.push(path)
}

function handleLogout() {
  if (!confirm('退出登录？')) return
  localStorage.removeItem('admin_token')
  localStorage.removeItem('admin_info')
  router.push('/login')
}
</script>

<template>
  <div class="admin-layout">
    <!-- 左侧菜单栏 -->
    <aside class="sidebar">
      <div class="brand">
        <div class="logo">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 2h12l-1 7a5 5 0 0 1-10 0L6 2Z"/><path d="M6 8H4a2 2 0 0 0 0 4h2"/><path d="M5 22h14"/><path d="M12 14v8"/></svg>
        </div>
        <div class="brand-text">
          <span class="brand-name">茶咖优选</span>
          <span class="brand-sub">餐饮管理后台</span>
        </div>
      </div>

      <nav class="nav-menu">
        <div v-for="group in menuList" :key="group.group" class="menu-group">
          <div class="group-title">{{ group.group }}</div>
          <div
            v-for="item in group.items"
            :key="item.path"
            class="menu-item"
            :class="{ active: route.path === item.path }"
            @click="handleMenuClick(item.path)"
          >
            <span class="menu-icon">
              <svg v-if="item.icon === 'dashboard'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="9"/><rect x="14" y="3" width="7" height="5"/><rect x="14" y="12" width="7" height="9"/><rect x="3" y="16" width="7" height="5"/></svg>
              <svg v-else-if="item.icon === 'drink'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 2h12l-1 7a5 5 0 0 1-10 0L6 2Z"/><path d="M6 8H4a2 2 0 0 0 0 4h2"/><path d="M5 22h14"/></svg>
              <svg v-else-if="item.icon === 'category'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/></svg>
              <svg v-else-if="item.icon === 'order'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 2 6 6H2"/><path d="M2 6h20l-2 13a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2L2 6Z"/><path d="M9 11v5"/><path d="M15 11v5"/></svg>
              <svg v-else-if="item.icon === 'employee'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/></svg>
            </span>
            <span class="menu-text">{{ item.title }}</span>
            <span v-if="item.path === '/order' && pendingCount > 0" class="menu-badge">
              <span class="menu-badge-count">{{ pendingCount }}</span>
            </span>
          </div>
        </div>
      </nav>

      <div class="sidebar-footer">
        <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="10"/><path d="M12 16v-4M12 8h.01"/></svg>
        <span>v1.0</span>
      </div>
    </aside>

    <!-- 右侧主区域 -->
    <div class="main-area">
      <!-- 顶部导航栏 -->
      <header class="topbar">
        <div class="crumb">
          <span v-for="(item, index) in breadcrumb" :key="index">
            <span v-if="index > 0"> / </span>
            <b v-if="index === breadcrumb.length - 1">{{ item }}</b>
            <span v-else>{{ item }}</span>
          </span>
        </div>
        <div class="search-pill">
          <el-input v-model="searchQuery" placeholder="搜索…" clearable>
            <template #prefix>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="m21 21-4.3-4.3"/></svg>
            </template>
          </el-input>
        </div>
        <div class="topbar-right">
          <div class="icon-btn" title="通知">
            <span class="ping"></span>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg>
          </div>
          <div class="user-area" @click="handleLogout" title="点击退出登录">
            <div class="user-avatar">{{ userInfo.name?.charAt(0) || '管' }}</div>
            <div class="user-detail">
              <span class="user-name">{{ userInfo.name }}</span>
              <span class="user-role">{{ userInfo.role }}</span>
            </div>
          </div>
        </div>
      </header>

      <!-- 内容区域 -->
      <main class="content-area">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped src="../styles/layout/admin-layout.css"></style>
