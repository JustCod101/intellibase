import api from './index';

// ===== 用户管理 (AdminController) =====

export const getUsers = (page = 1, size = 10) =>
  api.get('/admin/users', { params: { page, size } });

export const updateUserRole = (id: number, role: string) =>
  api.put(`/admin/users/${id}/role`, { role });

export const updateUserStatus = (id: number, status: number) =>
  api.put(`/admin/users/${id}/status`, { status });

// ===== 缓存统计 (CacheStatsController) =====

export const getCacheStats = () => api.get('/admin/cache/stats');

export const resetCacheStats = () => api.post('/admin/cache/stats/reset');
