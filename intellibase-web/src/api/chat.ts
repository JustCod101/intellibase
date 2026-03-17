import api from './index';

export const getConversations = (params?: any) => api.get('/chat/conversations', { params });
export const getMessages = (conversationId: string, params?: any) =>
  api.get(`/chat/conversations/${conversationId}/messages`, { params });

export const createConversation = (data: { kbId: number; title?: string }) =>
  api.post('/chat/conversations', data);

export const deleteConversation = (conversationId: string) =>
  api.delete(`/chat/conversations/${conversationId}`);

export const getChatStreamUrl = (conversationId: string, question: string) => {
  const baseUrl = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1';
  const token = localStorage.getItem('accessToken');
  return `${baseUrl}/chat/stream?conversationId=${conversationId}&question=${encodeURIComponent(question)}&token=${token}`;
};
