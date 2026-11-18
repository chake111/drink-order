<script setup>
import draggable from 'vuedraggable'
import { useCategoryPage } from '../composables/useCategoryPage.js'

const {
  loading, tableData, drawerVisible, drawerTitle, form, formRef, rules,
  iconMap, iconOptions,
  handleAdd, handleEdit, handleSubmit, handleStatusChange, handleDelete, handleDragEnd
} = useCategoryPage()
</script>

<template>
  <div class="category-page">
    <div class="page-header">
      <div>
        <h1>饮品分类</h1>
        <p>管理饮品分类，支持排序、启用停用；停用后该分类饮品在用户端隐藏</p>
      </div>
      <el-button type="primary" @click="handleAdd">新增分类</el-button>
    </div>

    <!-- 卡片网格（可拖拽排序） -->
    <draggable
      v-model="tableData"
      item-key="id"
      handle=".drag-handle"
      animation="200"
      ghost-class="cat-ghost"
      chosen-class="cat-chosen"
      drag-class="cat-drag"
      :disabled="loading"
      v-loading="loading"
      class="cat-grid"
      @end="handleDragEnd"
    >
      <template #item="{ element: cat }">
        <div class="cat-card">
          <div class="drag-handle" title="拖拽排序">
            <svg width="14" height="14" viewBox="0 0 16 16" fill="currentColor"><circle cx="5" cy="3" r="1.5"/><circle cx="11" cy="3" r="1.5"/><circle cx="5" cy="8" r="1.5"/><circle cx="11" cy="8" r="1.5"/><circle cx="5" cy="13" r="1.5"/><circle cx="11" cy="13" r="1.5"/></svg>
          </div>
          <div class="cat-icon">
            <svg v-if="iconMap[cat.icon]" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" v-html="iconMap[cat.icon]"></svg>
            <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><line x1="3" y1="6" x2="3.01" y2="6"/><line x1="3" y1="12" x2="3.01" y2="12"/><line x1="3" y1="18" x2="3.01" y2="18"/></svg>
          </div>
          <div class="cat-mid">
            <div class="cat-name">{{ cat.name }}</div>
            <div class="cat-meta">{{ cat.drinkCount ?? 0 }} 款饮品 · {{ cat.status === 1 ? '已启用' : '已停用' }}</div>
          </div>
          <div class="cat-actions">
            <span class="toggle" :class="{ on: cat.status === 1 }" @click="handleStatusChange(cat)"></span>
            <div class="cat-ops">
              <span class="op-text" @click="handleEdit(cat)">编辑</span>
              <span class="op-sep"></span>
              <span class="op-text del" @click="handleDelete(cat)">删除</span>
            </div>
          </div>
        </div>
      </template>
      <template #footer>
        <div v-if="!loading && tableData.length === 0" class="cat-empty">
          暂无分类数据
        </div>
      </template>
    </draggable>

    <p class="cat-foot" v-if="tableData.length > 0">共 {{ tableData.length }} 个分类</p>

    <!-- 新增/编辑抽屉 -->
    <el-drawer v-model="drawerVisible" :title="drawerTitle" size="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="分类名称" prop="name">
          <el-input v-model="form.name" placeholder="如：奶茶、咖啡、鲜果茶" />
        </el-form-item>
        <el-form-item label="分类图标">
          <div class="icon-picker">
            <div
              v-for="key in iconOptions"
              :key="key"
              class="icon-opt"
              :class="{ picked: form.icon === key }"
              @click="form.icon = key"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" v-html="iconMap[key]"></svg>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="状态">
          <div class="switch-field">
            <div class="switch-text">启用</div>
            <span class="toggle" :class="{ on: form.status === 1 }" @click="form.status = form.status === 1 ? 0 : 1"></span>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">保存分类</el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped src="../styles/views/category.css"></style>
