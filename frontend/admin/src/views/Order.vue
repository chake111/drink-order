<script setup>
import { formatTime } from '../utils/format.js'
import { useOrderPage } from '../composables/useOrderPage.js'

const {
  loading, tableData, total, queryParams, statusMap, timelineSteps,
  drawerVisible, currentOrder, rejectDialogVisible, rejectReason,
  handleSearch, handleReset, handlePageChange, handleSizeChange,
  handleConfirm, handleComplete, handlePickup,
  handleReject, submitReject, handleDetail
} = useOrderPage()
</script>

<template>
  <div class="order-page">
    <div class="page-header">
      <div>
        <h1>订单管理</h1>
        <p>查看和管理用户订单，支持接单、制作完成、取餐确认</p>
      </div>
    </div>

    <!-- 筛选栏 -->
    <div class="filter-bar">
      <div class="status-tabs">
        <span class="status-tab" :class="{ active: !queryParams.status }" @click="queryParams.status = null; handleSearch()">全部</span>
        <span class="status-tab" :class="{ active: queryParams.status === 1 }" @click="queryParams.status = 1; handleSearch()">待接单</span>
        <span class="status-tab" :class="{ active: queryParams.status === 2 }" @click="queryParams.status = 2; handleSearch()">制作中</span>
        <span class="status-tab" :class="{ active: queryParams.status === 3 }" @click="queryParams.status = 3; handleSearch()">待取餐</span>
        <span class="status-tab" :class="{ active: queryParams.status === 4 }" @click="queryParams.status = 4; handleSearch()">已完成</span>
        <span class="status-tab" :class="{ active: queryParams.status === 5 }" @click="queryParams.status = 5; handleSearch()">已取消</span>
      </div>
      <div class="filter-row">
        <el-input v-model="queryParams.orderNo" placeholder="订单号"  @clear="handleSearch" @keyup.enter="handleSearch" />
        <el-button @click="handleReset">重置</el-button>
        <el-button type="primary" @click="handleSearch">查询</el-button>
      </div>
    </div>

    <div class="table-card">
      <el-table :data="tableData" v-loading="loading">
        <el-table-column label="取餐号" width="70">
          <template #default="{ row }"><b class="en" style="font-size:15px;color:var(--primary)">{{ row.pickupNo }}</b></template>
        </el-table-column>
        <el-table-column label="订单号" prop="orderNo"  />
        <el-table-column label="顾客" width="150">
          <template #default="{ row }">
            <div class="ocust">
              <span class="oav">{{ (row.userName || '?').charAt(0) }}</span>
              <div>
                <div class="on-name">{{ row.userName || '未知用户' }}</div>
                <div class="on-phone">{{ row.userPhone || '-' }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="商品" show-overflow-tooltip width="150">
          <template #default="{ row }">
            <div v-for="item in row.details" :key="item.drinkName" class="order-item">
              {{ item.drinkName }} x{{ item.quantity }}
              <span v-if="item.spec" class="spec">{{ item.spec }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="金额">
          <template #default="{ row }"><span class="price">&yen;{{ row.amount?.toFixed(2) }}</span></template>
        </el-table-column>
        <el-table-column label="取餐方式" >
          <template #default="{ row }">{{ row.pickupType || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态">
          <template #default="{ row }">
            <span class="obadge" :class="statusMap[row.status]?.cls"><i></i>{{ statusMap[row.status]?.label }}</span>
          </template>
        </el-table-column>
        <el-table-column label="下单时间">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" fixed="right" width="180">
          <template #default="{ row }">
            <span v-if="row.status === 1" class="op-text" @click="handleConfirm(row.id)">接单</span>
            <span v-if="row.status === 1" class="op-sep"></span>
            <span v-if="row.status === 1" class="op-text del" @click="handleReject(row.id)">拒单</span>
            <span v-if="row.status === 2" class="op-text" @click="handleComplete(row.id)">完成制作</span>
            <span v-if="row.status === 3" class="op-text" @click="handlePickup(row.id)">确认取餐</span>
            <span v-if="row.status >= 1 && row.status <= 3" class="op-sep"></span>
            <span class="op-text" @click="handleDetail(row)">详情</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <span class="pager-info">共 {{ total }} 条</span>
        <el-pagination v-model:current-page="queryParams.page" v-model:page-size="queryParams.pageSize" :total="total" :page-sizes="[10, 20, 50]" layout="sizes, prev, pager, next" @current-change="handlePageChange" @size-change="handleSizeChange" />
      </div>
    </div>

    <!-- 订单详情抽屉 -->
    <el-drawer v-model="drawerVisible" title="订单详情" size="540px" class="order-drawer">
      <template v-if="currentOrder">
        <!-- 当前状态 -->
        <div class="od-sec">
          <h4>当前状态</h4>
          <div style="display:flex;align-items:center;gap:12px">
            <span class="obadge" :class="statusMap[currentOrder.status]?.cls"><i></i>{{ statusMap[currentOrder.status]?.label }}</span>
            <span style="font-size:14px;color:var(--ink-2)">取餐号 <b class="en" style="color:var(--primary)">{{ currentOrder.pickupNo }}</b></span>
          </div>
        </div>

        <!-- 顾客信息 -->
        <div class="od-sec">
          <h4>顾客信息</h4>
          <div class="od-card">
            <div class="od-info-row"><span class="k">顾客</span><span class="v">{{ currentOrder.userName || '未知用户' }}</span></div>
            <div class="od-info-row"><span class="k">联系电话</span><span class="v mono">{{ currentOrder.userPhone || '-' }}</span></div>
            <div class="od-info-row"><span class="k">取餐方式</span><span class="v">{{ currentOrder.pickupType }}</span></div>
            <div class="od-info-row" v-if="currentOrder.remark"><span class="k">备注</span><span class="v">{{ currentOrder.remark }}</span></div>
          </div>
        </div>

        <!-- 商品明细 -->
        <div class="od-sec">
          <h4>商品明细</h4>
          <div class="od-card">
            <div class="od-line" v-for="item in currentOrder.details" :key="item.drinkName">
              <div class="lthumb">
                <img v-if="item.image" :src="item.image" alt="" />
              </div>
              <div class="lmid">
                <div class="lname">{{ item.drinkName }}</div>
                <div class="lspec" v-if="item.spec">{{ item.spec }}</div>
              </div>
              <div class="lqty">x{{ item.quantity }}</div>
              <div class="lprice">&yen;{{ (item.price * item.quantity)?.toFixed(2) }}</div>
            </div>
            <div class="od-total">
              <span class="tl">合计</span>
              <span class="tv">&yen;{{ currentOrder.amount?.toFixed(2) }}</span>
            </div>
          </div>
        </div>

        <!-- 订单进度 -->
        <div class="od-sec">
          <h4>订单进度</h4>
          <div class="od-steps">
            <!-- 已取消状态 -->
            <template v-if="currentOrder.status === 5">
              <div class="od-step done">
                <span class="sdot">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M9 12l2 2 4-4"/></svg>
                </span>
                <div class="stext">
                  <div class="st">提交订单</div>
                  <div class="stime mono">{{ formatTime(currentOrder.createTime) }}</div>
                </div>
              </div>
              <div class="od-step done">
                <span class="sdot" style="background:var(--off);border-color:var(--off)">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M18 6L6 18M6 6l12 12"/></svg>
                </span>
                <div class="stext">
                  <div class="st" style="color:var(--off)">订单已取消</div>
                  <div class="stime mono" v-if="currentOrder.rejectReason">原因：{{ currentOrder.rejectReason }}</div>
                </div>
              </div>
            </template>
            <!-- 正常状态时间线 -->
            <template v-else>
              <div v-for="step in timelineSteps" :key="step.status" class="od-step" :class="{ done: currentOrder.status >= step.status }">
                <span class="sdot">
                  <svg v-if="currentOrder.status >= step.status" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M9 12l2 2 4-4"/></svg>
                  <template v-else>{{ step.status }}</template>
                </span>
                <div class="stext">
                  <div class="st">{{ step.label }}</div>
                  <div class="stime mono" v-if="step.status === 1">{{ formatTime(currentOrder.createTime) }}</div>
                </div>
              </div>
            </template>
          </div>
        </div>
      </template>

      <!-- 底部操作按钮 -->
      <template #footer v-if="currentOrder">
        <div class="drawer-footer">
          <el-button v-if="currentOrder.status === 1" @click="handleReject(currentOrder.id)">拒单</el-button>
          <el-button v-if="currentOrder.status === 1" type="primary" @click="handleConfirm(currentOrder.id)">接单</el-button>
          <el-button v-if="currentOrder.status === 2" type="primary" @click="handleComplete(currentOrder.id)">完成制作</el-button>
          <el-button v-if="currentOrder.status === 3" type="primary" @click="handlePickup(currentOrder.id)">确认取餐</el-button>
        </div>
      </template>
    </el-drawer>

    <!-- 拒单对话框 -->
    <el-dialog v-model="rejectDialogVisible" title="拒单" width="400px">
      <el-form-item label="拒单原因">
        <el-input v-model="rejectReason" type="textarea" :rows="3" placeholder="请输入拒单原因" />
      </el-form-item>
      <template #footer>
        <el-button @click="rejectDialogVisible = false">取消</el-button>
        <el-button type="danger" @click="submitReject">确认拒单</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped src="../styles/views/order.css"></style>
