<template>
  <div class="page">
    <el-card>
      <div style="display:flex;justify-content:space-between" class="mb">
        <div class="card-title" style="margin:0">调整与追溯（调位/减免/扣罚/信用/换品类，全部留痕）</div>
        <el-button type="primary" @click="openCreate">发起调整</el-button>
      </div>
      <el-select v-model="eventId" placeholder="全部集市" clearable class="mb" style="width: 260px" @change="load">
        <el-option v-for="e in events" :key="e.id" :label="`${e.name}（${e.marketDate}）`" :value="e.id" />
      </el-select>
      <el-table :data="adjustments" size="small">
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="adjType(row.type).type">{{ adjType(row.type).text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="摊主" width="130">
          <template #default="{ row }">{{ row.vendor?.name }}</template>
        </el-table-column>
        <el-table-column label="变更内容" min-width="220">
          <template #default="{ row }">{{ row.beforeValue }} → {{ row.afterValue }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="180" show-overflow-tooltip />
        <el-table-column label="来源" width="100">
          <template #default="{ row }">
            {{ sourceText(row.source) }}<span v-if="row.sourceRefId" class="text-muted">#{{ row.sourceRefId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作人" width="110">
          <template #default="{ row }">{{ row.operator?.name }}</template>
        </el-table-column>
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="createVisible" title="发起调整（原因与来源必填，向摊主公开）" width="560px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="调整类型" required>
          <el-select v-model="form.type" style="width: 100%">
            <el-option label="临时调位" value="RELOCATE" />
            <el-option label="雨天调位" value="RAIN_RELOCATE" />
            <el-option label="费用减免" value="FEE_WAIVER" />
            <el-option label="保证金扣罚" value="DEPOSIT_PENALTY" />
            <el-option label="信用调整" value="CREDIT_ADJUST" />
            <el-option label="品类变更" value="CATEGORY_CHANGE" />
          </el-select>
        </el-form-item>

        <!-- 调位/换品类：选择分配单 -->
        <template v-if="['RELOCATE', 'RAIN_RELOCATE', 'CATEGORY_CHANGE'].includes(form.type)">
          <el-form-item label="集市" required>
            <el-select v-model="form.eventId" style="width: 100%" @change="loadResults">
              <el-option v-for="e in events" :key="e.id" :label="`${e.name}（${e.marketDate}）`" :value="e.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="摊位分配单" required>
            <el-select v-model="form.resultId" style="width: 100%" filterable>
              <el-option v-for="r in results" :key="r.id"
                :label="`${r.stallNo} - ${r.application?.vendor?.name}（${categories[r.application?.category]}）`"
                :value="r.id" />
            </el-select>
          </el-form-item>
        </template>

        <el-form-item v-if="['RELOCATE', 'RAIN_RELOCATE'].includes(form.type)" label="目标摊位" required>
          <el-select v-model="form.newStallId" style="width: 100%" filterable>
            <el-option v-for="s in freeStalls" :key="s.id"
              :label="`${s.stallNo}（${s.zone?.name}${s.hasPower ? '·有电' : ''}）`" :value="s.id" />
          </el-select>
        </el-form-item>

        <template v-if="form.type === 'CATEGORY_CHANGE'">
          <el-form-item label="新品类" required>
            <el-select v-model="form.newCategory" style="width: 100%">
              <el-option v-for="(n, c) in categories" :key="c" :label="n" :value="c" />
            </el-select>
          </el-form-item>
          <el-form-item label="含熟食"><el-switch v-model="form.sellsCookedFood" /></el-form-item>
          <el-form-item label="含活禽"><el-switch v-model="form.sellsLivePoultry" /></el-form-item>
        </template>

        <!-- 费用/保证金：选择账单 -->
        <template v-if="['FEE_WAIVER', 'DEPOSIT_PENALTY'].includes(form.type)">
          <el-form-item label="集市" required>
            <el-select v-model="form.eventId" style="width: 100%" @change="loadPayments">
              <el-option v-for="e in events" :key="e.id" :label="`${e.name}（${e.marketDate}）`" :value="e.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="账单" required>
            <el-select v-model="form.paymentId" style="width: 100%" filterable>
              <el-option v-for="p in candidatePayments" :key="p.id"
                :label="`${p.vendor?.name} - ${p.itemName} ¥${p.amount}（${payText(p.status)}）`" :value="p.id" />
            </el-select>
          </el-form-item>
        </template>

        <!-- 信用调整 -->
        <template v-if="form.type === 'CREDIT_ADJUST'">
          <el-form-item label="摊主" required>
            <el-select v-model="form.vendorId" style="width: 100%" filterable>
              <el-option v-for="v in vendors" :key="v.id" :label="`${v.name}（当前 ${v.creditScore} 分）`" :value="v.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="变动分值" required>
            <el-input-number v-model="form.creditDelta" :min="-50" :max="20" />
          </el-form-item>
        </template>

        <el-form-item label="原因" required>
          <el-input v-model="form.reason" type="textarea" :rows="2"
            placeholder="必填，向摊主公开，如：暴雨导致B区积水，临时调整至A区" />
        </el-form-item>
        <el-form-item label="来源" required>
          <el-select v-model="form.source" style="width: 100%">
            <el-option label="雨天" value="RAIN" />
            <el-option label="投诉处理" value="COMPLAINT" />
            <el-option label="巡查记录" value="PATROL" />
            <el-option label="争议处理" value="DISPUTE" />
            <el-option label="摊主申请" value="REQUEST" />
            <el-option label="政策减免" value="POLICY" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源单据号">
          <el-input-number v-model="form.sourceRefId" :min="0" placeholder="选填，如投诉ID/巡查ID" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">提交调整</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api'
import { CATEGORIES, fmtTime } from '../../utils/dict'

const categories = CATEGORIES
const events = ref([])
const adjustments = ref([])
const results = ref([])
const stalls = ref([])
const payments = ref([])
const vendors = ref([])
const eventId = ref(null)
const createVisible = ref(false)
const saving = ref(false)
const form = ref({ type: 'RELOCATE', eventId: null, resultId: null, paymentId: null, vendorId: null, newStallId: null, newCategory: '', sellsCookedFood: false, sellsLivePoultry: false, creditDelta: -5, reason: '', source: 'RAIN', sourceRefId: null })

const freeStalls = computed(() => {
  const used = new Set(results.value.map(r => r.stall?.id))
  return stalls.value.filter(s => s.active && !used.has(s.id))
})
const candidatePayments = computed(() => {
  if (form.value.type === 'FEE_WAIVER') return payments.value.filter(p => p.status === 'UNPAID')
  if (form.value.type === 'DEPOSIT_PENALTY') return payments.value.filter(p => p.itemType === 'DEPOSIT' && p.status !== 'DEDUCTED')
  return []
})

function adjType(t) {
  const map = {
    RELOCATE: { text: '临时调位', type: 'warning' }, RAIN_RELOCATE: { text: '雨天调位', type: 'primary' },
    FEE_WAIVER: { text: '费用减免', type: 'success' }, DEPOSIT_PENALTY: { text: '保证金扣罚', type: 'danger' },
    CREDIT_ADJUST: { text: '信用调整', type: 'warning' }, CATEGORY_CHANGE: { text: '品类变更', type: 'info' },
    RAIN_ABANDON: { text: '雨天拒绝-放弃经营', type: 'danger' },
    RAIN_RISK_SELF: { text: '雨天拒绝-风险自担', type: 'danger' }
  }
  return map[t] || { text: t, type: 'info' }
}
function sourceText(s) {
  return { RAIN: '雨天', COMPLAINT: '投诉处理', PATROL: '巡查记录', DISPUTE: '争议处理', REQUEST: '摊主申请', POLICY: '政策减免', OTHER: '其他' }[s] || s
}
function payText(s) {
  return { UNPAID: '未缴', PAID: '已缴', WAIVED: '已减免', DEDUCTED: '已扣罚' }[s] || s
}

async function load() {
  adjustments.value = await api.get('/admin/adjustments', { params: eventId.value ? { eventId: eventId.value } : {} })
}
async function loadResults() {
  if (!form.value.eventId) return
  results.value = await api.get(`/admin/events/${form.value.eventId}/results`)
}
async function loadPayments() {
  if (!form.value.eventId) return
  payments.value = await api.get('/admin/payments', { params: { eventId: form.value.eventId } })
}

async function openCreate() {
  form.value = { type: 'RELOCATE', eventId: eventId.value, resultId: null, paymentId: null, vendorId: null, newStallId: null, newCategory: '', sellsCookedFood: false, sellsLivePoultry: false, creditDelta: -5, reason: '', source: 'RAIN', sourceRefId: null }
  if (form.value.eventId) { await loadResults(); await loadPayments() }
  createVisible.value = true
}

async function submit() {
  if (!form.value.reason) return ElMessage.warning('请填写调整原因')
  saving.value = true
  try {
    await api.post('/admin/adjustments', form.value)
    ElMessage.success('调整已执行并留痕')
    createVisible.value = false
    await load()
  } finally { saving.value = false }
}

onMounted(async () => {
  const [evs, sts, vds] = await Promise.all([
    api.get('/admin/events'), api.get('/admin/stalls'), api.get('/admin/vendors')
  ])
  events.value = evs; stalls.value = sts; vendors.value = vds
  await load()
})
</script>
