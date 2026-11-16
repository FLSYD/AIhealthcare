<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { User, Lock, ArrowRight } from '@element-plus/icons-vue'
import { auth, signIn } from '../store/auth'
import { canRoleAccess } from '../router'
const router = useRouter(), route = useRoute()
const form = reactive({ username: '', password: '' }), formRef = ref(), busy = ref(false), error = ref('')
const rules = { username: [{ required: true, whitespace: true, message: '请输入登录账号', trigger: 'blur' }], password: [{ required: true, message: '请输入密码', trigger: 'blur' }] }
async function submit() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    if (!await formRef.value.validate().catch(() => false)) return
    const home = await signIn(form.username, form.password)
    const next = route.query.redirect
    const candidate = typeof next === 'string' && next.startsWith('/') && !next.startsWith('//') && next !== '/login'
      ? next
      : home
    const resolved = router.resolve(candidate)
    await router.replace(canRoleAccess(resolved.meta.roles, auth.user?.role) ? candidate : home)
  } catch (failure) { error.value = failure.message }
  finally { busy.value = false }
}
</script>
<template>
  <div class="login-page">
    <div class="login-brand"><span class="brand-mark">✚</span><div><strong>医院预约挂号管理</strong><span>HOSPITAL MANAGEMENT SYSTEM</span></div></div>
    <div class="login-content">
      <section class="login-intro"><div class="intro-eyebrow"><span class="online-dot"></span> 高效协同 · 用心守护</div><h1>让每一次就诊<br />都有序发生<span>。</span></h1><p>连接预约与诊疗，让医院管理更高效，<br />让医护工作更从容。</p>
        <div class="hospital-illustration" aria-hidden="true"><div class="illustration-orbit orbit-one"></div><div class="illustration-orbit orbit-two"></div><div class="floating-badge badge-calendar">▦ <span>预约有序</span></div><div class="floating-badge badge-care">♡ <span>诊疗协同</span></div><svg viewBox="0 0 520 320"><defs><linearGradient id="hospitalFill" x2="0" y2="1"><stop stop-color="#fff"/><stop offset="1" stop-color="#ecf2ff"/></linearGradient></defs><ellipse cx="260" cy="292" rx="205" ry="17" fill="#cdddf7" opacity=".45"/><path d="M90 270V132h115v138M315 270V132h115v138" fill="#d4e4fc"/><rect x="170" y="60" width="180" height="215" rx="15" fill="url(#hospitalFill)" stroke="#c3d7f5"/><path d="M170 114h180" stroke="#dce7f8"/><rect x="235" y="76" width="50" height="24" rx="6" fill="#e8efff"/><path d="M257 80v16m-8-8h16" stroke="#3774ef" stroke-width="5"/><g fill="#9dbfee"><rect x="194" y="135" width="26" height="31" rx="4"/><rect x="246" y="135" width="26" height="31" rx="4"/><rect x="298" y="135" width="26" height="31" rx="4"/><rect x="194" y="184" width="26" height="31" rx="4"/><rect x="246" y="184" width="26" height="31" rx="4"/><rect x="298" y="184" width="26" height="31" rx="4"/></g><g fill="#b0cdef"><rect x="110" y="156" width="19" height="25" rx="3"/><rect x="139" y="156" width="19" height="25" rx="3"/><rect x="110" y="198" width="19" height="25" rx="3"/><rect x="139" y="198" width="19" height="25" rx="3"/><rect x="366" y="156" width="19" height="25" rx="3"/><rect x="395" y="156" width="19" height="25" rx="3"/><rect x="366" y="198" width="19" height="25" rx="3"/><rect x="395" y="198" width="19" height="25" rx="3"/></g><path d="M235 275v-32q0-13 13-13h25q13 0 13 13v32" fill="#719ee6"/><path d="M260 230v45" stroke="#edf4ff" stroke-width="3"/><path d="M72 279h376" stroke="#adc8ed" stroke-width="5" stroke-linecap="round"/><g><path d="M68 273v-48m0 9-12-13m12 27 13-16" stroke="#91bdad" stroke-width="5" stroke-linecap="round"/><circle cx="61" cy="213" r="22" fill="#b4dcd1"/><circle cx="78" cy="225" r="18" fill="#a3d2c6"/><path d="M454 273v-45" stroke="#91bdad" stroke-width="5"/><circle cx="453" cy="217" r="25" fill="#b4dcd1"/></g></svg></div>
        <div class="intro-points"><span>预约管理</span><i></i><span>医生工作台</span><i></i><span>全流程协同</span></div>
      </section>
      <section class="login-card"><div class="login-greeting">欢迎回来</div><h2>登录管理工作台</h2><p class="login-subtitle">使用管理员、医生或配药师账号继续</p>
        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="login-error" />
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit"><el-form-item label="登录账号" prop="username"><el-input v-model="form.username" :prefix-icon="User" placeholder="请输入用户名" autocomplete="username" size="large" maxlength="50" @keyup.enter="submit" /></el-form-item><el-form-item label="登录密码" prop="password"><el-input v-model="form.password" type="password" :prefix-icon="Lock" show-password placeholder="请输入密码" autocomplete="current-password" size="large" @keyup.enter="submit" /></el-form-item><el-button class="login-submit" type="primary" size="large" :loading="busy" native-type="submit">登 录<el-icon v-if="!busy"><ArrowRight /></el-icon></el-button></el-form>
        <div class="login-tip"><el-icon><Lock /></el-icon><span>登录后将根据账号角色进入对应工作台</span></div>
      </section>
    </div><footer class="login-footer">医院预约挂号管理系统 <span>·</span> 用心服务每一次就诊</footer>
  </div>
