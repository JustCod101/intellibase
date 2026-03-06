import React, { useState, useEffect, useRef } from 'react';
import { Send, Plus, User, Bot, Paperclip, Loader2 } from 'lucide-react';
import { toast } from 'react-hot-toast';
import { getConversations, getMessages, getChatStreamUrl } from '../api/chat';
import { fetchSSE } from '../utils/sse';
import type { Conversation, ChatMessage } from '../types';
import '../styles/chat.css';

const Chat: React.FC = () => {
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [currentConv, setCurrentConv] = useState<Conversation | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const abortControllerRef = useRef<AbortController | null>(null);

  useEffect(() => {
    fetchConversations();
    return () => {
      if (abortControllerRef.current) {
        abortControllerRef.current.abort();
      }
    };
  }, []);

  useEffect(() => {
    if (currentConv) {
      fetchMessages(currentConv.id);
    }
  }, [currentConv]);

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  const fetchConversations = async () => {
    try {
      const response: any = await getConversations();
      setConversations(response.data.records);
      if (response.data.records.length > 0) {
        setCurrentConv(response.data.records[0]);
      }
    } catch (err: any) {
      toast.error('加载会话列表失败');
    }
  };

  const fetchMessages = async (id: number) => {
    try {
      const response: any = await getMessages(id.toString());
      setMessages(response.data.records.reverse());
    } catch (err) {
      toast.error('加载历史消息失败');
    }
  };

  const handleSend = async () => {
    if (!input.trim() || !currentConv || loading) return;

    const userQuestion = input.trim();
    const userMsgId = Date.now();
    const assistantMsgId = Date.now() + 1;

    setMessages(prev => [...prev, { 
      id: userMsgId, 
      conversationId: currentConv.id,
      role: 'user', 
      content: userQuestion 
    }]);
    
    setInput('');
    setLoading(true);

    setMessages(prev => [...prev, { 
      id: assistantMsgId, 
      conversationId: currentConv.id,
      role: 'assistant', 
      content: '' 
    }]);

    const url = getChatStreamUrl(currentConv.id.toString(), userQuestion);
    
    abortControllerRef.current = new AbortController();

    await fetchSSE(url, {
      signal: abortControllerRef.current.signal,
      onMessage: ({ event, data }) => {
        if (event === 'token' || !event) {
          setMessages(prev => prev.map(msg => 
            msg.id === assistantMsgId ? { ...msg, content: msg.content + data } : msg
          ));
        } else if (event === 'sources') {
          setMessages(prev => prev.map(msg => 
            msg.id === assistantMsgId ? { ...msg, sources: Array.isArray(data) ? data : [data] } : msg
          ));
        }
      },
      onError: (err) => {
        console.error('SSE Error:', err);
        toast.error('生成回答时发生错误');
        setLoading(false);
      },
      onDone: () => {
        setLoading(false);
      }
    });
  };

  const stopGeneration = () => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      setLoading(false);
    }
  };

  return (
    <div className="chat-page">
      <div className="chat-sidebar">
        <button className="new-chat-btn" onClick={() => setCurrentConv(null)}>
          <Plus size={18} />
          <span>新会话</span>
        </button>
        <div className="conv-list">
          {conversations.map(conv => (
            <div 
              key={conv.id} 
              className={`conv-item ${currentConv?.id === conv.id ? 'active' : ''}`}
              onClick={() => setCurrentConv(conv)}
            >
              <p className="conv-title" title={conv.title}>{conv.title || '新对话'}</p>
              <p className="conv-date">{new Date(conv.createdAt).toLocaleDateString()}</p>
            </div>
          ))}
        </div>
      </div>

      <div className="chat-main flex-1 flex flex-col relative h-full">
        <div className="chat-messages flex-1 overflow-y-auto p-4 md:p-8 space-y-6">
          {messages.length === 0 ? (
            <div className="h-full flex flex-col items-center justify-center text-gray-400">
              <Bot size={48} className="mb-4 text-gray-300" />
              <p>向 IntelliBase 提问，开始智能探索</p>
            </div>
          ) : (
            messages.map(msg => (
              <div key={msg.id} className={`message-wrapper flex gap-4 max-w-4xl mx-auto w-full ${msg.role}`}>
                <div className={`message-avatar w-10 h-10 rounded-full flex items-center justify-center shrink-0 ${msg.role === 'user' ? 'bg-blue-100 text-blue-600' : 'bg-green-100 text-green-600'}`}>
                  {msg.role === 'user' ? <User size={20} /> : <Bot size={20} />}
                </div>
                <div className="message-content flex-1 text-base leading-relaxed text-gray-800">
                  <p className="whitespace-pre-wrap">{msg.content || (loading && msg.role === 'assistant' ? '正在思考...' : '')}</p>
                  
                  {msg.sources && msg.sources.length > 0 && (
                    <div className="message-sources mt-4 p-4 bg-gray-50 rounded-lg border-l-4 border-blue-500">
                      <p className="sources-title text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">参考来源：</p>
                      <div className="space-y-2">
                        {msg.sources.map((s: any, idx: number) => (
                          <div key={idx} className="source-item text-sm text-gray-600 bg-white p-2 rounded border border-gray-100 shadow-sm">
                            <span className="text-blue-500 font-medium mr-1">[{idx + 1}]</span>
                            {s.snippet}
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              </div>
            ))
          )}
          <div ref={messagesEndRef} className="h-4" />
        </div>

        <div className="chat-input-area p-4 md:p-6 bg-white border-t border-gray-100">
          <div className="chat-input-wrapper max-w-4xl mx-auto flex items-end gap-3 p-3 border border-gray-200 rounded-xl shadow-sm bg-white focus-within:ring-2 focus-within:ring-blue-100 focus-within:border-blue-300 transition-all">
            <button className="attach-btn p-2 text-gray-400 hover:text-gray-600 hover:bg-gray-100 rounded-lg transition-colors">
              <Paperclip size={20} />
            </button>
            <textarea 
              className="flex-1 max-h-[200px] min-h-[44px] resize-none outline-none py-2 bg-transparent text-gray-700 placeholder-gray-400"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder="输入你的问题... (Shift + Enter 换行)"
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  handleSend();
                }
              }}
              rows={1}
            />
            {loading ? (
              <button 
                onClick={stopGeneration}
                className="send-btn p-2 text-red-500 hover:bg-red-50 rounded-lg transition-colors flex items-center justify-center"
                title="停止生成"
              >
                <Loader2 size={20} className="animate-spin" />
              </button>
            ) : (
              <button 
                onClick={handleSend} 
                disabled={!input.trim() || !currentConv}
                className={`send-btn p-2 rounded-lg transition-colors flex items-center justify-center ${!input.trim() || !currentConv ? 'text-gray-300' : 'text-blue-600 hover:bg-blue-50'}`}
              >
                <Send size={20} />
              </button>
            )}
          </div>
          <div className="text-center mt-2 text-xs text-gray-400">
            AI 可能会犯错，请核实重要信息。
          </div>
        </div>
      </div>
    </div>
  );
};

export default Chat;
