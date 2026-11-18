<script setup>
import { Delete } from '@element-plus/icons-vue'
import ImageUpload from '../components/ImageUpload.vue'
import { formatTime } from '../utils/format.js'
import { useDrinkPage } from '../composables/useDrinkPage.js'

const {
  loading, tableData, total, queryParams, categoryList, selectedIds,
  drawerVisible, drawerTitle, form, formRef, rules,
  handleSearch, handleReset, handlePageChange, handleSizeChange, handleSelectionChange,
  handleAdd, handleEdit, addFlavor, removeFlavor, handleSubmit,
  handleStatusChange, handleDelete, handleBatchStatus, handleBatchDelete
} = useDrinkPage()
</script>

<template>
  <div class="drink-page">
    <div class="page-header">
      <div>
        <h1>饮品管理</h1>
        <p>维护门店在售饮品，支持分类筛选、起售停售与口味设置</p>
      </div>
      <el-button type="primary" @click="handleAdd">新增饮品</el-button>
    </div>

    <!-- 筛选栏 -->
    <div class="filter-bar">
      <div class="category-tabs">
        <span class="cat-tab" :class="{ active: !queryParams.categoryId }"
          @click="queryParams.categoryId = null; handleSearch()">全部</span>
        <span v-for="cat in categoryList" :key="cat.id" class="cat-tab"
          :class="{ active: queryParams.categoryId === cat.id }"
          @click="queryParams.categoryId = cat.id; handleSearch()">{{ cat.name }}</span>
      </div>
      <div class="filter-row">
        <el-input v-model="queryParams.name" placeholder="饮品名称" clearable style="width: 200px" @clear="handleSearch"
          @keyup.enter="handleSearch" />
        <el-select v-model="queryParams.status" placeholder="全部状态" clearable style="width: 120px"
          @change="handleSearch">
          <el-option label="起售" :value="1" /><el-option label="停售" :value="0" />
        </el-select>
        <el-button @click="handleReset">重置</el-button>
        <el-button type="primary" @click="handleSearch">查询</el-button>
      </div>
    </div>

    <!-- 批量操作 -->
    <div v-if="selectedIds.length > 0" class="batch-bar">
      <span>已选 <b>{{ selectedIds.length }}</b> 项</span>
      <div class="batch-actions">
        <el-button size="small" @click="handleBatchStatus(1)">批量起售</el-button>
        <el-button size="small" @click="handleBatchStatus(0)">批量停售</el-button>
        <el-button size="small" type="danger" @click="handleBatchDelete">批量删除</el-button>
      </div>
    </div>

    <!-- 表格 -->
    <div class="table-card">
      <el-table :data="tableData" v-loading="loading" @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="46" />
        <el-table-column label="饮品" min-width="200">
          <template #default="{ row }">
            <div class="drink-info">
              <div class="drink-thumb"><img v-if="row.image" :src="row.image" alt="" /><span v-else>☕</span></div>
              <div>
                <div class="drink-name">{{ row.name }}</div>
                <div class="drink-desc">{{ row.description }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="分类" width="100">
          <template #default="{ row }"><span class="cat-pill">{{ row.categoryName }}</span></template>
        </el-table-column>
        <el-table-column label="价格" width="100">
          <template #default="{ row }"><span class="price">{{ row.price?.toFixed(2) }}</span></template>
        </el-table-column>
        <el-table-column label="口味规格" min-width="100">
          <template #default="{ row }">
            <div class="flavors"><span v-for="f in row.flavors" :key="f.name" class="flavor-tag">{{ f.name }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span class="st-wrap">
              <span class="toggle" :class="{ on: row.status === 1 }"
                @click="handleStatusChange(row.id, row.status === 1 ? 0 : 1)"></span>
              <span class="txt">{{ row.status === 1 ? '起售' : '停售' }}</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间">
          <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <span class="op-text" @click="handleEdit(row)">编辑</span>
            <span class="op-sep"></span>
            <span class="op-text del" @click="handleDelete(row.id)">删除</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <span class="pager-info">共 {{ total }} 条</span>
        <el-pagination v-model:current-page="queryParams.page" v-model:page-size="queryParams.pageSize" :total="total"
          :page-sizes="[10, 20, 50]" layout="sizes, prev, pager, next" @current-change="handlePageChange"
          @size-change="handleSizeChange" />
      </div>
    </div>

    <!-- 新增/编辑抽屉 -->
    <el-drawer v-model="drawerVisible" size="540px" class="drink-drawer">
      <template #header>
        <div class="drawer-title-group">
          <h2 class="drawer-title">{{ drawerTitle }}</h2>
          <p class="drawer-subtitle">填写饮品基本信息与口味规格</p>
        </div>
      </template>
      <el-form ref="formRef" :model="form" :rules="rules" class="drink-form">
        <el-form-item label="饮品图片" class="image-upload-item">
          <ImageUpload v-model="form.image" />
        </el-form-item>
        <div class="form-two-col">
          <el-form-item label="饮品名称" prop="name">
            <el-input v-model="form.name" placeholder="请输入饮品名称" />
          </el-form-item>
          <el-form-item label="所属分类" prop="categoryId">
            <el-select v-model="form.categoryId" placeholder="请选择分类" style="width: 100%">
              <el-option v-for="cat in categoryList" :key="cat.id" :label="cat.name" :value="cat.id" />
            </el-select>
          </el-form-item>
        </div>
        <div class="form-two-col">
          <el-form-item label="价格" prop="price">
            <div class="price-input">
              <span class="price-prefix">¥</span>
              <el-input v-model="form.price" placeholder="0.00" />
            </div>
          </el-form-item>
          <el-form-item label="初始状态">
            <div class="switch-field">
              <div class="txt">起售</div>
              <span class="toggle" :class="{ on: form.status === 1 }" @click="form.status = form.status === 1 ? 0 : 1"></span>
            </div>
          </el-form-item>
        </div>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="饮品描述" />
        </el-form-item>
        <el-form-item label="口味规格" class="flavor-item">
          <div class="flavor-list">
            <div v-for="(flavor, index) in form.flavors" :key="index" class="flavor-row">
              <el-input v-model="flavor.name" placeholder="口味名" class="flavor-name-input" />
              <el-input v-model="flavor.value" placeholder="选项（逗号分隔）" class="flavor-value-input" />
              <el-button type="danger" link @click="removeFlavor(index)" class="flavor-del-btn">
                <el-icon>
                  <Delete />
                </el-icon>
              </el-button>
            </div>
            <el-button type="primary" link @click="addFlavor" class="add-flavor-btn">
              + 添加口味规格
            </el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">保存饮品</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped src="../styles/views/drink.css"></style>
