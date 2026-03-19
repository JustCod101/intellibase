import React from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import { Toaster } from 'react-hot-toast';
import Layout from './components/Layout/Layout';
import Login from './pages/Login';
import Register from './pages/Register';
import KnowledgeBase from './pages/KnowledgeBase';
import KnowledgeBaseDetail from './pages/KnowledgeBaseDetail';
import Chat from './pages/Chat';
import Settings from './pages/Settings';
import Dashboard from './pages/Dashboard';
import './styles/global.css';

/**
 * 路由守卫组件
 * 检查用户是否已登录，未登录则重定向至登录页
 */
const ProtectedRoute = ({ children }: { children: React.ReactNode }) => {
  const token = localStorage.getItem('accessToken');
  
  if (!token) {
    return <Navigate to="/login" replace />;
  }

  return <Layout>{children}</Layout>;
};

function App() {
  return (
    <>
      <Toaster position="top-right" />
      <Router>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />
          
          <Route 
            path="/dashboard" 
            element={
              <ProtectedRoute>
                <Dashboard />
              </ProtectedRoute>
            } 
          />
          
          <Route 
            path="/knowledge" 
            element={
              <ProtectedRoute>
                <KnowledgeBase />
              </ProtectedRoute>
            } 
          />
          
          <Route
            path="/knowledge/:kbId"
            element={
              <ProtectedRoute>
                <KnowledgeBaseDetail />
              </ProtectedRoute>
            }
          />

          <Route
            path="/chat" 
            element={
              <ProtectedRoute>
                <Chat />
              </ProtectedRoute>
            } 
          />

          <Route
            path="/settings"
            element={
              <ProtectedRoute>
                <Settings />
              </ProtectedRoute>
            }
          />

          <Route path="/" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </Router>
    </>
  );
}

export default App;
