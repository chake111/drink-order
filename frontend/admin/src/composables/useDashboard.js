import { ref, computed, onMounted } from 'vue'
import request from '../utils/request.js'

/**
 * 工作台数据：今日 KPI、近 7 日趋势、热销 TOP5。
 */
export function useDashboard() {
  const todayData = ref({
    todayOrders: 0,
    todayAmount: 0,
    pendingOrders: 0,
    waitingOrders: 0,
    makingOrders: 0,
    completedOrders: 0,
    avgOrderValue: 0,
    ordersChange: 0,
    amountChange: 0,
    pendingChange: 0,
    completedChange: 0
  })
  const trendData = ref([])
  const topData = ref([])

  const hasTrendData = computed(() => trendData.value.length > 0)

  // 客单价 = 营业额 / 订单数
  const avgOrderDisplay = computed(() => {
    if (!todayData.value.todayOrders) return '0.00'
    return (todayData.value.todayAmount / todayData.value.todayOrders).toFixed(2)
  })

  // 待处理订单数 = 待接单 + 制作中（与原型一致）
  const pendingDisplay = computed(() => {
    const p = todayData.value.pendingOrders
    if (p > 0) return p
    return (todayData.value.waitingOrders || 0) + (todayData.value.makingOrders || 0)
  })

  onMounted(async () => {
    try {
      const [todayRes, trendRes, topRes] = await Promise.all([
        request.get('/dashboard/today'),
        request.get('/dashboard/trend'),
        request.get('/dashboard/top')
      ])
      if (todayRes.data) {
        const d = todayRes.data
        todayData.value = {
          todayOrders: d.todayOrders ?? 0,
          todayAmount: d.todayAmount ?? 0,
          pendingOrders: d.pendingOrders ?? 0,
          waitingOrders: d.waitingOrders ?? 0,
          makingOrders: d.makingOrders ?? 0,
          completedOrders: d.completedOrders ?? 0,
          ordersChange: d.ordersChange ?? 0,
          amountChange: d.amountChange ?? 0,
          pendingChange: d.pendingChange ?? 0,
          completedChange: d.completedChange ?? 0
        }
      }
      trendData.value = trendRes.data || []
      topData.value = topRes.data || []
    } catch (e) {
      console.error('获取工作台数据失败:', e)
    }
  })

  return { todayData, trendData, topData, hasTrendData, avgOrderDisplay, pendingDisplay }
}