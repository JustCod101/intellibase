import api from './index';

export const getUserProfile = () => api.get('/user/profile');

export const updateUserProfile = (data: {
  email?: string;
  oldPassword?: string;
  newPassword?: string;
}) => api.put('/user/profile', data);
