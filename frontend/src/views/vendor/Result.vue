<template>
  <div class="page">
    <el-alert v-if="pendingRelocations" type="warning" :closable="false" class="mb"
      :title="`您有 ${pendingRelocations} 条雨天调位通知待确认，请前往「雨天调位通知」处理`">
      <el-button type="warning" size="small" style="margin-left:12px" @click="$router.push('/relocations')">
        去处理
      </el-button>
    </el-alert>
    <el-empty v-if="!results.length" description="暂无抽签结果，请先报名并等待集市抽签" />
    <div v-for="r in results" :key="r.id" class="mb">
      <el-card>
        <template #header>
          <div style="display:flex;justify-content:space-between;align-items:center">
            <span><b>{{ r.application?.event?.name }}</b>（{{ r.application?.event?.marketDate }}）</span>
            <el-tag :type="r.status === 'RELOCATED' ? 'warning' : 'success'">
              {{ r.status === 'RELOCATED' ? '已调位' : '已分配' }}
            </el-tag>
          </div>
        </template>
        <el-row :gutter="20">
          <el-col :span="14">
            <el-descriptions :column="2" border>
              <el-descriptions-item label="摊位号">
                <el-tag type="success" size="large">{{ r.stallNo }}</el-tag>
                <span class="text-muted">（{{ r.stall?.zone?.name }}）</span>
              </el-descriptions-item>
              <el-descriptions-item label="入场时间">{{ r.entryTime }}</el-descriptions-item>
              <el-descriptions-item label="经营品类">{{ categories[r.application?.category] }}</el-descriptions-item>
              <el-descriptions-item label="保证金">¥{{ r.deposit }}</el-descriptions-item>
              <el-descriptions-item label="电源" v-if="r.stall">{{ r.stall.hasPower ? '有' : '无' }}</el-descriptions-item>
              <el-descriptions-item label="临消防通道" v-if="r.stall">{{ r.stall.nearFireLane ? '是（禁止堆物）' : '否' }}</el-descriptions-item>
            </el-descriptions>
            <div class="mt">
              <div class="card-title">禁止事项</div>
              <el-tag v-for="(p, i) in parseJson(r.prohibitedItems)" :key="i" type="danger"
                effect="plain" style="margin: 0 6px 6px 0">{{ p }}</el-tag>
            </div>
          </el-col>
          <el-col :span="10" style="text-align:center">
            <div class="card-title">入场核验码（出示给工作人员）</div>
            <img v-if="qrMap[r.id]" :src="qrMap[r.id]" style="width:180px;height:180px" />
            <div class="text-muted" style="word-break:break-all">{{ r.qrToken }}</div>
          </el-col>
        </el-row>

        <el-divider />
        <div class="card-title">收费项目</div>
        <el-table :data="paymentsOf(r.id)" size="small">
          <el-table-column prop="itemName" label="项目" />
          <el-table-column label="金额" width="120">
            <template #default="{ row }">¥{{ row.amount }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="payType(row.status)">{{ payText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" show-overflow-tooltip />
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button v-if="row.status === 'UNPAID'" type="primary" size="small" @click="pay(row)">缴纳</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import QRCode from 'qrcode'
import api from '../../api'
import { CATEGORIES } from '../../utils/dict'

const categories = CATEGORIES
const results = ref([])
const payments = ref([])
const qrMap = ref({})
const pendingRelocations = ref(0)

function parseJson(s) { try { return JSON.parse(s) } catch { return [] } }
function payText(s) {
  return { UNPAID: '未缴', PAID: '已缴', WAIVED: '已减免', DEDUCTED: '已扣罚', REFUNDED: '已退还' }[s] || s
}
function payType(s) {
  return { UNPAID: 'danger', PAID: 'success', WAIVED: 'info', DEDUCTED: 'warning', REFUNDED: 'info' }[s] || 'info'
}
function paymentsOf(resultId) { return payments.value.filter(p => p.result?.id === resultId) }

async function pay(p) {
  await ElMessageBox.confirm(`确认缴纳「${p.itemName}」¥${p.amount}？`, '缴费确认')
  await api.post(`/vendor/payments/${p.id}/pay`)
  ElMessage.success('缴费成功')
  payments.value = await api.get('/vendor/payments')
}

onMounted(async () => {
  const [res, pays, relos] = await Promise.all([
    api.get('/vendor/results'), api.get('/vendor/payments'), api.get('/vendor/rain-relocations')
  ])
  results.value = res
  payments.value = pays
  pendingRelocations.value = relos.filter(t => t.status === 'PENDING').length
  for (const r of res) {
    qrMap.value[r.id] = await QRCode.toDataURL(r.qrToken, { width: 180, margin: 1 })
  }
})
</script>
