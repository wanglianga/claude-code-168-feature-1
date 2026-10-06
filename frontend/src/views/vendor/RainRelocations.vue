<template>
  <div class="page">
    <el-alert v-if="pending.length" type="warning" :closable="false" class="mb"
      :title="`您有 ${pending.length} 张雨天调位通知待处理，请尽快确认（清晨疏导）`" show-icon />
    <el-empty v-if="!list.length" description="暂无雨天调位通知" />

    <!-- 待处理 -->
    <el-card v-for="r in pending" :key="r.id" class="mb" shadow="hover">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <span><b>雨天调位通知 #{{ r.id }}</b> · {{ r.event?.name }}（{{ r.event?.marketDate }}）</span>
          <el-tag type="warning">待您确认</el-tag>
        </div>
      </template>
      <el-row :gutter="20">
        <el-col :span="14">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="原摊位">
              <b style="color:#f56c6c">{{ r.oldStallNo }}</b>（{{ r.oldStall?.zone?.name }}，低洼积水{{ r.floodDepthCm ? '约 ' + r.floodDepthCm + 'cm' : '' }}）
            </el-descriptions-item>
            <el-descriptions-item label="拟调往">
              <b style="color:#409eff">{{ r.newStallNo }}</b>（{{ r.newStall?.zone?.name }}，硬化路面）
            </el-descriptions-item>
            <el-descriptions-item label="新摊入场时间">{{ newEntryTime(r) }}</el-descriptions-item>
            <el-descriptions-item label="距消防通道">
              <el-tag size="small" :type="r.fireLaneDistance < 3 ? 'danger' : 'success'">{{ r.fireLaneDistance }} 米</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="收费差额" :span="2">
              原摊位费 ¥{{ r.oldFee }} → 新摊位费 ¥{{ r.newFee }}，
              <b :style="{ color: r.feeDelta > 0 ? '#e6a23c' : (r.feeDelta < 0 ? '#67c23a' : '#909399') }">
                {{ r.feeDelta > 0 ? `需补收 +¥${r.feeDelta}` : (r.feeDelta < 0 ? `应退 ¥${Math.abs(r.feeDelta)}（财务退款）` : '无差额') }}
              </b>
            </el-descriptions-item>
            <el-descriptions-item label="调位原因" :span="2">{{ r.reason }}</el-descriptions-item>
          </el-descriptions>

          <div class="card-title mt">邻摊影响（新摊位周边 5 米）</div>
          <el-table :data="neighbors(r)" size="small">
            <el-table-column prop="stallNo" label="摊位" width="90" />
            <el-table-column prop="vendorName" label="邻摊摊主" width="120" />
            <el-table-column prop="category" label="品类" />
            <el-table-column label="间距" width="90">
              <template #default="{ row }">{{ row.distanceMeters }} 米</template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!neighbors(r).length" description="周边 5 米内无其他摊位" :image-size="40" />

          <el-input v-model="notes[r.id]" type="textarea" :rows="2" class="mt"
            placeholder="答复备注（选填，如：同意，正在转移货物）" />
          <div class="mt">
            <el-button type="primary" size="large" :loading="acting === r.id" @click="respond(r, true)">
              同意调位（重生成入场二维码）
            </el-button>
            <el-button type="danger" plain size="large" @click="showReject(r)">拒绝调位…</el-button>
          </div>
        </el-col>
        <el-col :span="10">
          <div class="card-title">原摊位积水照片（管理员现场拍摄）</div>
          <el-image v-for="(u, i) in photos(r.oldStallPhotos)" :key="i" :src="u"
            :preview-src-list="photos(r.oldStallPhotos)" :initial-index="i"
            class="thumb-img" fit="cover" preview-teleported />
        </el-col>
      </el-row>
    </el-card>

    <!-- 已处理 -->
    <el-card v-if="history.length">
      <div class="card-title">处理记录</div>
      <el-table :data="history" size="small">
        <el-table-column label="单号" width="70" prop="id" />
        <el-table-column label="集市" min-width="140">
          <template #default="{ row }">{{ row.event?.name }}</template>
        </el-table-column>
        <el-table-column label="摊位变更" width="150">
          <template #default="{ row }">{{ row.oldStallNo }} → <b>{{ row.newStallNo }}</b></template>
        </el-table-column>
        <el-table-column label="差额" width="100">
          <template #default="{ row }">
            <span :style="{ color: row.feeDelta > 0 ? '#e6a23c' : (row.feeDelta < 0 ? '#67c23a' : '#909399') }">
              {{ row.feeDelta > 0 ? '+' : '' }}¥{{ row.feeDelta }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="(RAIN_STATUS[row.status] || {}).type">{{ (RAIN_STATUS[row.status] || {}).text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="答复时间" width="150">
          <template #default="{ row }">{{ fmtTime(row.vendorRespondedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button v-if="['CONFIRMED', 'COMPLETED'].includes(row.status)" size="small" @click="showQr(row)">新入场码</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 拒绝选择 -->
    <el-dialog v-model="rejectVisible" title="拒绝雨天调位" width="480px">
      <el-alert type="error" :closable="false" class="mb"
        title="原摊位低洼积水，拒绝调位存在货物受淹与安全风险，请谨慎选择。" />
      <el-radio-group v-model="rejectDecision">
        <el-radio value="RISK_SELF" border style="display:block;margin-bottom:12px;padding:10px">
          保留原位继续经营，<b>积水风险自担</b>（签署留痕，摊位保留）
        </el-radio>
        <el-radio value="ABANDON" border style="display:block;padding:10px">
          <b>放弃本场经营</b>（取消摊位分配，退场）
        </el-radio>
      </el-radio-group>
      <template #footer>
        <el-button @click="rejectVisible = false">再想想</el-button>
        <el-button type="danger" :loading="acting" @click="confirmReject">确认拒绝</el-button>
      </template>
    </el-dialog>

    <!-- 新二维码 -->
    <el-dialog v-model="qrVisible" title="调位后新入场核验码" width="360px">
      <div style="text-align:center">
        <img v-if="qrUrl" :src="qrUrl" style="width:220px;height:220px" />
        <div class="text-muted" style="word-break:break-all">{{ qrToken }}</div>
        <el-alert type="info" :closable="false" class="mt"
          title="原入场码已作废，入场时请向工作人员出示此新码。" />
        <div v-if="qrRow && qrRow.feeDelta > 0" class="mt">
          <el-tag type="warning">需补收摊位费差额 ¥{{ qrRow.feeDelta }}</el-tag>
          <el-button type="primary" size="small" style="margin-left:8px" @click="$router.push('/result')">去缴费</el-button>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import QRCode from 'qrcode'
import api from '../../api'
import { RAIN_STATUS, fmtTime } from '../../utils/dict'

const list = ref([])
const notes = ref({})
const acting = ref(null)
const rejectVisible = ref(false)
const rejectRow = ref(null)
const rejectDecision = ref('RISK_SELF')
const qrVisible = ref(false)
const qrUrl = ref('')
const qrToken = ref('')
const qrRow = ref(null)
const results = ref([])

const pending = computed(() => list.value.filter(r => r.status === 'PROPOSED'))
const history = computed(() => list.value.filter(r => r.status !== 'PROPOSED'))

function photos(s) { return s ? s.split(',').filter(Boolean) : [] }
function neighbors(r) { try { return JSON.parse(r.neighborImpact || '[]') } catch { return [] } }
function newEntryTime(r) {
  const off = r.newStall?.zone?.entryOffsetMinutes || 0
  const total = 360 + off
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`
}

async function respond(r, accept, decision = null) {
  acting.value = r.id
  try {
    const updated = await api.post(`/vendor/rain-relocations/${r.id}/respond`, {
      accept, decision, note: notes.value[r.id] || ''
    })
    if (accept) {
      ElMessage.success('已同意调位，入场二维码已重新生成')
      await load()
      await showQr(updated, true)
    } else {
      ElMessage.success(decision === 'ABANDON' ? '已登记放弃本场经营' : '已登记保留原位、风险自担')
      await load()
    }
  } finally { acting.value = null }
}

function showReject(r) {
  rejectRow.value = r
  rejectDecision.value = 'RISK_SELF'
  rejectVisible.value = true
}

async function confirmReject() {
  await ElMessageBox.confirm(
    rejectDecision.value === 'ABANDON' ? '确认放弃本场经营？摊位分配将被取消。' : '确认保留原位经营并自担积水风险？该决定将留痕。',
    '再次确认', { type: 'warning' })
  rejectVisible.value = false
  await respond(rejectRow.value, false, rejectDecision.value)
}

async function showQr(row, justConfirmed = false) {
  // 以最新结果上的二维码令牌为准
  const result = results.value.find(x => x.id === row.result?.id)
  const token = result?.qrToken
  if (!token) return ElMessage.warning('未找到新入场码')
  qrRow.value = row
  qrToken.value = token
  qrUrl.value = await QRCode.toDataURL(token, { width: 220, margin: 1 })
  qrVisible.value = true
}

async function load() {
  const [rs, res] = await Promise.all([api.get('/vendor/rain-relocations'), api.get('/vendor/results')])
  list.value = rs
  results.value = res
}

onMounted(load)
</script>

<style scoped>
.thumb-img { width: 130px; height: 95px; border-radius: 4px; margin: 6px 6px 0 0; }
</style>
