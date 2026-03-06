import React, { useEffect, useState } from 'react';
import { toast } from 'react-hot-toast';
import { getKbList, createKb } from '../api/kb';
import { Plus, Search, Book, Database, Clock, X } from 'lucide-react';
import type { KnowledgeBase as KbType, ApiResponse, PageResult } from '../types';
import '../styles/kb.css';

const KnowledgeBase: React.FC = () => {
  const [kbList, setKbList] = useState<KbType[]>([]);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  
  const [newKb, setNewKb] = useState({ 
    name: '', 
    description: '', 
    embeddingModel: 'text-embedding-3-small' 
  });

  useEffect(() => {
    fetchKbList();
  }, []);

  const fetchKbList = async (keyword?: string) => {
    setLoading(true);
    try {
      const response = await getKbList({ keyword }) as unknown as ApiResponse<PageResult<KbType>>;
      setKbList(response.data.records);
    } catch (err: any) {
      toast.error(err.response?.data?.message || '获取知识库列表失败');
    } finally {
      setLoading(false);
    }
  };

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    fetchKbList(searchQuery);
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newKb.name.trim()) {
      toast.error('请输入知识库名称');
      return;
    }

    setSubmitting(true);
    try {
      await createKb({
        ...newKb,
        chunkStrategy: { size: 512, overlap: 64 }
      });
      toast.success('创建知识库成功');
      setShowModal(false);
      setNewKb({ name: '', description: '', embeddingModel: 'text-embedding-3-small' });
      fetchKbList();
    } catch (err: any) {
      toast.error(err.response?.data?.message || '创建知识库失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="kb-page">
      <header className="page-header">
        <div className="header-title">
          <h1>知识库管理</h1>
          <p>管理您的文档集合与向量化配置</p>
        </div>
        <button className="btn btn-primary" onClick={() => setShowModal(true)}>
          <Plus size={18} />
          <span>新建知识库</span>
        </button>
      </header>

      <div className="search-bar">
        <form className="search-input-wrapper" onSubmit={handleSearch}>
          <Search size={18} className="search-icon" />
          <input 
            type="text" 
            placeholder="搜索知识库..." 
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
          />
        </form>
      </div>

      {loading ? (
        <div style={{ display: 'flex', justifyContent: 'center', padding: '2rem', color: '#6b7280' }}>
          正在加载数据...
        </div>
      ) : kbList.length === 0 ? (
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', padding: '4rem', color: '#6b7280' }}>
          <Database size={48} style={{ marginBottom: '1rem', color: '#d1d5db' }} />
          <p>暂无知识库数据</p>
        </div>
      ) : (
        <div className="kb-grid">
          {kbList.map((kb) => (
            <div key={kb.id} className="kb-card">
              <div className="kb-card-header">
                <div className="kb-icon">
                  <Database size={24} />
                </div>
                <div className={`kb-status-tag ${kb.status === 'ACTIVE' ? 'active' : ''}`}>
                  {kb.status === 'ACTIVE' ? '活跃' : '离线'}
                </div>
              </div>
              <h3 className="kb-name">{kb.name}</h3>
              <p className="kb-desc" title={kb.description}>{kb.description || '暂无描述。'}</p>
              <div className="kb-meta">
                <div className="meta-item">
                  <Book size={14} />
                  <span>{kb.docCount} 篇文档</span>
                </div>
                <div className="meta-item">
                  <Clock size={14} />
                  <span>{new Date(kb.createdAt).toLocaleDateString()}</span>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {showModal && (
        <div className="modal-overlay" onClick={() => setShowModal(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
              <h2 style={{ margin: 0 }}>创建知识库</h2>
              <button 
                type="button" 
                style={{ background: 'transparent', border: 'none', cursor: 'pointer', padding: '4px' }} 
                onClick={() => setShowModal(false)}
              >
                <X size={20} color="#6b7280" />
              </button>
            </div>
            <form onSubmit={handleCreate}>
              <div className="form-group">
                <label>名称 <span style={{ color: 'red' }}>*</span></label>
                <input 
                  type="text" 
                  value={newKb.name} 
                  onChange={(e) => setNewKb({...newKb, name: e.target.value})} 
                  placeholder="请输入知识库名称"
                  maxLength={50}
                  required
                />
              </div>
              <div className="form-group">
                <label>描述</label>
                <textarea 
                  value={newKb.description} 
                  onChange={(e) => setNewKb({...newKb, description: e.target.value})} 
                  placeholder="请输入知识库描述（可选）"
                  rows={3}
                  maxLength={200}
                />
              </div>
              <div className="form-group">
                <label>向量模型</label>
                <select 
                  value={newKb.embeddingModel} 
                  onChange={(e) => setNewKb({...newKb, embeddingModel: e.target.value})}
                >
                  <option value="text-embedding-3-small">text-embedding-3-small (推荐, 1536维)</option>
                  <option value="text-embedding-3-large">text-embedding-3-large (高精度, 3072维)</option>
                </select>
              </div>
              <div className="modal-actions">
                <button type="button" className="btn" onClick={() => setShowModal(false)} disabled={submitting}>
                  取消
                </button>
                <button type="submit" className="btn btn-primary" disabled={submitting}>
                  {submitting ? '创建中...' : '确定创建'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};

export default KnowledgeBase;
