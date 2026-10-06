<template>
  <div class="page">
    <el-card>
      <div style="display:flex;justify-content:space-between;align-items:center" class="mb">
        <div class="card-title" style="margin:0">雨天临时调位（暴雨积水 · 低洼摊位 → 硬化路面）</div>
        <el-select v-model="eventId" placeholder="选择集市" style="width: 280px" @change="reloadAll">
          <el-option v-for="e in events" :key="e.id" :label="`${e.name}（${e.marketDate}）`" :value="e.id" />
        </el-select>
      </div>

      <el-tabs v-model="tab">
        <!-- ============ 发起调位 ============ -->
        <el-tab-pane label="发起调位" name="create">
          <el-alert type="warning" :closable="false" class="mb"
            title="以下为本集市已分配的低洼易积水摊位；为每个摊位选择硬化路面替代位置后发起调位，待摊主确认。" />
          <el-empty v-if="!affected.length" description="该集市暂无低洼积水受影响摊位（请先完成抽签分配）" />
          <el-table v-else :data="affected" size="small">
            <el-table-column label="受影响摊位" width="120">
              <template #default="{ row }">
                <el-tag type="danger" size="large">{{ row.result.stallNo }}</el-tag>
                <el-tag v-if="row.result.stall?.lowLying" type="warning" size="small" effect="plain" style="margin-left:4px">低洼</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="区域" min-width="130">
              <template #default="{ row }">{{ row.result.stall?.zone?.name }}</template>
            </el-table-column>
            <el-table-column label="摊主" width="130">
              <template #default="{ row }">{{ row.result.application?.vendor?.name }}</template>
            </el-table-column>
            <el-table-column label="品类" width="110">
              <template #default="{ row }">{{ categories[row.result.application?.category] }}</template>
            </el-table-column>
            <el-table-column label="可替代位置" width="110">
              <template #default="{ row }">
                <el-tag :type="row.alternatives.length ? 'success' : 'info'" size="small">
                  {{ row.alternatives.length }} 个硬化路面位
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <el-tag v-if="row.hasPendingTicket" type="warning" size="small">已有待确认调位单</el-tag>
                <span v-else class="text-muted">可发起</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="140">
              <template #default="{ row }">
                <el-button type="primary" size="small" :disabled="row.hasPendingTicket || !row.alternatives.length"
                  @click="openCreate(row)">选择替代位置</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ============ 调位单管理 ============ -->
        <el-tab-pane label="调位单管理" name="tickets">
          <el-table :data="tickets" size="small">
            <el-table-column prop="id" label="单号" width="70" />
            <el-table-column label="摊主" width="130">
              <template #default="{ row }">{{ row.vendor?.name }}</template>
            </el-table-column>
            <el-table-column label="调位" min-width="150">
              <template #default="{ row }">
                <el-tag type="danger" size="small">{{ row.oldStallNo }}</el-tag>
                <span style="margin:0 4px">→</span>
                <el-tag type="success" size="small">{{ row.newStallNo }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="收费差额" width="110">
              <template #default="{ row }">
                <span :style="{ color: row.feeDiff > 0 ? '#f56c6c' : row.feeDiff < 0 ? '#67c23a' : '#909399' }">
                  {{ diffText(row.feeDiff) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="消防距离" width="90">
              <template #default="{ row }">{{ row.fireLaneDistance != null ? row.fireLaneDistance + '米' : '-' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="140">
              <template #default="{ row }">
                <el-tag size="small" :type="statusOf(row.status).type">{{ statusOf(row.status).text }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="vendorNote" label="摊主说明" min-width="140" show-overflow-tooltip />
            <el-table-column prop="reason" label="调位原因" min-width="160" show-overflow-tooltip />
            <el-table-column label="时间" width="150">
              <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <template v-if="row.status === 'REJECTED'">
                  <el-button type="danger" size="small" @click="openResolve(row, 'ABANDONED')">放弃经营</el-button>
                  <el-button type="warning" size="small" @click="openResolve(row, 'KEEP_ORIGINAL')">保留原位</el-button>
                </template>
                <span v-else-if="row.status === 'ABANDONED' || row.status === 'KEEP_ORIGINAL'" class="text-muted">
                  {{ row.resolveNote }}
                </span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ============ 财务核对 ============ -->
        <el-tab-pane label="财务核对" name="finance">
          <el-alert type="info" :closable="false" class="mb"
            title="已完成调位的新旧摊位照片、摊主确认记录与收费差额，供财务核对收退。" />
          <el-empty v-if="!financeRows.length" description="暂无已确认完成的调位记录" />
          <el-table v-else :data="financeRows" size="small">
            <el-table-column label="单号" width="70">
              <template #default="{ row }">#{{ row.ticket.id }}</template>
            </el-table-column>
            <el-table-column label="摊主" width="120">
              <template #default="{ row }">{{ row.ticket.vendor?.name }}</template>
            </el-table-column>
            <el-table-column label="调位" min-width="130">
              <template #default="{ row }">{{ row.ticket.oldStallNo }} → {{ row.ticket.newStallNo }}</template>
            </el-table-column>
            <el-table-column label="新旧摊位照片" min-width="220">
              <template #default="{ row }">
                <div style="display:flex;gap:8px;align-items:center">
                  <div v-for="(u, i) in splitPhotos(row.ticket.oldStallPhoto)" :key="'o' + i">
                    <el-image :src="u" :preview-src-list="splitPhotos(row.ticket.oldStallPhoto)" :initial-index="i"
                      class="fin-img" fit="cover" preview-teleported />
                  </div>
                  <span>→</span>
                  <div v-for="(u, i) in splitPhotos(row.ticket.newStallPhoto)" :key="'n' + i">
                    <el-image :src="u" :preview-src-list="splitPhotos(row.ticket.newStallPhoto)" :initial-index="i"
                      class="fin-img" fit="cover" preview-teleported />
                  </div>
                  <span v-if="!splitPhotos(row.ticket.oldStallPhoto).length && !splitPhotos(row.ticket.newStallPhoto).length"
                    class="text-muted">未附照片</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="摊主确认" min-width="170">
              <template #default="{ row }">
                <div>{{ fmtTime(row.ticket.confirmedAt) }}</div>
                <div class="text-muted">{{ row.ticket.vendorNote || '（无备注）' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="收费差额" width="120">
              <template #default="{ row }">
                <div :style="{ color: row.ticket.feeDiff > 0 ? '#f56c6c' : row.ticket.feeDiff < 0 ? '#67c23a' : '#909399' }">
                  {{ diffText(row.ticket.feeDiff) }}
                </div>
                <div class="text-muted" style="font-size:12px">
                  ¥{{ row.ticket.oldFee }} → ¥{{ row.ticket.newFee }}
                </div>
              </template>
            </el-table-column>
            <el-table-column label="差额单状态" width="110">
              <template #default="{ row }">
                <template v-if="row.diffPayment">
                  <el-tag size="small" :type="payType(row.diffPayment.status)">{{ payText(row.diffPayment.status) }}</el-tag>
                </template>
                <span v-else class="text-muted">无差额</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120">
              <template #default="{ row }">
                <el-button v-if="row.diffPayment && row.diffPayment.amount < 0 && row.diffPayment.status === 'UNPAID'"
                  type="success" size="small" @click="settle(row)">标记已退还</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 选择替代位置 + 发起 -->
    <el-dialog v-model="createVisible" title="选择替代位置并发起调位" width="780px">
      <template v-if="current">
        <el-descriptions :column="3" border size="small" class="mb">
          <el-descriptions-item label="受影响摊位">
            <el-tag type="danger">{{ current.result.stallNo }}</el-tag>（{{ current.result.stall?.zone?.name }}）
          </el-descriptions-item>
          <el-descriptions-item label="摊主">{{ current.result.application?.vendor?.name }}</el-descriptions-item>
          <el-descriptions-item label="品类">{{ categories[current.result.application?.category] }}</el-descriptions-item>
        </el-descriptions>
        <div class="card-title">可替代位置（硬化路面 · 空闲 · 品类兼容）</div>
        <el-table :data="current.alternatives" size="small" highlight-current-row
          @current-change="r => selectedAlt = r" max-height="260" class="mb">
          <el-table-column width="45">
            <template #default="{ row }">
              <el-radio :model-value="selectedAlt" :label="row" @change="selectedAlt = row"><span /></el-radio>
            </template>
          </el-table-column>
          <el-table-column label="摊位号" width="90">
            <template #default="{ row }"><el-tag type="success">{{ row.stall.stallNo }}</el-tag></template>
          </el-table-column>
          <el-table-column label="区域" min-width="130">
            <template #default="{ row }">{{ row.stall.zone?.name }}</template>
          </el-table-column>
          <el-table-column label="路面" width="80">
            <template #default="{ row }">
              <el-tag size="small" :type="row.stall.hardened ? 'success' : 'warning'">
                {{ row.stall.hardened ? '硬化' : '未硬化' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="电源" width="60">
            <template #default="{ row }">{{ row.stall.hasPower ? '有' : '无' }}</template>
          </el-table-column>
          <el-table-column label="消防通道距离" width="110">
            <template #default="{ row }">
              <span :style="{ color: row.stall.nearFireLane ? '#f56c6c' : 'inherit' }">
                {{ row.stall.fireLaneDistance != null ? row.stall.fireLaneDistance + '米' : '未知' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="邻摊影响" min-width="240">
            <template #default="{ row }"><span style="font-size:12px">{{ row.neighborImpact }}</span></template>
          </el-table-column>
        </el-table>

        <el-form :model="createForm" label-width="110px">
          <el-form-item label="收费差额">
            <span v-if="selectedAlt">
              原摊位费 ¥{{ current.result.stall?.zone?.baseFee }} → 新摊位费 ¥{{ selectedAlt.stall.zone?.baseFee }}，
              <b :style="{ color: feeDiffOf(selectedAlt) > 0 ? '#f56c6c' : feeDiffOf(selectedAlt) < 0 ? '#67c23a' : '#909399' }">
                {{ diffText(feeDiffOf(selectedAlt)) }}
              </b>
            </span>
            <span v-else class="text-muted">请先选择替代位置</span>
          </el-form-item>
          <el-form-item label="积水情况/原因" required>
            <el-input v-model="createForm.reason" type="textarea" :rows="2"
              placeholder="如：清晨暴雨，D区低洼摊位积水约15cm，调至硬化路面" />
          </el-form-item>
          <el-form-item label="旧摊位照片">
            <ImageUpload v-model="createForm.oldStallPhoto" />
          </el-form-item>
          <el-form-item label="新摊位照片">
            <ImageUpload v-model="createForm.newStallPhoto" />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!selectedAlt" @click="submitCreate">
          发起调位（待摊主确认）
        </el-button>
      </template>
    </el-dialog>

    <!-- 处理拒绝单 -->
    <el-dialog v-model="resolveVisible" :title="resolveAction === 'ABANDONED' ? '标记放弃经营' : '保留原位（风险自担）'" width="480px">
      <el-alert :type="resolveAction === 'ABANDONED' ? 'error' : 'warning'" :closable="false" class="mb"
        :title="resolveAction === 'ABANDONED'
          ? '放弃经营：取消该摊主本次分配，未缴账单作废，摊位释放。'
          : '保留原位：摊主留在原低洼摊位继续经营，积水风险由摊主自行承担。'" />
      <el-input v-model="resolveNote" type="textarea" :rows="3" placeholder="处理说明（必填，向摊主公开留痕）" />
      <template #footer>
        <el-button @click="resolveVisible = false">取消</el-button>
        <el-button :type="resolveAction === 'ABANDONED' ? 'danger' : 'warning'" :loading="saving" @click="submitResolve">
          确认
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api'
import ImageUpload from '../../components/ImageUpload.vue'
import { CATEGORIES, RELOCATION_STATUS, fmtTime } from '../../utils/dict'

const categories = CATEGORIES
const events = ref([])
const eventId = ref(null)
const tab = ref('create')
const affected = ref([])
const tickets = ref([])
const financeRows = ref([])
const createVisible = ref(false)
const current = ref(null)
const selectedAlt = ref(null)
const createForm = ref({ reason: '', oldStallPhoto: '', newStallPhoto: '' })
const saving = ref(false)
const resolveVisible = ref(false)
const resolveAction = ref('ABANDONED')
const resolveNote = ref('')
const resolveTicket = ref(null)

function statusOf(s) { return RELOCATION_STATUS[s] || { text: s, type: 'info' } }
function diffText(d) {
  if (d > 0) return `补缴 ¥${d}`
  if (d < 0) return `退还 ¥${Math.abs(d)}`
  return '无差额'
}
function payText(s) {
  return { UNPAID: '未结清', PAID: '已缴', WAIVED: '已减免', DEDUCTED: '已扣罚', REFUNDED: '已退还' }[s] || s
}
function payType(s) {
  return { UNPAID: 'danger', PAID: 'success', WAIVED: 'info', DEDUCTED: 'warning', REFUNDED: 'success' }[s] || 'info'
}
function splitPhotos(s) { return s ? s.split(',').filter(Boolean) : [] }
function feeDiffOf(alt) {
  const oldFee = current.value?.result?.stall?.zone?.baseFee || 0
  return (alt.stall.zone?.baseFee || 0) - oldFee
}

async function loadAffected() {
  if (!eventId.value) { affected.value = []; return }
  affected.value = await api.get(`/admin/rain-relocations/events/${eventId.value}/affected`)
}
async function loadTickets() {
  tickets.value = await api.get('/admin/rain-relocations', { params: eventId.value ? { eventId: eventId.value } : {} })
}
async function loadFinance() {
  if (!eventId.value) { financeRows.value = []; return }
  financeRows.value = await api.get('/admin/rain-relocations/finance', { params: { eventId: eventId.value } })
}
async function reloadAll() { await Promise.all([loadAffected(), loadTickets(), loadFinance()]) }

function openCreate(row) {
  current.value = row
  selectedAlt.value = null
  createForm.value = {
    reason: `清晨暴雨，${row.result.stall?.zone?.name}低洼摊位积水，临时调至硬化路面`,
    oldStallPhoto: '', newStallPhoto: ''
  }
  createVisible.value = true
}

async function submitCreate() {
  if (!createForm.value.reason) return ElMessage.warning('请填写积水情况/原因')
  saving.value = true
  try {
    await api.post('/admin/rain-relocations', {
      resultId: current.value.result.id,
      newStallId: selectedAlt.value.stall.id,
      reason: createForm.value.reason,
      oldStallPhoto: createForm.value.oldStallPhoto,
      newStallPhoto: createForm.value.newStallPhoto
    })
    ElMessage.success('调位单已发起，等待摊主确认')
    createVisible.value = false
    await reloadAll()
  } finally { saving.value = false }
}

function openResolve(row, action) {
  resolveTicket.value = row
  resolveAction.value = action
  resolveNote.value = ''
  resolveVisible.value = true
}

async function submitResolve() {
  if (!resolveNote.value) return ElMessage.warning('请填写处理说明')
  saving.value = true
  try {
    await api.post(`/admin/rain-relocations/${resolveTicket.value.id}/resolve`, {
      action: resolveAction.value, note: resolveNote.value
    })
    ElMessage.success('已处理并留痕')
    resolveVisible.value = false
    await reloadAll()
  } finally { saving.value = false }
}

async function settle(row) {
  await api.post(`/admin/rain-relocations/${row.ticket.id}/settle`)
  ElMessage.success('已标记退还摊主')
  await loadFinance()
}

onMounted(async () => {
  events.value = await api.get('/admin/events')
  if (events.value.length) {
    eventId.value = events.value[0].id
    await reloadAll()
  }
})
</script>

<style scoped>
.fin-img { width: 56px; height: 56px; border-radius: 4px; }
</style>
