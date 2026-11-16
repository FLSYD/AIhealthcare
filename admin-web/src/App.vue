<script setup>
import { onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { auth, clearSession, invalidateSession, loadSession } from './store/auth'
import { TOKEN_KEY } from './lib/api'
const router = useRouter()
let storageSequence = 0
function expired() { storageSequence++; clearSession(); if (router.currentRoute.value.path !== '/login') router.replace('/login') }
async function sessionChanged(event) {
  if (event.key !== TOKEN_KEY && event.key !== null) return
  const sequence = ++storageSequence
  invalidateSession()
  try {
    const loggedIn = await loadSession(true)
    if (sequence === storageSequence) router.replace(loggedIn ? auth.permissions.homePath : '/login')
  } catch { if (sequence === storageSequence) router.replace('/login') }
}
onMounted(() => { window.addEventListener('healthcare-session-expired', expired); window.addEventListener('storage', sessionChanged) })
onUnmounted(() => { window.removeEventListener('healthcare-session-expired', expired); window.removeEventListener('storage', sessionChanged) })
</script>
<template><el-config-provider><router-view /></el-config-provider></template>
