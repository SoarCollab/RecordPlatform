import request from "@/utils/request.js";

export const loginApi = (data) => {
  return request.post("/auth/login", data, {
    skipToken: true,
    cusToken: true,
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded'
    }
  })
}
// cusToken monitor/list

export const getMonitorListApi = () => {
  return request.get("/monitor/list", {
    cusToken: true
  })
}

// monitor/details
export const getMonitorDetailsApi = (id) => {
  return request.get(`/monitor/details`, {
    params: {
      clientId: id
    },
    cusToken: true
  })
}
// monitor/runtime_history
export const getMonitorRuntimeHistoryApi = (id) => {
  return request.get(`/monitor/runtime_history`, {
    params: {
      clientId: id
    },
    cusToken: true
  })
}
// monitor/runtime_now
export const getMonitorRuntimeNowApi = (id) => {
  return request.get(`/monitor/runtime_now`, {
    params: {
      clientId: id
    },
    cusToken: true
  })
}

// /monitor/rename id name
export const renameMonitorApi = (data) => {
  return request.post(`/monitor/rename`, data, {
    cusToken: true
  })
}

// /monitor/node id node location
export const resetMonitorNodeApi = (data) => {
  return request.post(`/monitor/node`, data,{
    cusToken: true
  })
}

// /monitor/delete
export const deleteMonitorApi = (id) => {
  return request.get(`/monitor/delete`,{
    params: {
      clientId: id
    },
    cusToken: true
  })
}

// /monitor/ssh
export const sshMonitorApi = (id) => {
  return request.get(`/monitor/ssh`, {
    params: {
      clientId: id
    },
    cusToken: true
  })
}

// /monitor/ssh-save
export const saveSSHMonitorApi = (data) => {
  return request.post(`/monitor/ssh-save`, data, {
    cusToken: true
  })
}
// /monitor/register
export const registerMonitorApi = () => {
  return request.get(`/monitor/register`, {
    cusToken: true
  })
}