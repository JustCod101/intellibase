import axios from 'axios';

/**
 * 创建 Axios 实例
 * 配置基础路径和请求超时
 */
const api = axios.create({
  // 优先使用环境变量中的 API 地址，默认为本地 8080 端口
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1',
  timeout: 10000, // 请求超时时间：10秒
});

/**
 * 请求拦截器
 * 在发送请求前统一添加认证 Token
 */
api.interceptors.request.use(
  (config) => {
    // 从本地存储获取登录时保存的 JWT Token
    const token = localStorage.getItem('accessToken');
    if (token) {
      // 如果存在 Token，则放入请求头的 Authorization 字段
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

/**
 * 响应拦截器
 * 统一处理响应数据格式及错误码
 */
api.interceptors.response.use(
  (response) => response.data, // 直接返回后端定义的统一结果对象 (code, data, message)
  (error) => {
    // 如果返回 401 (未授权)，说明 Token 已失效或未登录
    if (error.response?.status === 401) {
      localStorage.removeItem('accessToken'); // 清除本地失效 Token
      window.location.href = '/login';      // 强制跳转回登录页
    }
    return Promise.reject(error);
  }
);

export default api;
