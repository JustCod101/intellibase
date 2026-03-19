import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'react-hot-toast';
import { User, Mail, Shield, Lock, LogOut, Save } from 'lucide-react';
import { getUserProfile, updateUserProfile } from '../api/user';
import type { ApiResponse } from '../types';
import '../styles/settings.css';

interface UserProfile {
  id: number;
  username: string;
  email: string;
  role: string;
}

const Settings: React.FC = () => {
  const navigate = useNavigate();
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [loading, setLoading] = useState(true);

  // 邮箱编辑
  const [email, setEmail] = useState('');
  const [savingEmail, setSavingEmail] = useState(false);

  // 密码修改
  const [oldPassword, setOldPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [savingPassword, setSavingPassword] = useState(false);

  useEffect(() => {
    fetchProfile();
  }, []);

  const fetchProfile = async () => {
    try {
      const res = await getUserProfile() as unknown as ApiResponse<UserProfile>;
      setProfile(res.data);
      setEmail(res.data.email || '');
    } catch {
      toast.error('获取用户信息失败');
    } finally {
      setLoading(false);
    }
  };

  const handleSaveEmail = async (e: React.FormEvent) => {
    e.preventDefault();
    setSavingEmail(true);
    try {
      const res = await updateUserProfile({ email }) as unknown as ApiResponse<UserProfile>;
      setProfile(res.data);
      toast.success('邮箱已更新');
    } catch (err: any) {
      toast.error(err.response?.data?.message || '更新失败');
    } finally {
      setSavingEmail(false);
    }
  };

  const handleChangePassword = async (e: React.FormEvent) => {
    e.preventDefault();
    if (newPassword !== confirmPassword) {
      toast.error('两次密码输入不一致');
      return;
    }
    setSavingPassword(true);
    try {
      await updateUserProfile({ oldPassword, newPassword });
      toast.success('密码已修改');
      setOldPassword('');
      setNewPassword('');
      setConfirmPassword('');
    } catch (err: any) {
      toast.error(err.response?.data?.message || '密码修改失败');
    } finally {
      setSavingPassword(false);
    }
  };

  const handleLogout = () => {
    localStorage.removeItem('accessToken');
    navigate('/login');
    toast.success('已退出登录');
  };

  if (loading) {
    return (
      <div className="settings-page">
        <div style={{ textAlign: 'center', padding: '4rem', color: '#6b7280' }}>加载中...</div>
      </div>
    );
  }

  return (
    <div className="settings-page">
      <header className="settings-header">
        <h1>账户设置</h1>
        <p>管理您的个人信息与安全设置</p>
      </header>

      <div className="settings-grid">
        {/* 个人信息卡片 */}
        <div className="settings-card">
          <div className="card-title">
            <User size={20} />
            <h2>个人信息</h2>
          </div>
          <div className="profile-info">
            <div className="info-row">
              <span className="info-label">用户名</span>
              <span className="info-value">{profile?.username}</span>
            </div>
            <div className="info-row">
              <span className="info-label">角色</span>
              <span className={`role-tag ${profile?.role === 'ADMIN' ? 'admin' : 'user'}`}>
                <Shield size={14} />
                {profile?.role === 'ADMIN' ? '管理员' : '普通用户'}
              </span>
            </div>
          </div>

          <form onSubmit={handleSaveEmail} className="email-form">
            <div className="form-group">
              <label><Mail size={14} /> 邮箱地址</label>
              <div className="input-with-btn">
                <input
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="请输入邮箱"
                />
                <button type="submit" className="btn btn-primary btn-sm" disabled={savingEmail}>
                  <Save size={14} />
                  {savingEmail ? '保存中...' : '保存'}
                </button>
              </div>
            </div>
          </form>
        </div>

        {/* 修改密码卡片 */}
        <div className="settings-card">
          <div className="card-title">
            <Lock size={20} />
            <h2>修改密码</h2>
          </div>
          <form onSubmit={handleChangePassword}>
            <div className="form-group">
              <label>原密码</label>
              <input
                type="password"
                value={oldPassword}
                onChange={(e) => setOldPassword(e.target.value)}
                placeholder="请输入当前密码"
                required
              />
            </div>
            <div className="form-group">
              <label>新密码</label>
              <input
                type="password"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                placeholder="至少 6 个字符"
                minLength={6}
                required
              />
            </div>
            <div className="form-group">
              <label>确认新密码</label>
              <input
                type="password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                placeholder="再次输入新密码"
                minLength={6}
                required
              />
            </div>
            <button type="submit" className="btn btn-primary" disabled={savingPassword}>
              {savingPassword ? '修改中...' : '确认修改'}
            </button>
          </form>
        </div>

        {/* 退出登录卡片 */}
        <div className="settings-card danger-zone">
          <div className="card-title">
            <LogOut size={20} />
            <h2>退出登录</h2>
          </div>
          <p className="danger-desc">退出后需要重新输入用户名和密码登录。</p>
          <button className="btn btn-danger" onClick={handleLogout}>
            <LogOut size={16} />
            退出登录
          </button>
        </div>
      </div>
    </div>
  );
};

export default Settings;
