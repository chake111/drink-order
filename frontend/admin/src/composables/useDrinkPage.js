import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../utils/request.js'

/**
 * 饮品管理页的状态与接口调用。
 * 页面只负责布局与展示，交互逻辑集中在这里。
 */
export function useDrinkPage() {
  const loading = ref(false)
  const tableData = ref([])
  const total = ref(0)
  const queryParams = ref({ page: 1, pageSize: 10, name: '', categoryId: null, status: null })
  const categoryList = ref([])
  const selectedIds = ref([])

  // 抽屉相关
  const drawerVisible = ref(false)
  const drawerTitle = ref('新增饮品')
  const form = ref({ name: '', categoryId: null, price: null, description: '', flavors: [], image: '', status: 1 })
  const formRef = ref(null)

  const rules = {
    name: [{ required: true, message: '请输入饮品名称', trigger: 'blur' }],
    categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }],
    price: [{ required: true, message: '请输入价格', trigger: 'blur' }]
  }

  async function loadData() {
    loading.value = true
    try {
      const res = await request.get('/drink/page', { params: queryParams.value })
      tableData.value = res.data.records
      total.value = res.data.total
    } catch (e) { } finally { loading.value = false }
  }

  async function loadCategories() {
    try {
      const res = await request.get('/category/list')
      categoryList.value = res.data
    } catch (e) { }
  }

  function handleSearch() { queryParams.value.page = 1; loadData() }
  function handleReset() {
    queryParams.value = { page: 1, pageSize: 10, name: '', categoryId: null, status: null }
    loadData()
  }
  function handlePageChange(page) { queryParams.value.page = page; loadData() }
  function handleSizeChange(size) { queryParams.value.pageSize = size; queryParams.value.page = 1; loadData() }
  function handleSelectionChange(rows) { selectedIds.value = rows.map(r => r.id) }

  function handleAdd() {
    drawerTitle.value = '新增饮品'
    form.value = { name: '', categoryId: null, price: null, description: '', flavors: [], image: '', status: 1 }
    drawerVisible.value = true
  }

  function handleEdit(row) {
    drawerTitle.value = '编辑饮品'
    form.value = {
      id: row.id,
      name: row.name,
      categoryId: row.categoryId,
      price: row.price,
      description: row.description,
      flavors: row.flavors ? row.flavors.map(f => ({ ...f })) : [],
      image: row.image || '',
      status: row.status
    }
    drawerVisible.value = true
  }

  function addFlavor() {
    form.value.flavors.push({ name: '', value: '' })
  }

  function removeFlavor(index) {
    form.value.flavors.splice(index, 1)
  }

  async function handleSubmit() {
    try {
      await formRef.value.validate()
      if (!form.value.image) {
        ElMessage.warning('请上传饮品图片')
        return
      }
      const formData = new FormData()
      if (form.value.id) formData.append('id', form.value.id)
      formData.append('name', form.value.name)
      formData.append('categoryId', form.value.categoryId)
      formData.append('price', form.value.price)
      formData.append('image', form.value.image)
      formData.append('status', form.value.status)
      if (form.value.description) formData.append('description', form.value.description)
      if (form.value.flavors.length > 0) {
        formData.append('flavors', JSON.stringify(form.value.flavors.filter(f => f.name && f.value)))
      }

      if (form.value.id) {
        await request.put('/drink', formData, { headers: { 'Content-Type': 'multipart/form-data' } })
        ElMessage.success('修改成功')
      } else {
        await request.post('/drink', formData, { headers: { 'Content-Type': 'multipart/form-data' } })
        ElMessage.success('新增成功')
      }
      drawerVisible.value = false
      loadData()
    } catch (e) {
      if (e?.message && !e.message.includes('validate')) ElMessage.error(e.message)
    }
  }

  async function handleStatusChange(id, status) {
    try {
      await request.post(`/drink/status/${status}`, null, { params: { ids: String(id) } })
      loadData()
    } catch (e) {
      if (e?.message) ElMessage.error(e.message)
    }
  }

  async function handleDelete(id) {
    try {
      await ElMessageBox.confirm('确定删除该饮品吗？', '提示', { type: 'warning' })
      await request.delete('/drink', { params: { ids: String(id) } })
      ElMessage.success('删除成功')
      loadData()
    } catch (e) {
      if (e !== 'cancel' && e?.message) ElMessage.error(e.message)
    }
  }

  async function handleBatchStatus(status) {
    if (!selectedIds.value.length) return
    try {
      await request.post(`/drink/status/${status}`, null, { params: { ids: selectedIds.value.join(',') } })
      loadData()
    } catch (e) {
      if (e?.message) ElMessage.error(e.message)
    }
  }

  async function handleBatchDelete() {
    if (!selectedIds.value.length) return
    try {
      await ElMessageBox.confirm(`确定删除选中的 ${selectedIds.value.length} 个饮品吗？`, '提示', { type: 'warning' })
      await request.delete('/drink', { params: { ids: selectedIds.value.join(',') } })
      ElMessage.success('删除成功')
      loadData()
    } catch (e) {
      if (e !== 'cancel' && e?.message) ElMessage.error(e.message)
    }
  }

  onMounted(() => { loadData(); loadCategories() })

  return {
    loading, tableData, total, queryParams, categoryList, selectedIds,
    drawerVisible, drawerTitle, form, formRef, rules,
    loadData, loadCategories,
    handleSearch, handleReset, handlePageChange, handleSizeChange, handleSelectionChange,
    handleAdd, handleEdit, addFlavor, removeFlavor, handleSubmit,
    handleStatusChange, handleDelete, handleBatchStatus, handleBatchDelete
  }
}