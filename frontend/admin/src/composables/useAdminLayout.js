import { ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { usePendingOrders } from './usePendingOrders'

/**
 * 管理端布局：侧栏菜单、面包屑、登录用户与退出。
 */
export function useAdminLayout() {
  const router = useRouter()
  const route = useRoute()
  const { pendingCount } = usePendingOrders()

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

  return { route, pendingCount, searchQuery, menuList, breadcrumb, userInfo, handleMenuClick, handleLogout }
}