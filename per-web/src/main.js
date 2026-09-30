/**
 * 应用入口：创建 Vue 应用并注册 Pinia、路由、Element Plus（中文语言包）
 */
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import './styles/tokens.css'
import './styles/element.css'
import './styles/base.css'
import App from './App.vue'
import router from './router'

/**
 * 应用引导函数：完成插件注册并挂载到 #app 节点
 */
function bootstrap() {
  const app = createApp(App)
  app.use(createPinia())
  app.use(router)
  app.use(ElementPlus, { locale: zhCn })
  app.mount('#app')
}

bootstrap()
