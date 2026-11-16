<script setup>
import { useRoute, useRouter } from 'vue-router'
import { auth } from '../store/auth'
const route = useRoute(), router = useRouter()
</script>
<template><section class="page-panel"><el-result :icon="route.path === '/forbidden' ? 'warning' : 'info'" :title="route.path === '/forbidden' ? '暂无此页面的访问权限' : '页面不存在'" sub-title="请从左侧菜单进入您可以使用的页面"><template #extra><el-button type="primary" @click="router.replace(auth.permissions.homePath)">返回工作台</el-button></template></el-result></section></template>
