<template>
  <div class="page">
    <el-row :gutter="16">
      <el-col :span="10">
        <el-card>
          <div style="display:flex;justify-content:space-between" class="mb">
            <div class="card-title" style="margin:0">摊位区域</div>
            <el-button size="small" type="primary" @click="editZone({})">新增区域</el-button>
          </div>
          <el-table :data="zones" size="small">
            <el-table-column prop="code" label="代码" width="60" />
            <el-table-column prop="name" label="名称" min-width="130" />
            <el-table-column label="属性" min-width="150">
              <template #default="{ row }">
                <el-tag v-if="row.allowsCookedFood" size="small" type="warning" class="mr">熟食冷链</el-tag>
                <el-tag v-if="row.allowsLivePoultry" size="small" type="danger" class="mr">活禽隔离</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="摊位费" width="80">
              <template #default="{ row }">¥{{ row.baseFee }}</template>
            </el-table-column>
            <el-table-column label="操作" width="70">
              <template #default="{ row }"><el-button size="small" @click="editZone(row)">编辑</el-button></template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="14">
        <el-card>
          <div style="display:flex;justify-content:space-between" class="mb">
            <div class="card-title" style="margin:0">摊位</div>
            <el-button size="small" type="primary" @click="editStall({})">新增摊位</el-button>
          </div>
          <el-table :data="stalls" size="small" max-height="600">
            <el-table-column prop="stallNo" label="摊位号" width="90" />
            <el-table-column label="区域" min-width="140">
              <template #default="{ row }">{{ row.zone?.name }}</template>
            </el-table-column>
            <el-table-column label="路面" width="70">
              <template #default="{ row }">
                <el-tag v-if="row.hardened" type="success" size="small">硬化</el-tag>
                <el-tag v-else type="danger" size="small">低洼</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="电源" width="60">
              <template #default="{ row }">{{ row.hasPower ? '有' : '无' }}</template>
            </el-table-column>
            <el-table-column label="限车长" width="80">
              <template #default="{ row }">{{ row.maxVehicleLength ? row.maxVehicleLength + 'm' : '不限' }}</template>
            </el-table-column>
            <el-table-column label="消防距离" width="80">
              <template #default="{ row }">{{ row.fireLaneDistance != null ? row.fireLaneDistance + 'm' : (row.nearFireLane ? '临通道' : '-') }}</template>
            </el-table-column>
            <el-table-column label="应急位" width="70">
              <template #default="{ row }">
                <el-tag v-if="row.reservedEmergency" type="warning" size="small">应急</el-tag><span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="70">
              <template #default="{ row }">
                <el-tag :type="row.active ? 'success' : 'info'" size="small">{{ row.active ? '启用' : '停用' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="70">
              <template #default="{ row }"><el-button size="small" @click="editStall(row)">编辑</el-button></template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="zoneVisible" title="区域" width="480px">
      <el-form :model="zoneForm" label-width="110px">
        <el-form-item label="代码"><el-input v-model="zoneForm.code" :disabled="!!zoneForm.id" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="zoneForm.name" /></el-form-item>
        <el-form-item label="允许品类">
          <el-select v-model="zoneCategories" multiple style="width: 100%">
            <el-option label="不限" value="*" />
            <el-option v-for="(n, c) in categories" :key="c" :label="n" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="允许熟食"><el-switch v-model="zoneForm.allowsCookedFood" /></el-form-item>
        <el-form-item label="允许活禽"><el-switch v-model="zoneForm.allowsLivePoultry" /></el-form-item>
        <el-form-item label="基础摊位费"><el-input-number v-model="zoneForm.baseFee" :min="0" /></el-form-item>
        <el-form-item label="入场偏移(分)"><el-input-number v-model="zoneForm.entryOffsetMinutes" :min="0" :step="15" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="zoneForm.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="zoneVisible = false">取消</el-button>
        <el-button type="primary" @click="saveZone">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="stallVisible" title="摊位" width="460px">
      <el-form :model="stallForm" label-width="120px">
        <el-form-item label="摊位号"><el-input v-model="stallForm.stallNo" :disabled="!!stallForm.id" /></el-form-item>
        <el-form-item label="所属区域">
          <el-select v-model="stallForm.zoneId" style="width: 100%">
            <el-option v-for="z in zones" :key="z.id" :label="z.name" :value="z.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="硬化路面">
          <el-switch v-model="stallForm.hardened" active-text="硬化(雨天可调入)" inactive-text="低洼易积水" />
        </el-form-item>
        <el-form-item label="应急备用位">
          <el-switch v-model="stallForm.reservedEmergency" active-text="仅临时/雨天调位用，不参与抽签" />
        </el-form-item>
        <el-form-item label="有电源"><el-switch v-model="stallForm.hasPower" /></el-form-item>
        <el-form-item label="限车长(米)"><el-input-number v-model="stallForm.maxVehicleLength" :min="0" :step="0.5" /></el-form-item>
        <el-form-item label="紧邻消防通道"><el-switch v-model="stallForm.nearFireLane" /></el-form-item>
        <el-form-item label="距消防通道(米)">
          <el-input-number v-model="stallForm.fireLaneDistance" :min="0" :step="0.1" :precision="1" />
        </el-form-item>
        <el-form-item label="平面图坐标(米)">
          <el-input-number v-model="stallForm.posX" :step="0.5" placeholder="X" style="width: 110px" />
          <el-input-number v-model="stallForm.posY" :step="0.5" placeholder="Y" style="width: 110px;margin-left:8px" />
          <div class="text-muted" style="font-size:12px">用于自动计算消防距离与邻摊影响</div>
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="stallForm.active" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="stallVisible = false">取消</el-button>
        <el-button type="primary" @click="saveStall">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api'
import { CATEGORIES } from '../../utils/dict'

const categories = CATEGORIES
const zones = ref([])
const stalls = ref([])
const zoneVisible = ref(false)
const stallVisible = ref(false)
const zoneForm = ref({})
const stallForm = ref({})
const zoneCategories = ref([])

async function load() {
  const [z, s] = await Promise.all([api.get('/admin/zones'), api.get('/admin/stalls')])
  zones.value = z; stalls.value = s
}

function editZone(z) {
  zoneForm.value = z.id ? { ...z } : { code: '', name: '', allowedCategories: '*', allowsCookedFood: false, allowsLivePoultry: false, baseFee: 10, entryOffsetMinutes: 0, remark: '' }
  zoneCategories.value = (zoneForm.value.allowedCategories || '*').split(',')
  zoneVisible.value = true
}

async function saveZone() {
  zoneForm.value.allowedCategories = zoneCategories.value.includes('*') ? '*' : zoneCategories.value.join(',')
  await api.post('/admin/zones', zoneForm.value)
  ElMessage.success('已保存')
  zoneVisible.value = false
  await load()
}

function editStall(s) {
  stallForm.value = s.id
    ? { ...s, zoneId: s.zone?.id }
    : { stallNo: '', zoneId: zones.value[0]?.id, hardened: true, reservedEmergency: false, hasPower: false, maxVehicleLength: 5, nearFireLane: false, fireLaneDistance: null, posX: null, posY: null, active: true }
  stallVisible.value = true
}

async function saveStall() {
  const payload = { ...stallForm.value, zone: { id: stallForm.value.zoneId } }
  await api.post('/admin/stalls', payload)
  ElMessage.success('已保存')
  stallVisible.value = false
  await load()
}

onMounted(load)
</script>

<style scoped>
.mr { margin-right: 4px; }
</style>
