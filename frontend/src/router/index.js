import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', component: () => import('../views/Login.vue') },
  { path: '/complaint', component: () => import('../views/PublicComplaint.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('../layouts/MainLayout.vue'),
    children: [
      { path: '', redirect: '/home' },
      { path: 'home', component: () => import('../views/Home.vue') },
      // 摊主
      { path: 'apply', component: () => import('../views/vendor/Apply.vue'), meta: { role: 'VENDOR' } },
      { path: 'result', component: () => import('../views/vendor/Result.vue'), meta: { role: 'VENDOR' } },
      { path: 'rain', component: () => import('../views/vendor/RainRelocations.vue'), meta: { role: 'VENDOR' } },
      { path: 'samples', component: () => import('../views/vendor/Samples.vue'), meta: { role: 'VENDOR' } },
      { path: 'trace', component: () => import('../views/vendor/Trace.vue'), meta: { role: 'VENDOR' } },
      // 工作人员
      { path: 'entry', component: () => import('../views/staff/Entry.vue'), meta: { role: 'STAFF' } },
      { path: 'patrol', component: () => import('../views/staff/Patrol.vue'), meta: { role: 'STAFF' } },
      // 管理员
      { path: 'admin/events', component: () => import('../views/admin/Events.vue'), meta: { role: 'ADMIN' } },
      { path: 'admin/stalls', component: () => import('../views/admin/Stalls.vue'), meta: { role: 'ADMIN' } },
      { path: 'admin/lottery', component: () => import('../views/admin/Lottery.vue'), meta: { role: 'ADMIN' } },
      { path: 'admin/rain', component: () => import('../views/admin/RainRelocations.vue'), meta: { role: 'ADMIN' } },
      { path: 'admin/complaints', component: () => import('../views/admin/Complaints.vue'), meta: { role: 'ADMIN' } },
      { path: 'admin/adjustments', component: () => import('../views/admin/Adjustments.vue'), meta: { role: 'ADMIN' } },
      { path: 'admin/dashboard', component: () => import('../views/admin/Dashboard.vue'), meta: { role: 'ADMIN' } },
      { path: 'admin/audits', component: () => import('../views/admin/Audits.vue'), meta: { role: 'ADMIN' } }
    ]
  }
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach(to => {
  if (to.meta.public || to.path === '/login') return true
  const token = localStorage.getItem('token')
  if (!token) return '/login'
  const user = JSON.parse(localStorage.getItem('user') || 'null')
  if (to.meta.role && user?.role !== to.meta.role && user?.role !== 'ADMIN') {
    return '/home'
  }
  return true
})

export default router
