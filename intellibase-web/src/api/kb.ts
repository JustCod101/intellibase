import api from './index';

export const getKbList = (params?: any) => api.get('/kb', { params });
export const createKb = (data: any) => api.post('/kb', data);
export const getKbDetail = (kbId: string) => api.get(`/kb/${kbId}`);

export const uploadDoc = (kbId: string, file: File, metadata?: any) => {
  const formData = new FormData();
  formData.append('file', file);
  if (metadata) {
    formData.append('metadata', JSON.stringify(metadata));
  }
  return api.post(`/kb/${kbId}/documents`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
};

export const updateKb = (kbId: string, data: { name?: string; description?: string; status?: string }) =>
  api.put(`/kb/${kbId}`, data);

export const deleteKb = (kbId: string) => api.delete(`/kb/${kbId}`);

export const getDocList = (kbId: string, params?: any) => api.get(`/kb/${kbId}/documents`, { params });
export const deleteDoc = (kbId: string, docId: string) => api.delete(`/kb/${kbId}/documents/${docId}`);
