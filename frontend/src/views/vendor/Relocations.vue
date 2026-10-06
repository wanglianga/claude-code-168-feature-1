<template>
  <div class="page">
    <el-card>
      <div class="card-title mb">雨天调位通知（暴雨积水临时调位，确认后换发新入场码并核算差额）</div>
      <el-empty v-if="!tickets.length" description="暂无调位通知" />
      <div v-for="t in tickets" :key="t.id" class="mb">
        <el-card shadow="never" style="border:1px solid #ebeef5">
          <template #header>
            <div style="display:flex;justify-content:space-between;align-items:center">
              <span>
                <b>{{ t.event?.name }}</b>（{{ t.event?.marketDate }}）
                <el-tag type="danger" size="small" style="margin-left:8px">{{ t.oldStallNo }}</el-tag>
                <span style="margin:0 4px">→</span>
                <el-tag type="success" size="small">{{ t.newStallNo }}</el-tag>
              </span>
              <el-tag :type="statusOf(t.status).type">{{ statusOf(t.status).text }}</el-tag>
            </div>
          </template>

          <el-row :gutter="20">
            <el-col :span="14">
              <el-descriptions :column="1" border size="small">
                <el-descriptions-item label="积水情况/原因">{{ t.reason }}</el-descriptions-item>
                <el-descriptions-item label="新摊位">
                  {{ t.newStallNo }}（{{ t.newStall?.zone?.name }}）
                  <el-tag v-if="t.newStall?.hardened" type="success" size="small" effect="plain" style="margin-left:6px">硬化路面</el-tag>
                  <el-tag v-if="t.newStall?.hasPower" size="small" effect="plain" style="margin-left:4px">有电源</el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="消防通道距离">
                  {{ t.fireLaneDistance != null ? t.fireLaneDistance + ' 米' : '未知' }}
                  <span v-if="t.newStall?.nearFireLane" class="text-muted">（紧邻消防通道，禁止堆物外延）</span>
                </el-descriptions-item>
                <el-descriptions-item label="邻摊影响">{{ t.neighborImpact || '无' }}</el-descriptions-item>
                <el-descriptions-item label="收费差额">
                  原摊位费 ¥{{ t.oldFee }} → 新摊位费 ¥{{ t.newFee }}，
                  <b :style="{ color: t.feeDiff > 0 ? '#f56c6c' : t.feeDiff < 0 ? '#67c23a' : '#909399' }">
                    {{ diffText(t.feeDiff) }}
                  </b>
                </el-descriptions-item>
                <el-descriptions-item v-if="t.vendorNote" label="我的说明">{{ t.vendorNote }}</el-descriptions-item>
                <el-descriptions-item v-if="t.resolveNote" label="管理处理">{{ t.resolveNote }}</el-descriptions-item>
              </el-descriptions>
            </el-col>
            <el-col :span="10">
              <div class="card-title" style="font-size:13px">新旧摊位照片</div>
              <div style="display:flex;gap:12px;align-items:flex-start">
                <div>
                  <div class="text-muted" style="font-size:12px">旧（积水）</div>
                  <template v-if="splitPhotos(t.oldStallPhoto).length">
                    <el-image v-for="(u, i) in splitPhotos(t.oldStallPhoto)" :key="'o' + i" :src="u"
                      :preview-src-list="splitPhotos(t.oldStallPhoto)" :initial-index="i"
                      class="photo" fit="cover" preview-teleported />
                  </template>
                  <span v-else class="text-muted" style="font-size:12px">未附</span>
                </div>
                <div>
                  <div class="text-muted" style="font-size:12px">新（硬化路面）</div>
                  <template v-if="splitPhotos(t.newStallPhoto).length">
                    <el-image v-for="(u, i) in splitPhotos(t.newStallPhoto)" :key="'n' + i" :src="u"
                      :preview-src-list="splitPhotos(t.newStallPhoto)" :initial-index="i"
                      class="photo" fit="cover" preview-teleported />
                  </template>
                  <span v-else class="text-muted" style="font-size:12px">未附</span>
                </div>
              </div>
            </el-col>
          </el-row>

          <template v-if="t.status === 'PENDING'">
            <el-divider />
            <el-input v-model="notes[t.id]" placeholder="确认/拒绝说明（拒绝必填，将留痕）" size="small"
              style="max-width:420px;margin-right:8px" />
            <el-button type="primary" size="small" :loading="acting" @click="confirm(t)">确认调位</el-button>
            <el-button type="danger" size="small" plain :loading="acting" @click="reject(t)">拒绝调位</el-button>
          </template>
          <el-alert v-if="t.status === 'CONFIRMED'" type="success" :closable="false" style="margin-top:10px"
            title="已确认调位：入场码已换发，请前往「抽签结果与缴费」出示新入场码；差额账单已生成。" />
          <el-alert v-if="t.status === 'KEEP_ORIGINAL'" type="warning" :closable="false" style="margin-top:10px"
            title="已保留原位经营，积水风险由您自行承担，请注意货物与用电安全。" />
          <el-alert v-if="t.status === 'ABANDONED'" type="info" :closable="false" style="margin-top:10px"
            title="已登记放弃本次经营，未缴账单作废。" />
        </el-card>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'
import { RELOCATION_STATUS } from '../../utils/dict'

const tickets = ref([])
const notes = ref({})
const acting = ref(false)

function statusOf(s) { return RELOCATION_STATUS[s] || { text: s, type: 'info' } }
function diffText(d) {
  if (d > 0) return `需补缴 ¥${d}`
  if (d < 0) return `退还您 ¥${Math.abs(d)}`
  return '无差额'
}
function splitPhotos(s) { return s ? s.split(',').filter(Boolean) : [] }

async function load() { tickets.value = await api.get('/vendor/rain-relocations') }

async function confirm(t) {
  await ElMessageBox.confirm(
    `确认从 ${t.oldStallNo} 调至 ${t.newStallNo}？确认后将换发新入场码${t.feeDiff > 0 ? `，并生成补缴差额 ¥${t.feeDiff}` : t.feeDiff < 0 ? `，差额 ¥${Math.abs(t.feeDiff)} 将退还` : ''}。`,
    '确认调位', { type: 'warning' }
  )
  acting.value = true
  try {
    await api.post(`/vendor/rain-relocations/${t.id}/confirm`, { note: notes.value[t.id] || '' })
    ElMessage.success('已确认调位，新入场码已生成')
    await load()
  } finally { acting.value = false }
}

async function reject(t) {
  const note = notes.value[t.id]
  if (!note) return ElMessage.warning('拒绝调位请先在输入框填写说明')
  await ElMessageBox.confirm(
    '拒绝后管理员将标记「放弃经营」或「保留原位风险自担」，确定拒绝调位？',
    '拒绝调位', { type: 'warning' }
  )
  acting.value = true
  try {
    await api.post(`/vendor/rain-relocations/${t.id}/reject`, { note })
    ElMessage.success('已拒绝调位，等待管理员处理')
    await load()
  } finally { acting.value = false }
}

onMounted(load)
</script>

<style scoped>
.photo { width: 72px; height: 72px; border-radius: 4px; margin-right: 6px; }
</style>
