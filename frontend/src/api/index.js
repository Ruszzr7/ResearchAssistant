/**
 * axios 封装 —— 统一 baseURL、错误处理。
 * 当前仅作为集中导出点，后续可扩展拦截器（token 附加、统一错误提示）。
 */
import axios from 'axios'
import { isCancelError } from '@/utils/cancel.js'

const api = axios.create({
  baseURL: '/api',
  timeout: 120000,  // Agent 处理可能较慢，给 2 分钟
})

// 响应拦截器：统一提取 data，并将业务错误码转换为 reject
api.interceptors.response.use(
  res => {
    const data = res.data
    if (data && data.code !== 200) {
      const err = new Error(data.message || '请求失败')
      err.response = { data }
      return Promise.reject(err)
    }
    return data
  },
  err => {
    // 取消错误静默处理，避免用户主动取消时打印错误日志
    if (axios.isCancel(err) || isCancelError(err)) {
      return Promise.reject(err)
    }
    const msg = err.response?.data?.message || err.message || '请求失败'
    console.error('[API Error]', msg)
    return Promise.reject(err)
  }
)

export default api
