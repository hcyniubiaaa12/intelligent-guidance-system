<template>
  <div class="patient-root p-login">
    <div class="p-login__box">
      <div class="p-login__hd">
        <span class="p-login__mark" aria-hidden="true">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <rect x="2.6" y="2.6" width="18.8" height="18.8" stroke="currentColor" stroke-width="1.7" />
            <path d="M6.9 8h10.2" stroke="currentColor" stroke-width="2.3" />
            <path d="M6.9 12h6.8" stroke="currentColor" stroke-width="2" opacity=".52" />
            <path d="M6.9 16h3.6" stroke="currentColor" stroke-width="1.7" opacity=".3" />
          </svg>
        </span>
        <div>
          <h1 class="p-login__title">智能导诊</h1>
          <p class="p-login__hint">登录后就能开始分诊 · 管理员进控制台</p>
        </div>
      </div>

      <div class="p-login__field">
        <label class="p-eyebrow" for="u">用 户 名</label>
        <input id="u" v-model="form.username" class="p-login__input" placeholder="请输入用户名" />
      </div>
      <div class="p-login__field">
        <label class="p-eyebrow" for="p">密 码</label>
        <input
          id="p"
          v-model="form.password"
          class="p-login__input"
          type="password"
          placeholder="请输入密码"
          @keydown.enter="submit"
        />
      </div>

      <div v-if="mode === 'register'" class="p-login__field">
        <label class="p-eyebrow" for="n">昵 称（选填）</label>
        <input
          id="n"
          v-model="form.nickname"
          class="p-login__input"
          placeholder="怎么称呼您"
          @keydown.enter="submit"
        />
      </div>

      <p v-if="error" class="p-login__err">{{ error }}</p>

      <button class="p-btn p-login__submit" :disabled="loading" @click="submit">
        {{ loading ? '请稍候…' : mode === 'login' ? '登 录' : '注 册' }}
      </button>

      <p class="p-login__switch" @click="switchMode">
        {{ mode === 'login' ? '还没有账号？注册一个（患者身份）' : '已有账号？返回登录' }}
      </p>

      <p class="p-login__tip">演示：admin / 123456 进管理端，患者账号可注册后登录</p>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '../../stores/user'
import { login, register } from '../../api/auth'
import '../../styles/patient.css'

const router = useRouter()
const route = useRoute()
const user = useUserStore()

const mode = ref('login') // login / register（注册默认患者角色，链路 D）
const loading = ref(false)
const form = ref({ username: '', password: '', nickname: '' })

/**
 * 登录后的落点：**由角色决定，redirect 只在它指向该角色有意义的页面时才算数**。
 *
 * 为什么不能让 redirect 无条件优先：访客打开站点根路径 `/` 时，路由守卫会把他送到
 * `/login?redirect=/`（`/` 是**患者首页**）。如果 redirect 压过角色，管理员从根路径进来
 * 登录就会被送进患者端——"第一次登录管理员账号却跳到用户端"就是这么来的，
 * 而之后浏览器已有登录态、直接进 `/admin/**` 不再经过登录页，所以只有第一次会碰到。
 *
 * 反方向同理：患者带着 `redirect=/admin/...` 来时不该先跳过去再被守卫弹回来。
 */
function landingPath(role) {
  const redirect = route.query.redirect
  const target = typeof redirect === 'string' && redirect ? redirect : ''
  if (role === 'admin') {
    return target.startsWith('/admin') ? target : '/admin/dashboard'
  }
  return target && !target.startsWith('/admin') ? target : '/'
}
const error = ref('')

function switchMode() {
  mode.value = mode.value === 'login' ? 'register' : 'login'
  error.value = ''
}

async function submit() {
  if (!form.value.username || !form.value.password) {
    error.value = '请填写用户名与密码'
    return
  }
  if (mode.value === 'register' && form.value.username.length < 3) {
    error.value = '用户名长度需在 3-32 位之间'
    return
  }
  if (mode.value === 'register' && form.value.password.length < 6) {
    error.value = '密码长度需在 6-64 位之间'
    return
  }
  loading.value = true
  error.value = ''
  try {
    // 登录/注册均返回 LoginVO；注册成功即登录（后端直接签发 token）
    const vo = mode.value === 'login'
      ? await login({ username: form.value.username, password: form.value.password })
      : await register({
          username: form.value.username,
          password: form.value.password,
          nickname: form.value.nickname || undefined
        })
    user.setLogin(vo)
    router.push(landingPath(vo.role))
  } catch (e) {
    // 后端校验 message（用户名已存在 / 封禁 / 密码错误等）直接展示
    error.value = e.message || '登录失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/* 登录 / 注册页 —— 方向 04「导诊伙伴」
   暖沙底上一列居中的「导诊卡」，无侧栏、无顶栏。表单控件用共享令牌自绘，
   主按钮直接用共享件 `.p-btn`——本页不重复定义它。 */

.p-login {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 20px;
  background: var(--bg);
}
.p-login__box {
  width: 100%;
  max-width: 380px;
  padding: 28px 22px 22px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  box-shadow: var(--sh-rest);
}
.p-login__hd { display: flex; align-items: center; gap: 12px; }
.p-login__mark {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: var(--r-xs);
  background: var(--leaf);
  color: var(--on-accent);
}
.p-login__title {
  font-size: 26px; /* 巨型：一屏最大的那句话 */
  font-weight: 600;
  letter-spacing: .02em;
  line-height: 1.3;
}
.p-login__hint {
  margin-top: 4px;
  font-size: 12.5px;
  line-height: 1.85;
  color: var(--ink-2);
}
.p-login__field { margin-top: 16px; }
.p-login__field label { display: block; margin-bottom: 8px; }
.p-login__input {
  width: 100%;
  min-height: 44px; /* 触控目标 ≥ 44px */
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--surface);
  font-family: var(--sans);
  font-size: 13.5px;
  color: var(--ink);
  transition: border-color .18s ease, box-shadow .18s ease;
}
.p-login__input::placeholder { color: var(--ink-3); }
.p-login__input:focus {
  outline: none;
  border-color: var(--leaf);
  box-shadow: 0 0 0 3px var(--leaf-glow);
}
/* 错误态就地说明，不弹窗 */
.p-login__err {
  margin-top: 12px;
  padding: 12px 16px;
  border-left: 3px solid var(--alert);
  border-radius: var(--r-sm);
  background: var(--alert-soft);
  font-size: 12.5px;
  line-height: 1.85;
  color: var(--alert);
}
.p-login__submit { margin-top: 20px; }
.p-login__switch {
  margin-top: 16px;
  text-align: center;
  font-size: 12.5px;
  color: var(--leaf-deep);
  cursor: pointer;
}
.p-login__switch:hover { text-decoration: underline; }
.p-login__tip {
  margin-top: 16px;
  text-align: center;
  font-size: 11.5px;
  color: var(--ink-3);
}
</style>
