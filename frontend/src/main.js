import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { ensureElement } from './plugins/element'
import './styles/base.css'

const app = createApp(App)
app.use(createPinia())
// router 模块中的鉴权守卫先注册；安装 router 前加后续守卫，首次直达管理端也能等到组件库。
router.beforeEach(async to => {
  if (to.matched.some(route => route.meta.requiresAdmin)) await ensureElement(app)
})
app.use(router)
app.mount('#app')
