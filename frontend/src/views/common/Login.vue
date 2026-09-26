<template>
  <div class="patient-root p-login">
    <div class="p-login__box">
      <div class="p-eyebrow" style="text-align: center">智 能 导 诊 系 统</div>
      <h1 class="p-login__title">分诊台</h1>
      <p class="p-login__hint">登录后即可开始症状分诊 · 管理员进入控制台</p>

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

      <button class="p-btn" style="margin-top: 18px" :disabled="loading" @click="submit">
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
.p-login {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}
.p-login__box {
  width: 100%;
  max-width: 360px;
  background: var(--card);
  border: 1px solid var(--line);
  padding: 28px 22px 22px;
}
.p-login__title {
  margin: 8px 0 4px;
  text-align: center;
  font-size: 26px;
  font-weight: 400;
  letter-spacing: .12em;
}
.p-login__hint {
  margin-bottom: 22px;
  text-align: center;
  font-size: 12.5px;
  color: var(--ink-2);
}
.p-login__field { margin-bottom: 14px; }
.p-login__field label { display: block; margin-bottom: 5px; }
.p-login__input {
  width: 100%;
  min-height: 44px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 0;
  background: var(--card);
  font-family: var(--serif);
  font-size: 14px;
  color: var(--ink);
}
.p-login__input:focus { outline: none; border-color: var(--teal); }
.p-login__err { font-size: 12.5px; color: var(--err); }
.p-login__switch {
  margin-top: 12px;
  text-align: center;
  font-size: 12.5px;
  color: var(--teal);
  cursor: pointer;
}
.p-login__switch:hover { text-decoration: underline; }
.p-login__tip {
  margin-top: 14px;
  text-align: center;
  font-size: 11.5px;
  color: var(--ink-2);
}
</style>
