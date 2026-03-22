import React, { useEffect, useState, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { toast } from 'react-hot-toast';
import { getKbDetail, getDocList, uploadDoc, deleteDoc, updateKb } from '../api/kb';
import { ArrowLeft, Upload, Trash2, FileText, RefreshCw, Edit3, X } from 'lucide-react';
import type { KnowledgeBase, Document as DocType, ApiResponse, PageResult } from '../types';
import '../styles/kb.css';

const KnowledgeBaseDetail: React.FC = () => {
  const { kbId } = useParams<{ kbId: string }>();
  const navigate = useNavigate();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [kb, setKb] = useState<KnowledgeBase | null>(null);
  const [docs, setDocs] = useState<DocType[]>([]);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);

  // Edit modal state
  const [showEdit, setShowEdit] = useState(false);
  const [editForm, setEditForm] = useState({ name: '', description: '', status: 'ACTIVE' });
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (kbId) {
      fetchDetail();
      fetchDocs();
    }
  }, [kbId]);

  const fetchDetail = async () => {
    try {
      const res = await getKbDetail(kbId!) as unknown as ApiResponse<KnowledgeBase>;
      setKb(res.data);
    } catch {
      toast.error('获取知识库详情失败');
    }
  };

  const fetchDocs = async () => {
    setLoading(true);
    try {
      const res = await getDocList(kbId!) as unknown as ApiResponse<PageResult<DocType>>;
      setDocs(res.data.records);
    } catch {
      toast.error('获取文档列表失败');
    } finally {
      setLoading(false);
    }
  };

  const handleUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    setUploading(true);
    try {
      await uploadDoc(kbId!, file);
      toast.success('文档上传成功，正在解析...');
      fetchDocs();
      fetchDetail();
    } catch (err: any) {
      toast.error(err.response?.data?.message || '上传失败');
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const handleDelete = async (docId: number) => {
    if (!confirm('确认删除该文档？')) return;
    try {
      await deleteDoc(kbId!, String(docId));
      toast.success('文档已删除');
      fetchDocs();
      fetchDetail();
    } catch (err: any) {
      toast.error(err.response?.data?.message || '删除失败');
    }
  };

  const statusLabel = (status: string) => {
    const map: Record<string, { text: string; cls: string }> = {
      PENDING: { text: '等待中', cls: 'status-pending' },
      PARSING: { text: '解析中', cls: 'status-parsing' },
      EMBEDDING: { text: '向量化中', cls: 'status-parsing' },
      COMPLETED: { text: '已完成', cls: 'status-completed' },
      FAILED: { text: '失败', cls: 'status-failed' },
    };
    const s = map[status] || { text: status, cls: '' };
    return <span className={`doc-status ${s.cls}`}>{s.text}</span>;
  };

  const formatSize = (bytes: number) => {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  };

  return (
    <div className="kb-page">
      <header className="page-header">
        <div className="header-title" style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
          <button className="btn-icon" onClick={() => navigate('/knowledge')} title="返回">
            <ArrowLeft size={20} />
          </button>
          <div>
            <h1>{kb?.name || '加载中...'}</h1>
            <p>{kb?.description || ''}</p>
          </div>
        </div>
        <div style={{ display: 'flex', gap: '0.5rem' }}>
          <button className="btn" onClick={fetchDocs} title="刷新">
            <RefreshCw size={16} />
          </button>
          <button
            className="btn"
            onClick={() => {
              if (kb) {
                setEditForm({ name: kb.name, description: kb.description || '', status: kb.status });
                setShowEdit(true);
              }
            }}
          >
            <Edit3 size={16} />
            <span>编辑</span>
          </button>
          <button
            className="btn btn-primary"
            onClick={() => fileInputRef.current?.click()}
            disabled={uploading}
          >
            <Upload size={16} />
            <span>{uploading ? '上传中...' : '上传文档'}</span>
          </button>
          <input
            ref={fileInputRef}
            type="file"
            accept=".pdf,.docx,.doc,.txt,.md"
            style={{ display: 'none' }}
            onChange={handleUpload}
          />
        </div>
      </header>

      {loading ? (
        <div style={{ textAlign: 'center', padding: '3rem', color: '#6b7280' }}>加载中...</div>
      ) : docs.length === 0 ? (
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', padding: '4rem', color: '#6b7280' }}>
          <FileText size={48} style={{ marginBottom: '1rem', color: '#d1d5db' }} />
          <p>暂无文档，点击"上传文档"添加</p>
        </div>
      ) : (
        <table className="doc-table">
          <thead>
            <tr>
              <th>文件名</th>
              <th>类型</th>
              <th>大小</th>
              <th>状态</th>
              <th>分块数</th>
              <th>上传时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {docs.map((doc) => (
              <tr key={doc.id}>
                <td>{doc.title}</td>
                <td>{doc.fileType}</td>
                <td>{formatSize(doc.fileSize)}</td>
                <td>{statusLabel(doc.parseStatus)}</td>
                <td>{doc.chunkCount}</td>
                <td>{new Date(doc.createdAt).toLocaleString()}</td>
                <td>
                  <button className="btn-icon btn-danger" onClick={() => handleDelete(doc.id)} title="删除">
                    <Trash2 size={16} />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {/* Edit Knowledge Base Modal */}
      {showEdit && (
        <div className="modal-overlay" onClick={() => setShowEdit(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
              <h2 style={{ margin: 0 }}>编辑知识库</h2>
              <button
                type="button"
                style={{ background: 'transparent', border: 'none', cursor: 'pointer', padding: '4px' }}
                onClick={() => setShowEdit(false)}
              >
                <X size={20} color="#6b7280" />
              </button>
            </div>
            <form onSubmit={async (e) => {
              e.preventDefault();
              setSaving(true);
              try {
                await updateKb(kbId!, editForm);
                toast.success('知识库已更新');
                setShowEdit(false);
                fetchDetail();
              } catch (err: any) {
                toast.error(err.response?.data?.message || '更新失败');
              } finally {
                setSaving(false);
              }
            }}>
              <div className="form-group">
                <label>名称</label>
                <input
                  type="text"
                  value={editForm.name}
                  onChange={(e) => setEditForm({ ...editForm, name: e.target.value })}
                  required
                />
              </div>
              <div className="form-group">
                <label>描述</label>
                <textarea
                  value={editForm.description}
                  onChange={(e) => setEditForm({ ...editForm, description: e.target.value })}
                  rows={3}
                />
              </div>
              <div className="form-group">
                <label>状态</label>
                <select
                  value={editForm.status}
                  onChange={(e) => setEditForm({ ...editForm, status: e.target.value })}
                >
                  <option value="ACTIVE">活跃</option>
                  <option value="INACTIVE">离线</option>
                </select>
              </div>
              <div className="modal-actions">
                <button type="button" className="btn" onClick={() => setShowEdit(false)} disabled={saving}>取消</button>
                <button type="submit" className="btn btn-primary" disabled={saving}>
                  {saving ? '保存中...' : '保存'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};

export default KnowledgeBaseDetail;
