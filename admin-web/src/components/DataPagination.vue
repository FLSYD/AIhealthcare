<script setup>
const page = defineModel('page', { default: 1 })
const size = defineModel('size', { default: 10 })
defineProps({ total: { type: Number, default: 0 } })
const emit = defineEmits(['change'])
function resize() { page.value = 1; emit('change') }
</script>
<template>
  <div class="table-pagination"><el-pagination v-model:current-page="page" v-model:page-size="size" :total="total" :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next" background @current-change="emit('change')" @size-change="resize" /></div>
</template>
