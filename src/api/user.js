import request from "@/utils/request";

/**
 * 登录
 * @param data
 * @returns {Promise<axios.AxiosResponse<any>>}
 */
export const loginApi = (data) => {
  return request.post("/auth/login", data, {
    skipToken: true,
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded'
    }
  })
}
/**
 * 登出
 * @returns {Promise<axios.AxiosResponse<any>>}
 */
export const logoutApi = () => {
  return request.post("/auth/logout" , {},{
    skipToken: true,
  })
}
/**
 * 获取用户信息
 * @returns {Promise<axios.AxiosResponse<any>>}
 */
export const getUserApi = () => {
  return request.get("/user/info")
}
/**
 * 获取邮箱验证码
 * @param email
 * @param type
 * @returns {Promise<axios.AxiosResponse<any>>}
 */
export const getEmailCodeApi = (email, type) => {
  return request.get(`/auth/ask-code`, {
    params: {
      email,
      type
    },
    skipToken: true
  })
}
/**
 * 注册
 * @param data
 * @returns {Promise<axios.AxiosResponse<any>>}
 */
export const registerApi = (data) => {
  return request.post("/auth/register", data,{
    skipToken: true
  })
}
/**
 * 重置密码确认
 * @param data
 * @returns {Promise<axios.AxiosResponse<any>>}
 */
export const resetConfirmApi = (data) => {
  return request.post("/auth/reset-confirm", data,{
    skipToken: true
  })
}
/**
 * 重置密码
 * @param data
 * @returns {Promise<axios.AxiosResponse<any>>}
 */
export const resetPasswordApi = (data) => {
  return request.post("/auth/reset-password", data,{
    skipToken: true
  })
}

/**
 * 修改密码
 * @param data
 * @returns {Promise<axios.AxiosResponse<any>>}
 */
export const changePassword = (data) => {
  return request.post("/user/change-password", data)
}

export const changeEmail = (data) => {
  return request.post("/user/modify-email", data)
}