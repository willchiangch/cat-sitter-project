import React, { createContext, useContext, useEffect, useState } from 'react';

export type Role = 'sitter' | 'client' | 'admin';

interface RoleContextType {
  currentRole: Role;
  toggleRole: () => Promise<void>;
  setRole: (role: Role) => Promise<void>;
  isAuthLoading: boolean;
}

const RoleContext = createContext<RoleContextType | undefined>(undefined);

const loginAsRole = async (role: Role) => {
  let email = 'sitter@test.com';
  if (role === 'client') {
    email = 'owner@test.com';
  } else if (role === 'admin') {
    email = 'admin@test.com';
  }
  
  try {
    const response = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password: 'password' })
    });
    const data = await response.json();
    if (data.accessToken) {
      localStorage.setItem('accessToken', data.accessToken);
      localStorage.setItem('refreshToken', data.refreshToken || '');
    }
  } catch (err) {
    console.error(`自動登入 ${role} 失敗：`, err);
  }
};

export const RoleProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [currentRole, setCurrentRole] = useState<Role>(
    (localStorage.getItem('userRole') as Role) || 'sitter'
  );
  const [isAuthLoading, setIsAuthLoading] = useState(true);

  // 初始化登入與角色監聽
  useEffect(() => {
    const initAuth = async () => {
      setIsAuthLoading(true);
      document.documentElement.setAttribute('data-theme', currentRole);
      localStorage.setItem('userRole', currentRole);
      // 無論是否有 Token，在初始化時都根據 localStorage 的角色動態登入對應的種子帳號
      await loginAsRole(currentRole);
      setIsAuthLoading(false);
    };
    initAuth();
  }, [currentRole]);

  const setRole = async (role: Role) => {
    setIsAuthLoading(true);
    await loginAsRole(role);
    setCurrentRole(role);
    setIsAuthLoading(false);
  };

  const toggleRole = async () => {
    const nextRole = currentRole === 'sitter' ? 'client' : 'sitter';
    await setRole(nextRole);
  };

  return (
    <RoleContext.Provider value={{ currentRole, toggleRole, setRole, isAuthLoading }}>
      {children}
    </RoleContext.Provider>
  );
};

export const useRole = () => {
  const context = useContext(RoleContext);
  if (!context) {
    throw new Error('useRole must be used within a RoleProvider');
  }
  return context;
};
