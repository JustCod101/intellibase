import api from './index';

export const getConversations = (params?: any) => api.get('/chat/conversations', { params });
export const getMessages = (conversationId: string, params?: any) => 
  api.get(`/chat/conversations/${conversationId}/messages`, { params });

export const getChatStreamUrl = (conversationId: string, question: string) => {
  const token = localStorage.getItem('accessToken');
  return `http://localhost:8080/api/v1/chat/stream?conversationId=${conversationId}&question=${encodeURIComponent(question)}&token=${token}`;
};