</template>
<style scoped>
.login-page{min-height:100vh;background:radial-gradient(ellipse at 23% 44%,#e6f0ff 0,transparent 57%),linear-gradient(115deg,#f3f8ff,#f8fbff 60%,#eef4fc);display:flex;flex-direction:column;padding:36px 5.2% 22px;overflow:hidden}.login-brand{display:flex;align-items:center;gap:13px}.login-brand strong{display:block;font-size:18px;color:#1f3555;letter-spacing:.4px}.login-brand>div>span{display:block;margin-top:5px;color:#9aaec7;font-size:9px;letter-spacing:2px}.login-content{flex:1;display:flex;align-items:center;justify-content:center;gap:clamp(50px,8vw,150px);padding:30px 0}.login-intro{width:min(520px,45%);position:relative}.intro-eyebrow{color:#7193bc;font-size:12px;letter-spacing:2px;display:flex;gap:9px;align-items:center;margin-bottom:21px}.login-intro h1{font-size:clamp(30px,3.3vw,47px);font-weight:700;color:#1e3d68;line-height:1.48;letter-spacing:2px}.login-intro h1>span{color:#3b80ee}.login-intro>p{font-size:14px;color:#859cb9;line-height:2;margin-top:20px;letter-spacing:.4px}.hospital-illustration{position:relative;width:430px;max-width:100%;margin:0 0 -5px -4px}.hospital-illustration svg{position:relative;display:block;width:100%;z-index:1}.illustration-orbit{position:absolute;border:1px dashed #d3e2fa;border-radius:50%;inset:33px 20px 15px}.orbit-two{inset:1px 65px 35px;transform:rotate(-25deg)}.floating-badge{position:absolute;z-index:2;display:flex;align-items:center;gap:9px;background:#ffffffdf;border:1px solid #fff;box-shadow:0 8px 22px #456da00c;border-radius:11px;padding:12px 16px;color:#4a82e4;font-size:24px}.floating-badge>span{font-size:12px;color:#7b94b5}.badge-calendar{right:-8px;top:68px;transform:rotate(5deg)}.badge-care{left:-22px;bottom:65px;transform:rotate(-5deg);color:#54b8a4}.intro-points{display:flex;gap:19px;align-items:center;color:#8da3bf;font-size:12px}.intro-points i{height:3px;width:3px;border-radius:50%;background:#afc2dc}.login-card{background:#fff;border:1px solid #fff;border-radius:19px;padding:43px 40px 30px;width:405px;max-width:100%;box-shadow:0 16px 60px #2e4c7a0b;position:relative}.login-card::before{content:"";position:absolute;top:-1px;left:40px;width:42px;height:4px;border-radius:0 0 5px 5px;background:#3879f0}.login-greeting{color:#94a2b6;font-size:12px;letter-spacing:2px;margin-bottom:12px}.login-card h2{font-size:24px;color:#21334b;margin-bottom:11px;font-weight:650}.login-subtitle{color:#9ba7b8;font-size:12px;margin-bottom:32px}.login-card :deep(.el-form-item__label){font-size:12px;color:#6d7b90;margin-bottom:10px}.login-card :deep(.el-input__wrapper){height:46px;background:#fcfdff}.login-card :deep(.el-form-item){margin-bottom:23px}.login-submit{width:100%;height:46px;margin-top:9px;display:flex;gap:18px}.login-submit .el-icon{margin-left:12px}.login-tip{margin-top:24px;display:flex;align-items:center;justify-content:center;gap:7px;font-size:10px;color:#a6b0bf}.login-tip .el-icon{font-size:12px}.login-error{margin-bottom:18px}.login-footer{display:flex;justify-content:center;gap:10px;color:#a0afc1;font-size:11px;line-height:1.8}.login-footer span{color:#bfccdc}@media(max-width:850px){.login-intro{display:none}.login-content{padding:35px 0}.login-page{padding:28px 20px 20px}.login-card{padding:36px 30px}.login-brand{justify-content:center}.login-brand strong{font-size:16px}}@media(max-width:420px){.login-card{padding:32px 24px}.login-brand>div>span{letter-spacing:1px}.login-footer{font-size:10px}}
</style>
