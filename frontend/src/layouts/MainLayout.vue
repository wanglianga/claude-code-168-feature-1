<template>
  <el-container style="min-height: 100vh">
    <el-aside :width="asideWidth">
      <div class="logo">
        <el-icon><Shop /></el-icon>
        <span v-if="!collapsed">集市摊位管理平台</span>
      </div>
      <el-menu :default-active="$route.path" router :collapse="collapsed" background-color="#001529" text-color="#a6adb4" active-text-color="#fff">
        <el-menu-item index="/home"><el-icon><HomeFilled /></el-icon><span>首页</span></el-menu-item>
        <template v-if="role === 'VENDOR'">
          <el-menu-item index="/apply"><el-icon><EditPen /></el-icon><span>摊位报名</span></el-menu-item>
          <el-menu-item index="/result"><el-icon><Ticket /></el-icon><span>抽签结果与缴费</span></el-menu-item>
          <el-menu-item index="/relocations"><el-icon><Umbrella /></el-icon><span>雨天调位通知</span></el-menu-item>
          <el-menu-item index="/samples"><el-icon><Food /></el-icon><span>食品留样</span></el-menu-item>
          <el-menu-item index="/trace"><el-icon><Document /></el-icon><span>信用与追溯</span></el-menu-item>
        </template>
        <template v-if="role === 'STAFF'">
          <el-menu-item index="/entry"><el-icon><Iphone /></el-icon><span>入场核验</span></el-menu-item>
          <el-menu-item index="/patrol"><el-icon><View /></el-icon><span>巡查登记</span></el-menu-item>
        </template>
        <template v-if="role === 'ADMIN'">
          <el-menu-item index="/admin/events"><el-icon><Calendar /></el-icon><span>集市与报名审核</span></el-menu-item>
          <el-menu-item index="/admin/stalls"><el-icon><Grid /></el-icon><span>区域摊位管理</span></el-menu-item>
          <el-menu-item index="/admin/lottery"><el-icon><MagicStick /></el-icon><span>抽签管理</span></el-menu-item>
          <el-menu-item index="/admin/complaints"><el-icon><ChatDotSquare /></el-icon><span>投诉处理</span></el-menu-item>
          <el-menu-item index="/admin/rain-relocation"><el-icon><Umbrella /></el-icon><span>雨天临时调位</span></el-menu-item>
          <el-menu-item index="/admin/adjustments"><el-icon><SetUp /></el-icon><span>调整与追溯</span></el-menu-item>
          <el-menu-item index="/admin/dashboard"><el-icon><DataAnalysis /></el-icon><span>复盘看板</span></el-menu-item>
          <el-menu-item index="/admin/audits"><el-icon><Memo /></el-icon><span>审计日志</span></el-menu-item>
        </template>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <el-icon class="collapse-btn" @click="collapsed = !collapsed"><Fold v-if="!collapsed" /><Expand v-else /></el-icon>
        <div class="spacer" />
        <el-link href="/complaint" target="_blank" style="margin-right: 16px">消费者投诉入口</el-link>
        <el-dropdown>
          <span class="user-info">
            {{ user?.name }}（{{ roleText }}）<el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main style="padding: 0">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../store/auth'
import { Shop, HomeFilled, EditPen, Ticket, Food, Document, Iphone, View, Calendar, Grid, MagicStick, ChatDotSquare, SetUp, DataAnalysis, Memo, Fold, Expand, ArrowDown, Umbrella } from '@element-plus/icons-vue'

const auth = useAuthStore()
const router = useRouter()
const collapsed = ref(false)
const user = computed(() => auth.user)
const role = computed(() => auth.role)
const asideWidth = computed(() => collapsed.value ? '64px' : '220px')
const roleText = computed(() => ({ VENDOR: '摊主', STAFF: '工作人员', ADMIN: '乡镇管理员' }[role.value] || ''))

function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<style scoped>
.logo { height: 60px; display: flex; align-items: center; justify-content: center; gap: 8px; color: #fff; background: #001529; font-weight: 600; font-size: 15px; overflow: hidden; white-space: nowrap; }
.el-aside { background: #001529; transition: width .2s; }
.el-menu { border-right: none; }
.header { background: #fff; display: flex; align-items: center; box-shadow: 0 1px 4px rgba(0,21,41,.08); }
.collapse-btn { cursor: pointer; font-size: 18px; }
.spacer { flex: 1; }
.user-info { cursor: pointer; display: flex; align-items: center; gap: 4px; }
</style>
