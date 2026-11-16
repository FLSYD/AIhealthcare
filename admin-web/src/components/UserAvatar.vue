<script setup>
import { computed, ref, watch } from 'vue'
import { avatarUrl } from '../lib/format'
const props = defineProps({ src: String, name: String, size: { type: Number, default: 40 } })
const failed = ref(false)
watch(() => props.src, () => failed.value = false)
const initials = computed(() => (props.name || '用户').slice(0, 2))
function fallback() { failed.value = true; return true }
</script>
<template>
  <el-avatar :size="size" :src="failed ? '' : avatarUrl(src)" class="user-avatar" @error="fallback">{{ initials }}</el-avatar>
</template>
