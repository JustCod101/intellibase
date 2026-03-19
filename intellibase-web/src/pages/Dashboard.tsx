import React, { useState, useEffect } from 'react';
import { toast } from 'react-hot-toast';
import { Database, FileText, MessageSquare, Zap, Activity, Server, CheckCircle, XCircle, Clock } from 'lucide-react';
import { getDashboardStats } from '../api/dashboard';
import type { ApiResponse } from '../types';
import '../styles/dashboard.css';

interface CacheLevel {
  hits: number;
  misses: number;
  total: number;
  hit_rate: string;
}

interface DashboardStats {
  kbCount: number;
  docTotal: number;
  docCompleted: number;
  docFailed: number;
  docProcessing: number;
  convCount: number;
  cache: {
    l1_semantic_cache: CacheLevel;
    l2_retrieval_cache: CacheLevel;
    l3_chunk_cache: CacheLevel;
    db_queries: number;
    overall_cache_hit_rate: string;
  };
}

const Dashboard: React.FC = () => {
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetchStats();
  }, []);

  const fetchStats = async () => {
    try {
      const res = await getDashboardStats() as unknown as ApiResponse<DashboardStats>;
      setStats(res.data);
    } catch {
      toast.error('获取统计数据失败');
    } finally {
      setLoading(false);
    }
  };

  if (loading) {
    return (
      <div className="dashboard-page">
        <div style={{ textAlign: 'center', padding: '4rem', color: '#6b7280' }}>加载中...</div>
      </div>
    );
  }

  if (!stats) return null;

  const cacheData = stats.cache;

  return (
    <div className="dashboard-page">
      <header className="dashboard-header">
        <div>
          <h1>控制台</h1>
          <p>IntelliBase 智能知识库平台运行概览</p>
        </div>
        <button className="btn" onClick={fetchStats}>
          <Activity size={16} />
          刷新
        </button>
      </header>

      {/* 核心指标卡片 */}
      <div className="stat-cards">
        <div className="stat-card">
          <div className="stat-icon blue">
            <Database size={24} />
          </div>
          <div className="stat-info">
            <span className="stat-value">{stats.kbCount}</span>
            <span className="stat-label">知识库</span>
          </div>
        </div>

        <div className="stat-card">
          <div className="stat-icon green">
            <FileText size={24} />
          </div>
          <div className="stat-info">
            <span className="stat-value">{stats.docTotal}</span>
            <span className="stat-label">文档总数</span>
          </div>
        </div>

        <div className="stat-card">
          <div className="stat-icon purple">
            <MessageSquare size={24} />
          </div>
          <div className="stat-info">
            <span className="stat-value">{stats.convCount}</span>
            <span className="stat-label">我的会话</span>
          </div>
        </div>

        <div className="stat-card">
          <div className="stat-icon amber">
            <Zap size={24} />
          </div>
          <div className="stat-info">
            <span className="stat-value">{cacheData.overall_cache_hit_rate}</span>
            <span className="stat-label">缓存命中率</span>
          </div>
        </div>
      </div>

      <div className="dashboard-grid">
        {/* 文档处理状态 */}
        <div className="dashboard-card">
          <h2><FileText size={18} /> 文档处理状态</h2>
          <div className="doc-status-list">
            <div className="doc-status-row">
              <div className="status-indicator">
                <CheckCircle size={16} className="text-green" />
                <span>已完成</span>
              </div>
              <span className="status-count">{stats.docCompleted}</span>
            </div>
            <div className="doc-status-row">
              <div className="status-indicator">
                <Clock size={16} className="text-blue" />
                <span>处理中</span>
              </div>
              <span className="status-count">{stats.docProcessing}</span>
            </div>
            <div className="doc-status-row">
              <div className="status-indicator">
                <XCircle size={16} className="text-red" />
                <span>失败</span>
              </div>
              <span className="status-count">{stats.docFailed}</span>
            </div>
          </div>
          {stats.docTotal > 0 && (
            <div className="progress-bar-wrap">
              <div className="progress-bar">
                <div className="progress-seg green" style={{ width: `${(stats.docCompleted / stats.docTotal) * 100}%` }} />
                <div className="progress-seg blue" style={{ width: `${(stats.docProcessing / stats.docTotal) * 100}%` }} />
                <div className="progress-seg red" style={{ width: `${(stats.docFailed / stats.docTotal) * 100}%` }} />
              </div>
            </div>
          )}
        </div>

        {/* 三级缓存命中率 */}
        <div className="dashboard-card">
          <h2><Server size={18} /> 三级缓存性能</h2>
          <div className="cache-levels">
            <CacheLevelBar label="L1 语义缓存" data={cacheData.l1_semantic_cache} color="var(--primary)" />
            <CacheLevelBar label="L2 检索缓存" data={cacheData.l2_retrieval_cache} color="#8b5cf6" />
            <CacheLevelBar label="L3 文档缓存" data={cacheData.l3_chunk_cache} color="#10b981" />
          </div>
          <div className="cache-footer">
            <span className="cache-db-label">数据库穿透次数</span>
            <span className="cache-db-value">{cacheData.db_queries}</span>
          </div>
        </div>
      </div>
    </div>
  );
};

const CacheLevelBar: React.FC<{ label: string; data: CacheLevel; color: string }> = ({ label, data, color }) => {
  const hitPercent = data.total > 0 ? (data.hits / data.total) * 100 : 0;

  return (
    <div className="cache-level">
      <div className="cache-level-header">
        <span className="cache-level-label">{label}</span>
        <span className="cache-level-rate">{data.hit_rate}</span>
      </div>
      <div className="cache-bar-bg">
        <div className="cache-bar-fill" style={{ width: `${hitPercent}%`, background: color }} />
      </div>
      <div className="cache-level-detail">
        <span>命中 {data.hits}</span>
        <span>未命中 {data.misses}</span>
      </div>
    </div>
  );
};

export default Dashboard;
