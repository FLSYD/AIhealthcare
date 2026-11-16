<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
const props = defineProps({ option: Object, height: { type: Number, default: 300 } })
const element = ref()
let chart, observer
onMounted(() => { chart = echarts.init(element.value); if (props.option) chart.setOption(props.option); observer = new ResizeObserver(() => chart?.resize()); observer.observe(element.value) })
watch(() => props.option, value => { if (value && chart) chart.setOption(value, true) }, { deep: true })
onBeforeUnmount(() => { observer?.disconnect(); chart?.dispose() })
</script>
<template><div ref="element" :style="{ height: height + 'px', width: '100%', minWidth: '0' }"></div></template>
