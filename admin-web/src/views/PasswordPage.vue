<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../lib/api'
import { clearSession } from '../store/auth'
const router = useRouter(), formRef = ref(), busy = ref(false)
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const rules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [{ required: true, min: 6, max: 50, message: '新密码为6至50个字符', trigger: 'blur' }],
  confirmPassword: [{ required: true, message: '请再次输入新密码', trigger: 'blur' }, { validator: (_, value, done) => value === form.newPassword ? done() : done(new Error('两次输入的密码不一致')), trigger: 'blur' }],
}
async function submit() {
  if (busy.value) return
  busy.value = true
  try { if (!await formRef.value.validate().catch(() => false)) return; await api.put('/password', { ...form }); ElMessage.success('密码已修改，请使用新密码登录'); clearSession(); router.replace('/login') } catch {} finally { busy.value = false }
}
function clear() { Object.assign(form, { oldPassword: '', newPassword: '', confirmPassword: '' }); formRef.value?.clearValidate() }
</script>
<template><section class="page-panel password-panel"><div class="password-heading"><div><h1>修改密码</h1><p>修改成功后，请使用新密码重新登录</p></div><el-button @click="router.push('/profile')">返回</el-button></div><el-form ref="formRef" :model="form" :rules="rules" label-width="100px" @submit.prevent="submit"><el-form-item label="原密码" prop="oldPassword"><el-input v-model="form.oldPassword" type="password" show-password placeholder="请输入原密码" autocomplete="current-password" /></el-form-item><el-form-item label="新密码" prop="newPassword"><el-input v-model="form.newPassword" type="password" show-password placeholder="6至50个字符" maxlength="50" autocomplete="new-password" /></el-form-item><el-form-item label="确认新密码" prop="confirmPassword"><el-input v-model="form.confirmPassword" type="password" show-password placeholder="再次输入新密码" autocomplete="new-password" /></el-form-item><el-form-item><el-button type="primary" native-type="submit" :loading="busy">确认修改</el-button><el-button @click="clear">清空</el-button></el-form-item></el-form></section></template>
<style scoped>.password-panel{max-width:760px;margin:auto;padding:28px 30px}.password-heading{display:flex;justify-content:space-between;align-items:center;padding-bottom:23px;border-bottom:1px solid #edf1f6;margin-bottom:28px;gap:12px}.password-heading h1{font-size:21px;color:#22344c}.password-heading p{font-size:12px;color:#94a1b4;margin-top:8px}@media(max-width:560px){.password-panel{padding:22px 16px}.password-panel :deep(.el-form-item__label){width:83px!important}}</style>
