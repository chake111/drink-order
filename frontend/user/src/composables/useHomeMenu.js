import { ref, computed, onMounted, reactive } from 'vue'
import { showToast, showSuccessToast } from 'vant'
import { useRouter } from 'vue-router'
import request from '../utils/request'
import { parseFlavorValue } from '../utils/flavor.js'

/**
 * 用户端首页：分类/饮品加载、购物车、口味选择与下单。
 */
export function useHomeMenu() {
  const router = useRouter()
  const categories = ref([])
  const drinks = ref([])
  const activeCatId = ref(null)
  const activeCatName = ref('推荐')
  const loading = ref(false)
  const cartItems = ref([])
  const showCartSheet = ref(false)
  const pickupType = ref('自取')

  // 饮品详情弹窗相关
  const showDetailSheet = ref(false)
  const detailDrink = ref(null)
  const detailQty = ref(1)
  const selectedFlavors = reactive({})

  const totalQty = computed(() => cartItems.value.reduce((sum, item) => sum + item.qty, 0))
  const totalPrice = computed(() => cartItems.value.reduce((sum, item) => sum + item.price * item.qty, 0))

  // 解析口味值（逗号分隔的字符串转数组）由 utils/flavor.js 提供

  // 获取分类列表
  const fetchCategories = async () => {
    try {
      const res = await request.get('/user/category/list')
      if (res.data && res.data.length) {
        categories.value = res.data
        activeCatId.value = res.data[0].id
        activeCatName.value = res.data[0].name
        fetchDrinks(res.data[0].id)
      }
    } catch {
      // 如果用户分类接口失败，尝试公共接口
      try {
        const res = await request.get('/category/list')
        if (res.data && res.data.length) {
          categories.value = res.data.filter(c => c.status === 1)
          if (categories.value.length) {
            activeCatId.value = categories.value[0].id
            activeCatName.value = categories.value[0].name
            fetchDrinks(categories.value[0].id)
          }
        }
      } catch {
        categories.value = [
          { id: 1, name: '推荐' },
          { id: 2, name: '奶茶' },
          { id: 3, name: '咖啡' }
        ]
        activeCatId.value = 1
        activeCatName.value = '推荐'
      }
    }
  }

  // 获取饮品列表
  const fetchDrinks = async (categoryId) => {
    loading.value = true
    try {
      const res = await request.get('/user/drink/list', { params: { categoryId } })
      drinks.value = res.data || []
    } catch {
      drinks.value = []
    } finally {
      loading.value = false
    }
  }

  // 切换分类
  const selectCategory = (id) => {
    activeCatId.value = id
    const cat = categories.value.find(c => c.id === id)
    activeCatName.value = cat ? cat.name : ''
    fetchDrinks(id)
  }

  // 显示饮品详情弹窗
  const showDrinkDetail = async (drink) => {
    // 如果饮品没有口味数据，从接口获取完整信息
    if (!drink.flavors || drink.flavors.length === 0) {
      try {
        const res = await request.get(`/user/drink/${drink.id}`)
        detailDrink.value = res.data
      } catch {
        detailDrink.value = { ...drink, flavors: [] }
      }
    } else {
      detailDrink.value = { ...drink }
    }

    // 重置选择状态
    detailQty.value = 1
    Object.keys(selectedFlavors).forEach(key => delete selectedFlavors[key])

    // 自动选中每个口味的第一个选项
    if (detailDrink.value.flavors && detailDrink.value.flavors.length) {
      detailDrink.value.flavors.forEach(f => {
        const opts = parseFlavorValue(f.value)
        if (opts.length) {
          selectedFlavors[f.name] = opts[0]
        }
      })
    }

    showDetailSheet.value = true
  }

  // 选择口味
  const selectFlavor = (name, value) => {
    selectedFlavors[name] = value
  }

  // 生成口味规格描述
  const buildSpec = () => {
    const parts = Object.entries(selectedFlavors)
      .filter(([_, v]) => v)
      .map(([_, v]) => v)
    return parts.join('/')
  }

  // 获取购物车中某饮品的数量
  const getCartQty = (drinkId) => {
    return cartItems.value
      .filter(i => i.id === drinkId)
      .reduce((sum, item) => sum + item.qty, 0)
  }

  // 快速加入购物车（不选口味，直接添加）
  const quickAddToCart = (drink) => {
    // 如果饮品有口味选项，必须打开详情弹窗让用户选择口味
    if (drink.flavors && drink.flavors.length > 0) {
      showDrinkDetail(drink)
      return
    }
    // 检查是否已有相同饮品且无口味的条目
    const existing = cartItems.value.find(i => i.id === drink.id && !i.spec)
    if (existing) {
      existing.qty++
    } else {
      cartItems.value.push({
        id: drink.id,
        name: drink.name,
        price: drink.price,
        qty: 1,
        spec: ''
      })
    }
  }

  // 带口味加入购物车
  const addToCartWithFlavor = () => {
    if (!detailDrink.value) return

    // 校验所有口味组是否都已选择
    if (detailDrink.value.flavors && detailDrink.value.flavors.length > 0) {
      const missing = detailDrink.value.flavors.filter(f => !selectedFlavors[f.name])
      if (missing.length > 0) {
        showToast(`请选择${missing.map(f => f.name).join('、')}`)
        return
      }
    }

    const spec = buildSpec()
    // 查找已有的相同饮品+相同口味的条目
    const existing = cartItems.value.find(
      i => i.id === detailDrink.value.id && i.spec === spec
    )
    if (existing) {
      existing.qty += detailQty.value
    } else {
      cartItems.value.push({
        id: detailDrink.value.id,
        name: detailDrink.value.name,
        price: detailDrink.value.price,
        qty: detailQty.value,
        spec: spec
      })
    }

    showSuccessToast('已加入购物车')
    showDetailSheet.value = false
  }

  // 减少购物车数量（按索引）
  const decreaseCartItem = (index) => {
    const item = cartItems.value[index]
    if (item) {
      item.qty--
      if (item.qty <= 0) {
        cartItems.value.splice(index, 1)
      }
    }
  }

  // 减少购物车数量（按饮品ID，快速模式）
  const decreaseCart = (drink) => {
    const existing = cartItems.value.find(i => i.id === drink.id && !i.spec)
    if (existing) {
      existing.qty--
      if (existing.qty <= 0) {
        const idx = cartItems.value.indexOf(existing)
        if (idx > -1) cartItems.value.splice(idx, 1)
      }
    } else {
      // 如果没有无口味的条目，减少最后一个该饮品的条目
      const lastItem = [...cartItems.value].reverse().find(i => i.id === drink.id)
      if (lastItem) {
        lastItem.qty--
        if (lastItem.qty <= 0) {
          const idx = cartItems.value.indexOf(lastItem)
          if (idx > -1) cartItems.value.splice(idx, 1)
        }
      }
    }
  }

  // 提交订单
  const submitOrder = async () => {
    if (cartItems.value.length === 0) {
      showToast('购物车为空')
      return
    }
    try {
      const items = cartItems.value.map(item => ({
        drinkId: item.id,
        quantity: item.qty,
        spec: item.spec || ''
      }))
      await request.post('/user/order', {
        pickupType: pickupType.value,
        items
      })
      showSuccessToast('下单成功')
      cartItems.value = []
      showCartSheet.value = false
      router.push('/orders')
    } catch (err) {
      showToast(err.message || '下单失败')
    }
  }

  onMounted(() => {
    fetchCategories()
  })

  return {
    categories, drinks, activeCatId, activeCatName, loading,
    cartItems, showCartSheet, pickupType,
    showDetailSheet, detailDrink, detailQty, selectedFlavors,
    totalQty, totalPrice,
    selectCategory, showDrinkDetail, selectFlavor,
    getCartQty, quickAddToCart, addToCartWithFlavor,
    decreaseCartItem, decreaseCart, submitOrder
  }
}