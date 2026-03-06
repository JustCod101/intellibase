import React, { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { toast } from 'react-hot-toast';
import { register } from '../api/auth';
import '../styles/auth.css';

const Register: React.FC = () => {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [email, setEmail] = useState('');
  const [loading, setLoading] = useState(false);
  
  const navigate = useNavigate();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);

    try {
      await register({ username, password, email });
      toast.success('注册成功，请登录！');
      navigate('/login');
    } catch (err: any) {
      toast.error(err.response?.data?.message || '注册失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-container">
      <div className="auth-card">
        <h2>创建账号</h2>
        <p className="auth-subtitle">加入 IntelliBase，开启您的智能知识库之旅</p>
        
        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label>邮箱地址</label>
            <input 
              type="email" 
              value={email} 
              onChange={(e) => setEmail(e.target.value)} 
              placeholder="请输入您的邮箱"
              required
            />
          </div>
          <div className="form-group">
            <label>用户名</label>
            <input 
              type="text" 
              value={username} 
              onChange={(e) => setUsername(e.target.value)} 
              placeholder="设置您的用户名"
              required
            />
          </div>
          <div className="form-group">
            <label>密码</label>
            <input 
              type="password" 
              value={password} 
              onChange={(e) => setPassword(e.target.value)} 
              placeholder="设置您的登录密码"
              required
            />
          </div>
          <button type="submit" className="btn btn-primary btn-block" disabled={loading}>
            {loading ? '注册中...' : '立即注册'}
          </button>
        </form>
        
        <p className="auth-footer">
          已有账号？ <Link to="/login">返回登录</Link>
        </p>
      </div>
    </div>
  );
};

export default Register;
