import React, { useState, useEffect } from 'react';
import { toast } from 'react-hot-toast';
import { Users, ToggleLeft, ToggleRight, RefreshCw, Server, RotateCcw } from 'lucide-react';
import { getUsers, updateUserRole, updateUserStatus, getCacheStats, resetCacheStats } from '../api/admin';
import type { ApiResponse, PageResult, AdminUser, CacheLevelStats } from '../types';
import '../styles/admin.css';

interface CacheStatsData {
  l0_local_cache: CacheLevelStats;
  l1_semantic_cache: CacheLevelStats;
  l2_retrieval_cache: CacheLevelStats;
  l3_chunk_cache: CacheLevelStats;
  db_queries: number;
  overall_cache_hit_rate: string;
}

const AdminUsers: React.FC = () => {
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const pageSize = 10;

  // Cache stats
  const [cacheStats, setCacheStats] = useState<CacheStatsData | null>(null);
  const [cacheLoading, setCacheLoading] = useState(false);

  useEffect(() => {
    fetchUsers();
    fetchCacheStats();
  }, [page]);

  const fetchUsers = async () => {
    setLoading(true);
    try {
      const res = await getUsers(page, pageSize) as unknown as ApiResponse<PageResult<AdminUser>>;
      setUsers(res.data.records);
      setTotal(res.data.total);
    } catch {
      toast.error('获取用户列表失败');
    } finally {
      setLoading(false);
    }
  };

  const fetchCacheStats = async () => {
    setCacheLoading(true);
    try {
      const res = await getCacheStats() as unknown as ApiResponse<CacheStatsData>;
      setCacheStats(res.data);
    } catch {
      // Cache stats may not be available
    } finally {
      setCacheLoading(false);
    }
  };

  const handleRoleChange = async (userId: number, newRole: string) => {
    try {
      await updateUserRole(userId, newRole);
      toast.success('角色已更新');
      fetchUsers();
    } catch (err: any) {
      toast.error(err.response?.data?.message || '更新角色失败');
    }
  };

  const handleStatusToggle = async (userId: number, currentStatus: number | undefined) => {
    const newStatus = currentStatus === 1 ? 0 : 1;
    try {
      await updateUserStatus(userId, newStatus);
      toast.success(newStatus === 1 ? '用户已启用' : '用户已禁用');
      fetchUsers();
    } catch (err: any) {
      toast.error(err.response?.data?.message || '操作失败');
    }
  };

  const handleResetCache = async () => {
    if (!confirm('确认重置缓存统计数据？')) return;
    try {
      await resetCacheStats();
      toast.success('缓存统计已重置');
      fetchCacheStats();
    } catch {
      toast.error('重置失败');
    }
  };

  const totalPages = Math.ceil(total / pageSize);

  return (
    <div className="admin-page">
      <header className="admin-header">
        <div>
          <h1>用户管理</h1>
          <p>管理租户下的用户角色与状态</p>
        </div>
        <button className="btn" onClick={() => { fetchUsers(); fetchCacheStats(); }}>
          <RefreshCw size={16} />
          刷新
        </button>
      </header>

      {/* User Table */}
      <div className="admin-card">
        <div className="card-title">
          <Users size={20} />
          <h2>用户列表</h2>
          <span className="badge">{total} 人</span>
        </div>

        {loading ? (
          <div className="loading-text">加载中...</div>
        ) : users.length === 0 ? (
          <div className="empty-text">暂无用户数据</div>
        ) : (
          <>
            <table className="admin-table">
              <thead>
                <tr>
                  <th>ID</th>
                  <th>用户名</th>
                  <th>邮箱</th>
                  <th>角色</th>
                  <th>状态</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {users.map(user => (
                  <tr key={user.id}>
                    <td>{user.id}</td>
                    <td className="username-cell">{user.username}</td>
                    <td>{user.email || '-'}</td>
                    <td>
                      <select
                        value={user.role}
                        onChange={(e) => handleRoleChange(user.id, e.target.value)}
                        className={`role-select ${user.role === 'ADMIN' ? 'admin' : user.role === 'READONLY' ? 'readonly' : 'user'}`}
                      >
                        <option value="ADMIN">管理员</option>
                        <option value="USER">普通用户</option>
                        <option value="READONLY">只读用户</option>
                      </select>
                    </td>
                    <td>
                      <span className={`status-badge ${user.status === 1 ? 'active' : 'disabled'}`}>
                        {user.status === 1 ? '启用' : '禁用'}
                      </span>
                    </td>
                    <td>
                      <button
                        className="btn-icon"
                        onClick={() => handleStatusToggle(user.id, user.status)}
                        title={user.status === 1 ? '禁用用户' : '启用用户'}
                      >
                        {user.status === 1
                          ? <ToggleRight size={20} className="text-green" />
                          : <ToggleLeft size={20} className="text-red" />
                        }
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            {totalPages > 1 && (
              <div className="pagination">
                <button
                  className="btn btn-sm"
                  disabled={page <= 1}
                  onClick={() => setPage(p => p - 1)}
                >
                  上一页
                </button>
                <span className="page-info">{page} / {totalPages}</span>
                <button
                  className="btn btn-sm"
                  disabled={page >= totalPages}
                  onClick={() => setPage(p => p + 1)}
                >
                  下一页
                </button>
              </div>
            )}
          </>
        )}
      </div>

      {/* Cache Stats Panel */}
      <div className="admin-card">
        <div className="card-title">
          <Server size={20} />
          <h2>缓存统计</h2>
          <button className="btn btn-sm btn-danger" onClick={handleResetCache} style={{ marginLeft: 'auto' }}>
            <RotateCcw size={14} />
            重置统计
          </button>
        </div>

        {cacheLoading ? (
          <div className="loading-text">加载中...</div>
        ) : cacheStats ? (
          <div className="cache-grid">
            <CacheCard label="L0 本地缓存" data={cacheStats.l0_local_cache} color="#f59e0b" />
            <CacheCard label="L1 语义缓存" data={cacheStats.l1_semantic_cache} color="#3b82f6" />
            <CacheCard label="L2 检索缓存" data={cacheStats.l2_retrieval_cache} color="#8b5cf6" />
            <CacheCard label="L3 文档缓存" data={cacheStats.l3_chunk_cache} color="#10b981" />
            <div className="cache-summary-card">
              <div className="cache-summary-item">
                <span className="cache-summary-label">整体命中率</span>
                <span className="cache-summary-value">{cacheStats.overall_cache_hit_rate}</span>
              </div>
              <div className="cache-summary-item">
                <span className="cache-summary-label">数据库穿透</span>
                <span className="cache-summary-value">{cacheStats.db_queries} 次</span>
              </div>
            </div>
          </div>
        ) : (
          <div className="empty-text">暂无缓存统计数据</div>
        )}
      </div>
    </div>
  );
};

const CacheCard: React.FC<{ label: string; data: CacheLevelStats; color: string }> = ({ label, data, color }) => {
  const hitPercent = data.total > 0 ? (data.hits / data.total) * 100 : 0;
  return (
    <div className="cache-stat-card">
      <div className="cache-stat-header">
        <span className="cache-stat-label">{label}</span>
        <span className="cache-stat-rate" style={{ color }}>{data.hit_rate}</span>
      </div>
      <div className="cache-stat-bar-bg">
        <div className="cache-stat-bar-fill" style={{ width: `${hitPercent}%`, background: color }} />
      </div>
      <div className="cache-stat-detail">
        <span>命中 {data.hits}</span>
        <span>未命中 {data.misses}</span>
        <span>总计 {data.total}</span>
      </div>
    </div>
  );
};

export default AdminUsers;
