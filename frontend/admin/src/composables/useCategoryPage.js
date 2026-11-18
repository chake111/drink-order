import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../utils/request.js'
import { updateCategorySort } from '../api/category.js'

// 图标映射（新增/编辑抽屉里的图标选择器）
const iconMap = {
  cup: '<path d="M6 2h12l-1 7a5 5 0 0 1-10 0L6 2Z"/><path d="M6 8H4a2 2 0 0 0 0 4h2"/><path d="M5 22h14"/>',
  coffee: '<path d="M17 8h1a4 4 0 1 1 0 8h-1"/><path d="M3 8h14v9a4 4 0 0 1-4 4H7a4 4 0 0 1-4-4Z"/><line x1="6" y1="2" x2="6" y2="4"/><line x1="10" y1="2" x2="10" y2="4"/><line x1="14" y1="2" x2="14" y2="4"/>',
  fruit: '<path d="M12 2a3 3 0 0 0 3 3"/><path d="M18.5 7.5C20 9 21 11.5 21 14a9 9 0 0 1-18 0c0-3 1.5-6 4-7.5C9 5 11 6 12 6s3-1 4.5 0Z"/>',
  soda: '<path d="M5 7h14l-1.5 13a1 1 0 0 1-1 1h-9a1 1 0 0 1-1-1L5 7Z"/><path d="M5 7l1-3h12l1 3"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/>',
  cake: '<path d="M3 21h18M4 16h16v5H4zM5 16v-4a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v4"/><path d="M12 6V3M9 6V4M15 6V4"/>'
}

/**
 * 饮品分类页：列表、拖拽排序、启停与新增/编辑。
 */
export function useCategoryPage() {
  const loading = ref(false)
  const tableData = ref([])
  const drawerVisible = ref(false)
  const drawerTitle = ref('新增分类')
  const form = ref({ name: '', icon: '', sort: 0, status: 1 })
  const formRef = ref(null)

  const rules = {
    name: [{ required: true, message: '请输入分类名称', trigger: 'blur' }]
  }

  const iconOptions = Object.keys(iconMap)

  async function loadData() {
    loading.value = true
    try {
      const res = await request.get('/category/list')
      tableData.value = res.data
    } catch (e) {} finally { loading.value = false }
  }

  function handleAdd() {
    drawerTitle.value = '新增分类'
    form.value = { name: '', icon: '', sort: 0, status: 1 }
    drawerVisible.value = true
  }

  function handleEdit(row) {
    drawerTitle.value = '编辑分类'
    form.value = { ...row }
    drawerVisible.value = true
  }

  async function handleSubmit() {
    try {
      await formRef.value.validate()
      if (!form.value.icon) {
        ElMessage.warning('请选择一个图标')
        return
      }
      if (form.value.id) {
        await request.put('/category', form.value)
        ElMessage.success('修改成功')
      } else {
        const { sort, ...rest } = form.value
        await request.post('/category', sort === 0 ? rest : { ...rest, sort })
        ElMessage.success('新增成功')
      }
      drawerVisible.value = false
      loadData()
    } catch (e) {}
  }

  async function handleStatusChange(row) {
    const newStatus = row.status === 1 ? 0 : 1
    try {
      await request.post(`/category/status/${newStatus}`, null, { params: { id: row.id } })
      loadData()
      ElMessage.success(newStatus === 1 ? '已启用：' + row.name : '已停用：' + row.name)
    } catch (e) {}
  }

  async function handleDelete(row) {
    try {
      await ElMessageBox.confirm(`确定删除分类「${row.name}」吗？`, '提示', { type: 'warning' })
      await request.delete(`/category/${row.id}`)
      ElMessage.success('已删除')
      loadData()
    } catch (e) {
      if (e !== 'cancel' && e?.message) ElMessage.error(e.message)
    }
  }

  async function handleDragEnd() {
    const ids = tableData.value.map(c => c.id)
    try {
      await updateCategorySort(ids)
      ElMessage.success('排序已更新')
      loadData()
    } catch (e) {
      ElMessage.error('排序更新失败')
      loadData()
    }
  }

  onMounted(() => { loadData() })

  return {
    loading, tableData, drawerVisible, drawerTitle, form, formRef, rules,
    iconMap, iconOptions,
    loadData, handleAdd, handleEdit, handleSubmit, handleStatusChange, handleDelete, handleDragEnd
  }
}