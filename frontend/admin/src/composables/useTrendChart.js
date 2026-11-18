import { ref, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts'

/**
 * 近 7 日销售趋势图：负责 ECharts 实例的创建、更新与销毁。
 * 图表配置与重构前保持一致。
 */
export function useTrendChart(trendData) {
  const trendChartRef = ref(null)
  let trendChart = null

  function renderTrendChart() {
    const chartDom = trendChartRef.value
    if (!chartDom) return

    if (trendChart) {
      trendChart.dispose()
    }
    trendChart = echarts.init(chartDom)

    const dates = trendData.value.map(item => item.date)
    const amounts = trendData.value.map(item => item.amount)
    const counts = trendData.value.map(item => item.count)

    trendChart.setOption({
      tooltip: {
        trigger: 'axis',
        backgroundColor: 'rgba(255,255,255,0.96)',
        borderColor: '#EBE2D7',
        textStyle: { color: '#2B211C', fontSize: 13 },
        formatter(params) {
          let html = `<div style="font-weight:600;margin-bottom:4px">${params[0].axisValue}</div>`
          params.forEach(p => {
            const marker = `<span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:${p.color};margin-right:6px"></span>`
            const val = p.seriesName === '营业额'
              ? `¥${Number(p.value).toFixed(2)}`
              : `${p.value} 单`
            html += `<div style="margin:2px 0">${marker}${p.seriesName}：${val}</div>`
          })
          return html
        }
      },
      legend: {
        data: ['营业额', '订单数'],
        right: 20,
        top: 0,
        textStyle: { color: '#7A6A5D', fontSize: 13 }
      },
      grid: {
        left: 20,
        right: 20,
        bottom: 16,
        top: 40,
        containLabel: true
      },
      xAxis: {
        type: 'category',
        data: dates,
        axisLine: { lineStyle: { color: '#EBE2D7' } },
        axisLabel: { color: '#A8988B', fontSize: 12 },
        axisTick: { show: false }
      },
      yAxis: [
        {
          type: 'value',
          axisLine: { show: false },
          axisTick: { show: false },
          splitLine: { lineStyle: { color: '#EBE2D7', type: 'dashed' } },
          axisLabel: { color: '#A8988B', fontSize: 12 }
        },
        {
          type: 'value',
          minInterval: 1,
          axisLine: { show: false },
          axisTick: { show: false },
          splitLine: { show: false },
          axisLabel: { color: '#A8988B', fontSize: 12 }
        }
      ],
      series: [
        {
          name: '营业额',
          type: 'line',
          data: amounts,
          smooth: true,
          symbol: 'circle',
          symbolSize: 7,
          lineStyle: { width: 2.5, color: '#A6643C' },
          itemStyle: { color: '#A6643C', borderWidth: 2, borderColor: '#fff' },
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(166,100,60,0.25)' },
              { offset: 1, color: 'rgba(166,100,60,0.02)' }
            ])
          }
        },
        {
          name: '订单数',
          type: 'bar',
          yAxisIndex: 1,
          data: counts,
          barWidth: 24,
          itemStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(94,138,85,0.6)' },
              { offset: 1, color: 'rgba(94,138,85,0.15)' }
            ]),
            borderRadius: [4, 4, 0, 0]
          }
        }
      ]
    })
  }

  function handleResize() {
    trendChart && trendChart.resize()
  }

  watch(trendData, async (newVal) => {
    if (newVal.length > 0) {
      await nextTick()
      renderTrendChart()
    }
  })

  onMounted(async () => {
    await nextTick()
    if (trendData.value.length > 0) {
      renderTrendChart()
    }
    window.addEventListener('resize', handleResize)
  })

  onBeforeUnmount(() => {
    window.removeEventListener('resize', handleResize)
    if (trendChart) {
      trendChart.dispose()
      trendChart = null
    }
  })

  return { trendChartRef }
}