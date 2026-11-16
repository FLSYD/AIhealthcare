<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../lib/api'
import { auth } from '../store/auth'
import { roleLabel } from '../lib/format'
import UserAvatar from '../components/UserAvatar.vue'
const form = reactive({ nickname: '', email: '', phone: '' }), formRef = ref(), saving = ref(false), uploading = ref(false), loading = ref(false), doctor = ref(null), doctorSaving = ref(false), doctorUploading = ref(false)
const intro = ref(''), error = ref('')
const rules = { email: [{ type: 'email', message: '请输入有效的邮箱地址', trigger: 'blur' }], phone: [{ pattern: /^(1[3-9]\d{9})?$/, message: '请输入有效的11位手机号', trigger: 'blur' }] }
async function load() {
  loading.value = true; error.value = ''
  try { auth.user = await api.get('/me'); reset(); if (auth.user.role === 'DOCTOR') { doctor.value = await api.get('/doctor-workspace/profile'); intro.value = doctor.value.intro || '' } }
  catch (failure) { error.value = failure.message } finally { loading.value = false }
}
function reset() { Object.assign(form, { nickname: auth.user?.nickname || '', email: auth.user?.email || '', phone: auth.user?.phone || '' }); formRef.value?.clearValidate() }
async function save() {
  if (saving.value || uploading.value || loading.value) return
  saving.value = true
  try { if (!await formRef.value.validate().catch(() => false)) return; auth.user = await api.put('/profile', { ...form }); ElMessage.success('个人信息已保存') } catch {} finally { saving.value = false }
}
function validFile(file) {
  if (!/^image\/(jpeg|png|gif|webp)$/.test(file.type)) { ElMessage.warning('请选择 JPG、PNG、GIF 或 WEBP 图片'); return false }
  if (file.size > 2 * 1024 * 1024) { ElMessage.warning('图片不能超过2MB'); return false }
  return true
}
async function upload({ file }, forDoctor = false) {
  if (loading.value || (forDoctor ? doctorUploading.value || doctorSaving.value : uploading.value || saving.value)) return
  if (!validFile(file)) return
  const busy = forDoctor ? doctorUploading : uploading
  busy.value = true
  try {
    const body = new FormData(); body.append('file', file)
    const result = await api.post(forDoctor ? '/doctor-workspace/profile/avatar' : '/avatar', body)
    if (forDoctor) doctor.value.avatar = result.avatar
    else auth.user = { ...auth.user, avatar: result }
    ElMessage.success('头像已更新')
  } catch {} finally { busy.value = false }
}
async function saveIntro() {
  if (doctorSaving.value || doctorUploading.value || loading.value) return
  doctorSaving.value = true
  try { doctor.value = await api.put('/doctor-workspace/profile/intro', { intro: intro.value }); ElMessage.success('医生简介已保存') } catch {} finally { doctorSaving.value = false }
}
onMounted(load)
</script>
<template>
  <div class="profile-page" v-loading="loading"><el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="empty-error"><el-button link @click="load">重新加载</el-button></el-alert>
    <section class="page-panel profile-panel"><div class="profile-header"><div><h1>个人信息</h1><p>管理您的头像、昵称与联系方式</p></div><router-link to="/password"><el-button>修改密码</el-button></router-link></div>
      <div class="avatar-area"><el-upload :show-file-list="false" :http-request="options => upload(options)" accept="image/jpeg,image/png,image/gif,image/webp" :disabled="uploading"><button class="avatar-upload" :disabled="uploading"><UserAvatar :src="auth.user?.avatar" :name="auth.user?.nickname || auth.user?.username" :size="100" /><span>{{ uploading ? '上传中…' : '更换头像' }}</span></button></el-upload><p>支持 JPG / PNG / GIF / WEBP，不超过2MB</p></div>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="78px" class="profile-form" @submit.prevent="save"><el-form-item label="用户名"><el-input :model-value="auth.user?.username" disabled /></el-form-item><el-form-item label="角色"><el-tag :type="auth.user?.role === 'ADMIN' ? 'danger' : auth.user?.role === 'PHARMACIST' ? 'warning' : 'success'" round>{{ roleLabel(auth.user?.role) }}</el-tag></el-form-item><el-form-item label="昵称"><el-input v-model="form.nickname" maxlength="50" show-word-limit /></el-form-item><el-form-item label="邮箱" prop="email"><el-input v-model="form.email" maxlength="100" placeholder="example@mail.com" /></el-form-item><el-form-item label="手机号" prop="phone"><el-input v-model="form.phone" maxlength="11" placeholder="11位手机号" /></el-form-item><el-form-item class="form-end"><el-button type="primary" :loading="saving" native-type="submit">保存</el-button><el-button @click="reset">重置</el-button></el-form-item></el-form>
    </section>
    <section v-if="doctor" class="page-panel doctor-profile-panel"><div class="profile-header"><div><h2>医生执业资料</h2><p>用于患者预约页面展示</p></div><el-upload :show-file-list="false" :http-request="options => upload(options, true)" accept="image/jpeg,image/png,image/gif,image/webp" :disabled="doctorUploading"><el-button :loading="doctorUploading">更换医生照片</el-button></el-upload></div><div class="doctor-profile-ident"><UserAvatar :src="doctor.avatar" :name="doctor.name" :size="54" /><div><strong>{{ doctor.name }}</strong><p>{{ doctor.departmentName }} · {{ doctor.title || '医生' }}</p></div></div><el-input v-model="intro" type="textarea" :rows="5" maxlength="1000" show-word-limit placeholder="介绍您的专业方向与擅长领域" /><div class="intro-save"><el-button type="primary" :loading="doctorSaving" @click="saveIntro">保存简介</el-button></div></section>
  </div>
</template>
<style scoped>
.profile-page{width:min(760px,100%);margin:0 auto;display:flex;flex-direction:column;gap:20px}.profile-panel,.doctor-profile-panel{padding:26px 30px}.profile-header{display:flex;align-items:center;justify-content:space-between;gap:12px;padding-bottom:18px;border-bottom:1px solid #edf1f6}.profile-header h1{font-size:21px;color:#1c2c43}.profile-header h2{font-size:17px;color:#24364b}.profile-header p{font-size:12px;color:#92a0b4;margin-top:7px}.avatar-area{text-align:center;padding:28px 0 25px;border-bottom:1px solid #edf1f6}.avatar-upload{background:none;border:0;padding:5px;display:flex;flex-direction:column;align-items:center;gap:13px}.avatar-upload>span{color:#4b7ce4;font-size:12px}.avatar-area>p{color:#9aa7b9;font-size:11px;margin-top:9px}.profile-form{padding-top:27px}.form-end{margin-top:28px;margin-bottom:0}.form-end :deep(.el-form-item__content){justify-content:flex-end}.doctor-profile-ident{display:flex;align-items:center;gap:15px;margin:23px 0}.doctor-profile-ident strong{font-size:17px;font-weight:600}.doctor-profile-ident p{font-size:12px;color:#8c9aaf;margin-top:7px}.intro-save{display:flex;justify-content:flex-end;margin-top:20px}@media(max-width:560px){.profile-panel,.doctor-profile-panel{padding:20px 16px}.profile-header{align-items:flex-start}.profile-form :deep(.el-form-item__label){width:65px!important}}
</style>
