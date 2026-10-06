<template>
  <div class="page">
    <el-row :gutter="16">
      <el-col :span="24">
        <el-card>
          <h2>欢迎，{{ auth.user?.name }}</h2>
          <p class="text-muted mt">
            <template v-if="role === 'VENDOR'">摊主端：报名 → 抽签结果 → 缴费 → 入场 → 留样 → 信用追溯</template>
            <template v-else-if="role === 'STAFF'">工作人员端：扫码入场核验、巡查登记</template>
            <template v-else>乡镇管理端：集市管理、抽签、投诉处理、调整追溯、复盘看板</template>
          </p>
        </el-card>
      </el-col>
    </el-row>

    <!-- 摊主首页 -->
    <template v-if="role === 'VENDOR'">
      <el-row :gutter="16" class="mt">
        <el-col :span="6"><el-card><div class="stat">{{ auth.user?.creditScore }}</div><div class="stat-label">当前信用分</div></el-card></el-col>
        <el-col :span="6"><el-card><div class="stat">{{ auth.user?.historyCount }}</div><div class="stat-label">历史经营次数</div></el-card></el-col>
        <el-col :span="6"><el-card><div class="stat">{{ applications.length }}</div><div class="stat-label">我的报名</div></el-card></el-col>
        <el-col :span="6"><el-card><div class="stat">{{ unpaidCount }}</div><div class="stat-label">待缴费用</div></el-card></el-col>
      </el-row>
      <el-card class="mt" v-if="pendingRain.length">
        <el-alert type="warning" :closable="false" show-icon
          :title="`有 ${pendingRain.length} 张雨天临时调位通知待您确认：低洼摊位积水，市场已安排硬化路面位置。`" />
        <div style="margin-top:8px">
          <el-tag v-for="r in pendingRain" :key="r.id" type="warning" style="margin-right:8px">
            #{{ r.id }} {{ r.oldStallNo }} → {{ r.newStallNo }}
          </el-tag>
          <el-button type="primary" size="small" style="margin-left:8px" @click="$router.push('/rain')">去确认</el-button>
        </div>
      </el-card>
      <el-card class="mt" v-if="latestResult">
        <div class="card-title">最新抽签结果</div>
        <el-descriptions :column="3" border>
          <el-descriptions-item label="集市">{{ latestResult.application?.event?.name }}</el-descriptions-item>
          <el-descriptions-item label="摊位号"><el-tag type="success" size="large">{{ latestResult.stallNo }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="入场时间">{{ latestResult.entryTime }}</el-descriptions-item>
        </el-descriptions>
        <el-button type="primary" class="mt" @click="$router.push('/result')">查看详情 / 缴费 / 入场码</el-button>
      </el-card>
      <el-card class="mt" v-if="openEvent">
        <div class="card-title">报名中的集市</div>
        <el-alert type="success" :closable="false"
          :title="`${openEvent.name}（${openEvent.marketDate}）正在报名`" />
        <el-button type="primary" class="mt" @click="$router.push('/apply')">立即报名</el-button>
      </el-card>
    </template>

    <!-- 工作人员首页 -->
    <template v-if="role === 'STAFF'">
      <el-row :gutter="16" class="mt">
        <el-col :span="12">
          <el-card><div class="card-title">入场核验</div>
            <p class="text-muted">扫描摊主入场码，核验身份并拍摄摊位布置</p>
            <el-button type="primary" class="mt" @click="$router.push('/entry')">开始核验</el-button>
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card><div class="card-title">巡查登记</div>
            <p class="text-muted">卫生、明码标价、缺斤少两、占道、用电安全</p>
            <el-button type="primary" class="mt" @click="$router.push('/patrol')">开始巡查</el-button>
          </el-card>
        </el-col>
      </el-row>
    </template>

    <!-- 管理员首页 -->
    <template v-if="role === 'ADMIN'">
      <el-row :gutter="16" class="mt">
        <el-col :span="6"><el-card><div class="stat">{{ stats.events }}</div><div class="stat-label">集市场次</div></el-card></el-col>
        <el-col :span="6"><el-card><div class="stat">{{ stats.vendors }}</div><div class="stat-label">注册摊主</div></el-card></el-col>
        <el-col :span="6"><el-card><div class="stat">{{ stats.stalls }}</div><div class="stat-label">摊位总数</div></el-card></el-col>
        <el-col :span="6"><el-card><div class="stat">{{ stats.audits }}</div><div class="stat-label">审计记录</div></el-card></el-col>
      </el-row>
      <el-card class="mt">
        <div class="card-title">快捷入口</div>
        <el-button @click="$router.push('/admin/events')">集市与报名审核</el-button>
        <el-button @click="$router.push('/admin/lottery')">抽签管理</el-button>
        <el-button @click="$router.push('/admin/complaints')">投诉处理</el-button>
        <el-button @click="$router.push('/admin/adjustments')">调整追溯</el-button>
        <el-button @click="$router.push('/admin/dashboard')">复盘看板</el-button>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useAuthStore } from '../store/auth'
import api from '../api'

const auth = useAuthStore()
const role = computed(() => auth.role)
const applications = ref([])
const payments = ref([])
const results = ref([])
const events = ref([])
const stats = ref({ events: 0, vendors: 0, stalls: 0, audits: 0 })
const rainList = ref([])

const latestResult = computed(() => results.value[0])
const unpaidCount = computed(() => payments.value.filter(p => p.status === 'UNPAID').length)
const openEvent = computed(() => events.value.find(e => e.status === 'OPEN'))
const pendingRain = computed(() => rainList.value.filter(r => r.status === 'PROPOSED'))

onMounted(async () => {
  if (role.value === 'VENDOR') {
    const [apps, pays, res, evs, rain] = await Promise.all([
      api.get('/vendor/applications'), api.get('/vendor/payments'),
      api.get('/vendor/results'), api.get('/meta/events'),
      api.get('/vendor/rain-relocations')
    ])
    applications.value = apps; payments.value = pays; results.value = res; events.value = evs; rainList.value = rain
  } else if (role.value === 'ADMIN') {
    const [evs, vendors, stalls, audits] = await Promise.all([
      api.get('/admin/events'), api.get('/admin/vendors'),
      api.get('/admin/stalls'), api.get('/admin/audits')
    ])
    stats.value = { events: evs.length, vendors: vendors.length, stalls: stalls.length, audits: audits.length }
  }
})
</script>

<style scoped>
.stat { font-size: 32px; font-weight: 700; color: #409eff; text-align: center; }
.stat-label { text-align: center; color: #909399; margin-top: 4px; }
</style>
