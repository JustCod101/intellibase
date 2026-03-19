import api from './index';

export const getDashboardStats = () => api.get('/dashboard/stats');
