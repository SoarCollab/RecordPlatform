<script setup>
import ContainerPage from "@/components/ContainerPage.vue";
import { getErrorStatisticsApi } from "@/api/audit.js";
import { onMounted, reactive, ref } from "vue";
import * as echarts from "echarts";
import { ElMessage } from "element-plus";

const statistics = reactive({
  loading: false,
  chartInstance: null,
  data: []
});

// const initChart = () => {
//   const chartDom = document.getElementById('error-statistics-chart');
//   statistics.chartInstance = echarts.init(chartDom);
//
//   const option = {
//     title: {
//       text: '用户错误操作统计',
//       left: 'center'
//     },
//     tooltip: {
//       trigger: 'item',
//       formatter: '{a} <br/>{b}: {c} ({d}%)'
//     },
//     legend: {
//       orient: 'vertical',
//       left: 'left',
//       data: []
//     },
//     series: [
//       {
//         name: '错误类型',
//         type: 'pie',
//         radius: ['40%', '70%'],
//         avoidLabelOverlap: false,
//         itemStyle: {
//           borderRadius: 10,
//           borderColor: '#fff',
//           borderWidth: 2
//         },
//         label: {
//           show: false,
//           position: 'center'
//         },
//         emphasis: {
//           label: {
//             show: true,
//             fontSize: 16,
//             fontWeight: 'bold'
//           }
//         },
//         labelLine: {
//           show: false
//         },
//         data: []
//       }
//     ]
//   };
//
//   statistics.chartInstance.setOption(option);
// };
// const res = ref([
//   {
//     errorCount: '1',
//     errorMsg: 'hello'
//   }, {
//     errorCount: '2',
//     errorMsg: 'world'
//   },
// ])
let res = ref([])
const fetchData = () => {
  statistics.loading = true;
  getErrorStatisticsApi()
    .then(response => {
      // console.log('错误统计API调用成功解析 (then block):', response); // Debugging
      if (response && response.code === 1 && response.data) {
        res.value = response.data || [];
      } else if (Array.isArray(response)){
        res.value = response;
      } else {
        console.error('错误统计API调用成功解析但响应格式无法识别 (then block):', response);
        res.value = [];
        ElMessage.error('获取错误统计失败: 响应格式不正确');
      }
    })
    .catch(apiErrorOrData => {
      if (Array.isArray(apiErrorOrData)) {
        // 预期的"成功"数据（数组）通过catch块传递
        console.log('错误统计捕获的对象被识别为数组数据 (catch block):', apiErrorOrData);
        res.value = apiErrorOrData;
      } else {
        // 真正的错误
        console.warn('错误统计API调用中捕获到非预期的对象或错误 (catch block):', apiErrorOrData);
        console.error('获取错误统计数据失败 (catch block - 未能识别为数组数据):', apiErrorOrData);
        res.value = [];
        const errorMessage = typeof apiErrorOrData === 'object' && apiErrorOrData !== null && apiErrorOrData.message ? apiErrorOrData.message : '未知错误';
        ElMessage.error(`获取错误统计数据失败: ${errorMessage}`);
      }
    })
    .finally(() => {
      statistics.loading = false;
      // console.log('fetchData 完成. 错误统计数据:', res.value); // Debugging
    });
};

onMounted(() => {
  // initChart();
  fetchData();
  
  // 窗口大小变化时重新调整图表大小
  // window.addEventListener('resize', () => {
  //   if (statistics.chartInstance) {
  //     statistics.chartInstance.resize();
  //   }
  // });
});

// 在组件销毁时移除事件监听
// onUnmounted(() => {
//   window.removeEventListener('resize', () => {
//     if (statistics.chartInstance) {
//       statistics.chartInstance.resize();
//     }
//   });
//
//   if (statistics.chartInstance) {
//     statistics.chartInstance.dispose();
//   }
// });
</script>

<template>
  <ContainerPage>
    <template #title>
      <div class="flex justify-between items-center">
        <span>错误操作统计</span>
        <el-button size="small" type="primary" >刷新数据</el-button>
      </div>
    </template>
    <el-table :data="res.data">
      <el-table-column prop="errorCount" label="错误个数"/>
      <el-table-column prop="errorMsg" label="错误信息" show-overflow-tooltip/>
      <el-table-column prop="firstOccurrence" label="第一次出错位置"/>
      <el-table-column prop="lastOccurrence" label="最后出错位置"/>
      <el-table-column prop="module" label="模块"/>
      <el-table-column prop="operationType" label="接口类型"/>
    </el-table>
<!--    <el-card>-->
<!--      <div id="error-statistics-chart" style="width: 100%; height: 500px;">-->
<!--        <el-table :data="res">-->
<!--          <el-table-column v-model="res.errorCount"/>-->
<!--          <el-table-column v-model="res.errorMsg"/>-->
<!--        </el-table>-->
<!--      </div>-->
<!--    </el-card>-->
  </ContainerPage>
</template>

<style scoped>
/* 自定义样式 */
</style> 