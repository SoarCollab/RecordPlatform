import request from "@/utils/request.js";

// 获取用户错误操作统计
export const getErrorStatisticsApi = () => {
  return request({
    url: '/system/audit/error-stats',
    method: 'get'
  });
};

// 分页查询用户日志
export const getUserLogsApi = (params) => {
  return request({
    url: '/system/logs/page',
    method: 'get',
    params
  });
};

// 获取操作日志详情
export const getLogDetailApi = (logId) => {
  return request({
    url: `/system/logs/${logId}`,
    method: 'get',
  });
}; 