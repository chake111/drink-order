import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../utils/request.js'

const statusMap = {
  1: { label: '待接单', cls: 'ob-wait' },
  2: { label: '制作中', cls: 'ob-making' },
  3: { label: '待取餐', cls: 'ob-ready' },
  4: { label: '已完成', cls: 'ob-done' },
  5: { label: '已取消', cls: 'ob-cancel' }
}

// 时间线步骤配置
const timelineSteps = [
  { status: 1, label: '提交订单' },
  { status: 2, label: '商家接单' },
  { status: 3, label: '制作完成' },
  { status: 4, label: '已取餐' }
]

/**
 * 订单管理页：分页查询、状态流转（接单/完成/取餐/拒单）与详情抽屉。
 */
export function useOrderPage() {
  const loading = ref(false)
  const tableData = ref([])
  const total = ref(0)
  const queryParams = ref({ page: 1, pageSize: 10, orderNo: '', status: null })

  // 详情抽屉
  const drawerVisible = ref(false)
  const currentOrder = ref(null)

  // 拒单对话框
  const rejectDialogVisible = ref(false)
  const rejectReason = ref('')
  const rejectOrderId = ref(null)

  async function loadData() {
    loading.value = true
    try {
      const res = await request.get('/order/page', { params: queryParams.value })
      tableData.value = res.data.records
      total.value = res.data.total
    } catch (e) {} finally { loading.value = false }
  }

  function handleSearch() { queryParams.value.page = 1; loadData() }
  function handleReset() {
    queryParams.value = { page: 1, pageSize: 10, orderNo: '', status: null }
    loadData()
  }
  function handlePageChange(page) { queryParams.value.page = page; loadData() }
  function handleSizeChange(size) { queryParams.value.pageSize = size; queryParams.value.page = 1; loadData() }

  async function handleConfirm(id) {
    try {
      await request.post(`/order/confirm/${id}`)
      ElMessage.success('接单成功')
      loadData()
    } catch (e) {}
  }

  async function handleComplete(id) {
    try {
      await request.post(`/order/complete/${id}`)
      ElMessage.success('制作完成')
      loadData()
    } catch (e) {}
  }

  async function handlePickup(id) {
    try {
      await request.post(`/order/pickup/${id}`)
      ElMessage.success('确认取餐')
      loadData()
    } catch (e) {}
  }

  function handleReject(id) {
    rejectOrderId.value = id
    rejectReason.value = ''
    rejectDialogVisible.value = true
  }

  async function submitReject() {
    if (!rejectReason.value.trim()) {
      ElMessage.warning('请输入拒单原因')
      return
    }
    try {
      await request.post(`/order/reject/${rejectOrderId.value}`, { rejectReason: rejectReason.value })
      ElMessage.success('已拒单')
      rejectDialogVisible.value = false
      loadData()
    } catch (e) {}
  }

  async function handleDetail(row) {
    try {
      const res = await request.get(`/order/${row.id}`)
      currentOrder.value = res.data
      drawerVisible.value = true
    } catch (e) {}
  }

  onMounted(() => { loadData() })

  return {
    loading, tableData, total, queryParams, statusMap, timelineSteps,
    drawerVisible, currentOrder, rejectDialogVisible, rejectReason,
    loadData, handleSearch, handleReset, handlePageChange, handleSizeChange,
    handleConfirm, handleComplete, handlePickup, handleReject, submitReject, handleDetail
  }
}