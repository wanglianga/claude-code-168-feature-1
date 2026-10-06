<template>
  <div class="page">
    <el-card>
      <div style="display:flex;justify-content:space-between" class="mb">
        <div class="card-title" style="margin:0">雨天临时调位（积水摊位 → 硬化路面，摊主确认后重生成二维码与收费差额）</div>
        <el-select v-model="eventId" placeholder="选择集市" style="width: 240px" @change="reload">
          <el-option v-for="e in events" :key="e.id" :label="`${e.name}（${e.marketDate}）`" :value="e.id" />
        </el-select>
      </div>
      <el-tabs v-model="tab">
        <!-- 1. 受影响摊位 -->
        <el-tab-pane label="① 受影响摊位" name="affected">
          <el-alert type="info" :closable="false" class="mb"
            title="扫描规则：低洼（非硬化路面）已分配摊位视为受积水影响；已发起的调位单同步显示状态。" />
          <el-table :data="affected" size="small">
            <el-table-column label="原摊位" width="100">
              <template #default="{ row }">
                <b>{{ row.stall.stallNo }}</b>
                <el-tag v-if="row.lowLying" size="small" type="danger" style="margin-left:4px">低洼易积水</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="区域" min-width="130">
              <template #default="{ row }">{{ row.stall.zone?.name }}</template>
            </el-table-column>
            <el-table-column label="摊主" width="140">
              <template #default="{ row }">{{ row.vendor.name }}</template>
            </el-table-column>
            <el-table-column label="品类" width="100">
              <template #default="{ row }">{{ categories[row.category] }}</template>
            </el-table-column>
            <el-table-column label="用电" width="60">
              <template #default="{ row }">{{ row.needsPower ? '需要' : '否' }}</template>
            </el-table-column>
            <el-table-column label="调位单状态" width="140">
              <template #default="{ row }">
                <el-tag v-if="row.rainStatus" size="small" :type="(RAIN_STATUS[row.rainStatus] || {}).type">
                  {{ (RAIN_STATUS[row.rainStatus] || {}).text }}
                </el-tag>
                <span v-else class="text-muted">未发起</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="160">
              <template #default="{ row }">
                <el-button v-if="row.active" type="primary" size="small" @click="openPropose(row)">安排调位</el-button>
                <el-button v-if="row.rainId" size="small" @click="openDetail(row.rainId)">查看单据</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!affected.length" description="当前集市没有受影响摊位" :image-size="70" />
        </el-tab-pane>

        <!-- 2. 调位单 -->
        <el-tab-pane label="② 调位单" name="orders">
          <el-table :data="orders" size="small">
            <el-table-column label="单号" width="70" prop="id" />
            <el-table-column label="摊主" width="120">
              <template #default="{ row }">{{ row.vendor?.name }}</template>
            </el-table-column>
            <el-table-column label="原摊位（积水）" width="130">
              <template #default="{ row }">
                {{ row.oldStallNo }}
                <el-image v-for="(u, i) in photos(row.oldStallPhotos)" :key="i" :src="u"
                  :preview-src-list="photos(row.oldStallPhotos)" :initial-index="i"
                  class="mini-img" fit="cover" preview-teleported />
              </template>
            </el-table-column>
            <el-table-column label="新摊位（硬化）" width="130">
              <template #default="{ row }">
                {{ row.newStallNo }}
                <el-image v-for="(u, i) in photos(row.newStallPhotos)" :key="i" :src="u"
                  :preview-src-list="photos(row.newStallPhotos)" :initial-index="i"
                  class="mini-img" fit="cover" preview-teleported />
              </template>
            </el-table-column>
            <el-table-column label="收费差额" width="110">
              <template #default="{ row }">
                <span :style="{ color: row.feeDelta > 0 ? '#e6a23c' : (row.feeDelta < 0 ? '#67c23a' : '#909399'), fontWeight: 600 }">
                  {{ row.feeDelta > 0 ? '补收 +' : (row.feeDelta < 0 ? '应退 ' : '无差额 ') }}¥{{ Math.abs(row.feeDelta) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="130">
              <template #default="{ row }">
                <el-tag size="small" :type="(RAIN_STATUS[row.status] || {}).type">{{ (RAIN_STATUS[row.status] || {}).text }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="170">
              <template #default="{ row }">
                <el-button size="small" @click="openDetail(row.id)">详情/归档</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!orders.length" description="暂无雨天调位单" :image-size="70" />
        </el-tab-pane>

        <!-- 3. 财务对账 -->
        <el-tab-pane label="③ 财务对账" name="finance">
          <el-alert type="success" :closable="false" class="mb"
            title="摊主确认调位后，此处向财务展示：新旧摊位照片、摊主确认信息、收费差额与账单处理状态。" />
          <el-table :data="financeRows" size="small">
            <el-table-column label="单号" width="70">
              <template #default="{ row }">{{ row.relocation.id }}</template>
            </el-table-column>
            <el-table-column label="摊主" width="110">
              <template #default="{ row }">{{ row.relocation.vendor?.name }}</template>
            </el-table-column>
            <el-table-column label="摊位变更" width="150">
              <template #default="{ row }">
                {{ row.relocation.oldStallNo }} → <b>{{ row.relocation.newStallNo }}</b>
              </template>
            </el-table-column>
            <el-table-column label="原摊位照片" width="120">
              <template #default="{ row }">
                <el-image v-for="(u, i) in photos(row.relocation.oldStallPhotos)" :key="i" :src="u"
                  :preview-src-list="photos(row.relocation.oldStallPhotos)" :initial-index="i"
                  class="mini-img" fit="cover" preview-teleported />
                <span v-if="!photos(row.relocation.oldStallPhotos).length" class="text-muted">-</span>
              </template>
            </el-table-column>
            <el-table-column label="新摊位照片" width="120">
              <template #default="{ row }">
                <el-image v-for="(u, i) in photos(row.relocation.newStallPhotos)" :key="i" :src="u"
                  :preview-src-list="photos(row.relocation.newStallPhotos)" :initial-index="i"
                  class="mini-img" fit="cover" preview-teleported />
                <span v-if="!photos(row.relocation.newStallPhotos).length" class="text-muted">待归档上传</span>
              </template>
            </el-table-column>
            <el-table-column label="摊主确认" min-width="170">
              <template #default="{ row }">
                <el-tag size="small" type="success">已同意</el-tag>
                <div class="text-muted" style="font-size:12px">{{ fmtTime(row.relocation.vendorRespondedAt) }}</div>
                <div v-if="row.relocation.vendorNote" style="font-size:12px">备注：{{ row.relocation.vendorNote }}</div>
              </template>
            </el-table-column>
            <el-table-column label="收费差额" width="120">
              <template #default="{ row }">
                <div>原 ¥{{ row.relocation.oldFee }} → 新 ¥{{ row.relocation.newFee }}</div>
                <span :style="{ color: row.relocation.feeDelta > 0 ? '#e6a23c' : '#67c23a', fontWeight: 600 }">
                  {{ row.relocation.feeDelta > 0 ? '补收 +' : '应退 ' }}¥{{ Math.abs(row.relocation.feeDelta) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="账单状态" width="130">
              <template #default="{ row }">
                <span v-if="!row.deltaPayment" class="text-muted">无差额</span>
                <template v-else>
                  <el-tag size="small" :type="(PAY_STATUS[row.deltaPayment.status] || {}).type">
                    {{ PAY_ITEM_TYPES[row.deltaPayment.itemType] }}：{{ (PAY_STATUS[row.deltaPayment.status] || {}).text }}
                  </el-tag>
                  <el-button v-if="row.deltaPayment.itemType === 'FEE_REFUND' && row.deltaPayment.status === 'UNPAID'"
                    type="warning" size="small" style="margin-top:4px" @click="doRefund(row.deltaPayment)">
                    登记已退款
                  </el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!financeRows.length" description="暂无摊主已确认的调位单" :image-size="70" />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 发起调位对话框 -->
    <el-dialog v-model="proposeVisible" title="发起雨天调位（选择硬化路面位置）" width="900px">
      <div v-if="affectedRow">
        <el-descriptions :column="4" border size="small" class="mb">
          <el-descriptions-item label="受影响摊位">{{ affectedRow.stall.stallNo }}（{{ affectedRow.stall.zone?.name }}）</el-descriptions-item>
          <el-descriptions-item label="摊主">{{ affectedRow.vendor.name }}</el-descriptions-item>
          <el-descriptions-item label="品类">{{ categories[affectedRow.category] }}</el-descriptions-item>
          <el-descriptions-item label="用电">{{ affectedRow.needsPower ? '需要电源' : '否' }}</el-descriptions-item>
        </el-descriptions>

        <div class="card-title">可替代位置（硬化路面 · 已过滤品类/电源/车长约束与占用）</div>
        <el-table :data="candidateList" size="small" max-height="300" highlight-current-row
          @current-change="onPickCandidate">
          <el-table-column width="50">
            <template #default="{ row }">
              <el-radio v-model="form.newStallId" :value="row.stall.id">&nbsp;</el-radio>
            </template>
          </el-table-column>
          <el-table-column label="摊位" width="100">
            <template #default="{ row }">
              <b>{{ row.stall.stallNo }}</b>
              <el-tag v-if="row.reservedEmergency" size="small" type="warning" style="margin-left:2px">应急</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="区域" min-width="150">
            <template #default="{ row }">{{ row.stall.zone?.name }}{{ row.stall.hasPower ? ' · 有电' : '' }}</template>
          </el-table-column>
          <el-table-column label="距消防通道" width="130">
            <template #default="{ row }">
              <el-tag size="small" :type="row.fireLaneWarning ? 'danger' : 'success'">
                {{ row.fireLaneDistance }} 米{{ row.fireLaneWarning ? '（偏近）' : '' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="邻摊影响" min-width="180">
            <template #default="{ row }">
              <span v-if="!row.neighbors.length" class="text-muted">周边 5 米内无摊</span>
              <el-tooltip v-for="n in row.neighbors.slice(0, 2)" :key="n.stallNo" placement="top">
                <el-tag size="small" effect="plain" style="margin:2px">{{ n.stallNo }}·{{ n.vendorName }}（{{ n.distanceMeters }}m）</el-tag>
                <template #content>
                  <div v-for="nn in row.neighbors" :key="nn.stallNo">{{ nn.stallNo }} {{ nn.vendorName }}（{{ nn.category }}，{{ nn.distanceMeters }}米）</div>
                </template>
              </el-tooltip>
              <el-tag v-if="row.neighbors.length > 2" size="small" type="info">+{{ row.neighbors.length - 2 }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="收费差额" width="110">
            <template #default="{ row }">
              <span :style="{ color: row.feeDelta > 0 ? '#e6a23c' : (row.feeDelta < 0 ? '#67c23a' : '#909399'), fontWeight: 600 }">
                {{ row.feeDelta > 0 ? '补收 +' : (row.feeDelta < 0 ? '应退 ' : '') }}¥{{ Math.abs(row.feeDelta) }}
              </span>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="candidateList && !candidateList.length" description="没有兼容的硬化路面空摊" :image-size="60" />

        <el-form label-width="110px" class="mt">
          <el-form-item label="积水量深(cm)">
            <el-input-number v-model="form.floodDepthCm" :min="0" :max="100" />
          </el-form-item>
          <el-form-item label="原摊位积水照片" required>
            <ImageUpload v-model="form.oldStallPhotos" />
          </el-form-item>
          <el-form-item label="调位原因" required>
            <el-input v-model="form.reason" type="textarea" :rows="2"
              placeholder="必填，向摊主公开，如：赶集日清晨暴雨，D 区低洼摊位积水约 15cm，无法摆卖" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="proposeVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitPropose">发起并通知摊主确认</el-button>
      </template>
    </el-dialog>

    <!-- 详情/归档对话框 -->
    <el-dialog v-model="detailVisible" title="雨天调位单详情" width="760px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="单号">#{{ detail.id }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="(RAIN_STATUS[detail.status] || {}).type">{{ (RAIN_STATUS[detail.status] || {}).text }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="摊主">{{ detail.vendor?.name }}（{{ detail.vendor?.phone }}）</el-descriptions-item>
          <el-descriptions-item label="集市">{{ detail.event?.name }}</el-descriptions-item>
          <el-descriptions-item label="原摊位">{{ detail.oldStallNo }}（{{ detail.oldStall?.zone?.name }}，低洼积水{{ detail.floodDepthCm ? '约 ' + detail.floodDepthCm + 'cm' : '' }}）</el-descriptions-item>
          <el-descriptions-item label="新摊位">{{ detail.newStallNo }}（{{ detail.newStall?.zone?.name }}，硬化路面）</el-descriptions-item>
          <el-descriptions-item label="新摊距消防通道">{{ detail.fireLaneDistance }} 米</el-descriptions-item>
          <el-descriptions-item label="收费差额">
            原 ¥{{ detail.oldFee }} → 新 ¥{{ detail.newFee }}，
            <b :style="{ color: detail.feeDelta > 0 ? '#e6a23c' : '#67c23a' }">
              {{ detail.feeDelta > 0 ? '补收 +' : (detail.feeDelta < 0 ? '应退 ' : '无差额 ') }}¥{{ Math.abs(detail.feeDelta) }}
            </b>
          </el-descriptions-item>
          <el-descriptions-item label="调位原因" :span="2">{{ detail.reason }}</el-descriptions-item>
          <el-descriptions-item label="摊主答复时间">{{ fmtTime(detail.vendorRespondedAt) }}</el-descriptions-item>
          <el-descriptions-item label="摊主备注">{{ detail.vendorNote || '-' }}</el-descriptions-item>
        </el-descriptions>

        <el-row :gutter="12" class="mt">
          <el-col :span="12">
            <div class="card-title">原摊位（积水）照片</div>
            <el-image v-for="(u, i) in photos(detail.oldStallPhotos)" :key="i" :src="u"
              :preview-src-list="photos(detail.oldStallPhotos)" :initial-index="i"
              class="thumb-img" fit="cover" preview-teleported />
          </el-col>
          <el-col :span="12">
            <div class="card-title">新摊位（硬化路面）照片</div>
            <template v-if="detail.status === 'CONFIRMED'">
              <ImageUpload v-model="completePhotos" />
            </template>
            <el-image v-for="(u, i) in photos(detail.newStallPhotos)" :key="i" :src="u"
              :preview-src-list="photos(detail.newStallPhotos)" :initial-index="i"
              class="thumb-img" fit="cover" preview-teleported />
          </el-col>
        </el-row>

        <div class="card-title mt">邻摊影响评估（发起时快照）</div>
        <el-table :data="neighborList" size="small">
          <el-table-column prop="stallNo" label="相邻摊位" width="100" />
          <el-table-column prop="vendorName" label="摊主" width="120" />
          <el-table-column prop="category" label="品类" />
          <el-table-column label="间距" width="100">
            <template #default="{ row }">{{ row.distanceMeters }} 米</template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!neighborList.length" description="周边 5 米内无其他摊位" :image-size="50" />
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button v-if="detail && detail.status === 'CONFIRMED'" type="primary" :loading="saving" @click="doComplete">
          上传新摊照片并归档（推送财务）
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api'
import { CATEGORIES, RAIN_STATUS, PAY_STATUS, PAY_ITEM_TYPES, fmtTime } from '../../utils/dict'
import ImageUpload from '../../components/ImageUpload.vue'

const categories = CATEGORIES
const events = ref([])
const eventId = ref(null)
const tab = ref('affected')
const affected = ref([])
const orders = ref([])
const financeRows = ref([])
const proposeVisible = ref(false)
const detailVisible = ref(false)
const saving = ref(false)
const affectedRow = ref(null)
const candidateList = ref([])
const detail = ref(null)
const completePhotos = ref('')
const form = ref({ resultId: null, newStallId: null, reason: '', floodDepthCm: 15, oldStallPhotos: '' })

function photos(s) { return s ? s.split(',').filter(Boolean) : [] }
function onPickCandidate(row) { if (row) form.value.newStallId = row.stall.id }

async function reload() {
  if (!eventId.value) { affected.value = []; orders.value = []; financeRows.value = []; return }
  const [a, o, f] = await Promise.all([
    api.get('/admin/rain-relocations/affected', { params: { eventId: eventId.value } }),
    api.get('/admin/rain-relocations', { params: { eventId: eventId.value } }),
    api.get('/admin/rain-relocations/finance', { params: { eventId: eventId.value } })
  ])
  affected.value = a; orders.value = o; financeRows.value = f
}

async function openPropose(row) {
  affectedRow.value = row
  form.value = { resultId: row.resultId, newStallId: null, reason: '赶集日清晨暴雨，' + row.stall.stallNo + ' 所在低洼区域积水，蔬菜等货物无法在原位摆卖，拟调至硬化路面。', floodDepthCm: 15, oldStallPhotos: '' }
  candidateList.value = []
  proposeVisible.value = true
  candidateList.value = await api.get('/admin/rain-relocations/candidates', { params: { resultId: row.resultId } })
}

async function submitPropose() {
  if (!form.value.newStallId) return ElMessage.warning('请选择一个硬化路面替代摊位')
  if (!form.value.oldStallPhotos) return ElMessage.warning('请上传原摊位积水照片')
  if (!form.value.reason) return ElMessage.warning('请填写调位原因')
  saving.value = true
  try {
    await api.post('/admin/rain-relocations', { ...form.value, eventId: eventId.value })
    ElMessage.success('调位单已发起，等待摊主确认')
    proposeVisible.value = false
    tab.value = 'orders'
    await reload()
  } finally { saving.value = false }
}

const neighborList = ref([])
async function openDetail(id) {
  const list = await api.get('/admin/rain-relocations', { params: { eventId: eventId.value } })
  detail.value = list.find(x => x.id === id)
  completePhotos.value = detail.value.newStallPhotos || ''
  try { neighborList.value = JSON.parse(detail.value.neighborImpact || '[]') } catch { neighborList.value = [] }
  detailVisible.value = true
}

async function doComplete() {
  if (!completePhotos.value) return ElMessage.warning('请先上传新摊位照片')
  saving.value = true
  try {
    await api.post(`/admin/rain-relocations/${detail.value.id}/complete`, { newStallPhotos: completePhotos.value })
    ElMessage.success('已归档并推送财务对账')
    detailVisible.value = false
    tab.value = 'finance'
    await reload()
  } finally { saving.value = false }
}

async function doRefund(p) {
  await api.post(`/admin/rain-relocations/delta-payments/${p.id}/refund`)
  ElMessage.success('已登记退款')
  await reload()
  if (detailVisible.value) await openDetail(detail.value.id)
}

onMounted(async () => {
  events.value = await api.get('/admin/events')
  if (events.value.length) { eventId.value = events.value[0].id; await reload() }
})
</script>

<style scoped>
.mini-img { width: 30px; height: 30px; border-radius: 3px; margin: 2px 0 0 4px; vertical-align: middle; }
.thumb-img { width: 110px; height: 80px; border-radius: 4px; margin: 6px 6px 0 0; }
</style>
