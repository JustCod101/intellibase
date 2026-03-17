import React, { useState, useEffect, useRef } from 'react';
import { Send, Plus, User, Bot, Loader2, Trash2, Database } from 'lucide-react';
import { toast } from 'react-hot-toast';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeHighlight from 'rehype-highlight';
import 'highlight.js/styles/github.css';
import { getConversations, getMessages, getChatStreamUrl, createConversation, deleteConversation } from '../api/chat';
import { getKbList } from '../api/kb';
import { fetchSSE } from '../utils/sse';
import type { Conversation, ChatMessage, KnowledgeBase as KbType, ApiResponse, PageResult } from '../types';
import '../styles/chat.css';

const Chat: React.FC = () => {
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [currentConv, setCurrentConv] = useState<Conversation | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const abortControllerRef = useRef<AbortController | null>(null);

  // New conversation flow state
  const [showNewChat, setShowNewChat] = useState(false);
  const [kbList, setKbList] = useState<KbType[]>([]);
  const [selectedKbId, setSelectedKbId] = useState<number | null>(null);
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    fetchConversationList();
    return () => {
      if (abortControllerRef.current) {
        abortControllerRef.current.abort();
      }
    };
  }, []);

  useEffect(() => {
    if (currentConv) {
      fetchMessages(currentConv.id);
      setShowNewChat(false);
    } else {
      setMessages([]);
    }
  }, [currentConv]);

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  const fetchConversationList = async () => {
    try {
      const response: any = await getConversations();
      setConversations(response.data.records);
      if (response.data.records.length > 0) {
        setCurrentConv(response.data.records[0]);
      }
    } catch {
      toast.error('加载会话列表失败');
    }
  };

  const fetchMessages = async (id: number) => {
    try {
      const response: any = await getMessages(id.toString());
      setMessages(response.data.records.reverse());
    } catch {
      toast.error('加载历史消息失败');
    }
  };

  const handleNewChat = async () => {
    setShowNewChat(true);
    setCurrentConv(null);
    setSelectedKbId(null);
    try {
      const res = await getKbList() as unknown as ApiResponse<PageResult<KbType>>;
      setKbList(res.data.records);
    } catch {
      toast.error('加载知识库列表失败');
    }
  };

  const handleCreateConversation = async () => {
    if (!selectedKbId) {
      toast.error('请选择一个知识库');
      return;
    }
    setCreating(true);
    try {
      const res: any = await createConversation({ kbId: selectedKbId });
      const newConv = res.data as Conversation;
      setConversations(prev => [newConv, ...prev]);
      setCurrentConv(newConv);
      setShowNewChat(false);
      toast.success('会话已创建');
    } catch (err: any) {
      toast.error(err.response?.data?.message || '创建会话失败');
    } finally {
      setCreating(false);
    }
  };

  const handleDeleteConv = async (e: React.MouseEvent, convId: number) => {
    e.stopPropagation();
    if (!confirm('确认删除该会话？')) return;
    try {
      await deleteConversation(String(convId));
      setConversations(prev => prev.filter(c => c.id !== convId));
      if (currentConv?.id === convId) {
        setCurrentConv(null);
        setMessages([]);
      }
      toast.success('会话已删除');
    } catch {
      toast.error('删除会话失败');
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
        <button className="new-chat-btn" onClick={handleNewChat}>
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
              <div className="conv-item-header">
                <p className="conv-title" title={conv.title}>{conv.title || '新对话'}</p>
                <button
                  className="conv-delete-btn"
                  onClick={(e) => handleDeleteConv(e, conv.id)}
                  title="删除会话"
                >
                  <Trash2 size={14} />
                </button>
              </div>
              <p className="conv-date">{new Date(conv.createdAt).toLocaleDateString()}</p>
            </div>
          ))}
        </div>
      </div>

      <div className="chat-main">
        <div className="chat-messages">
          {showNewChat ? (
            <div className="new-chat-panel">
              <Database size={48} style={{ color: '#d1d5db', marginBottom: '1rem' }} />
              <h2>选择知识库开始对话</h2>
              <p style={{ color: '#6b7280', marginBottom: '1.5rem' }}>请选择一个知识库作为对话的知识来源</p>
              {kbList.length === 0 ? (
                <p style={{ color: '#9ca3af' }}>暂无可用知识库，请先创建知识库</p>
              ) : (
                <>
                  <div className="kb-select-grid">
                    {kbList.map(kb => (
                      <div
                        key={kb.id}
                        className={`kb-select-card ${selectedKbId === kb.id ? 'selected' : ''}`}
                        onClick={() => setSelectedKbId(kb.id)}
                      >
                        <Database size={20} />
                        <div>
                          <p className="kb-select-name">{kb.name}</p>
                          <p className="kb-select-desc">{kb.description || '暂无描述'}</p>
                        </div>
                      </div>
                    ))}
                  </div>
                  <button
                    className="btn btn-primary"
                    style={{ marginTop: '1.5rem' }}
                    onClick={handleCreateConversation}
                    disabled={!selectedKbId || creating}
                  >
                    {creating ? '创建中...' : '开始对话'}
                  </button>
                </>
              )}
            </div>
          ) : messages.length === 0 ? (
            <div className="empty-chat">
              <Bot size={48} style={{ color: '#d1d5db', marginBottom: '1rem' }} />
              <p>向 IntelliBase 提问，开始智能探索</p>
            </div>
          ) : (
            messages.map(msg => (
              <div key={msg.id} className={`message-wrapper ${msg.role}`}>
                <div className="message-avatar">
                  {msg.role === 'user' ? <User size={20} /> : <Bot size={20} />}
                </div>
                <div className="message-content">
                  {msg.role === 'assistant' ? (
                    msg.content ? (
                      <div className="markdown-body">
                        <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeHighlight]}>
                          {msg.content}
                        </ReactMarkdown>
                      </div>
                    ) : (
                      loading && <p className="thinking">正在思考...</p>
                    )
                  ) : (
                    <p style={{ whiteSpace: 'pre-wrap' }}>{msg.content}</p>
                  )}

                  {msg.sources && msg.sources.length > 0 && (
                    <div className="message-sources">
                      <p className="sources-title">参考来源：</p>
                      <div className="sources-list">
                        {msg.sources.map((s: any, idx: number) => (
                          <div key={idx} className="source-item">
                            <span style={{ color: 'var(--primary)', fontWeight: 500, marginRight: '0.25rem' }}>[{idx + 1}]</span>
                            {s.snippet || s}
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              </div>
            ))
          )}
          <div ref={messagesEndRef} />
        </div>

        <div className="chat-input-area">
          <div className="chat-input-wrapper">
            <textarea
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder={currentConv ? '输入你的问题... (Shift + Enter 换行)' : '请先选择知识库创建会话'}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  handleSend();
                }
              }}
              rows={1}
              disabled={!currentConv}
            />
            {loading ? (
              <button
                onClick={stopGeneration}
                className="send-btn stop"
                title="停止生成"
              >
                <Loader2 size={20} className="spin" />
              </button>
            ) : (
              <button
                onClick={handleSend}
                disabled={!input.trim() || !currentConv}
                className="send-btn"
              >
                <Send size={20} />
              </button>
            )}
          </div>
          <div style={{ textAlign: 'center', marginTop: '0.5rem', fontSize: '0.75rem', color: '#9ca3af' }}>
            AI 可能会犯错，请核实重要信息。
          </div>
        </div>
      </div>
    </div>
  );
};

export default Chat;
