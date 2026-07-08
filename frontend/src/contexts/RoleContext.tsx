import React, { createContext, useContext, useEffect, useState, useRef } from 'react';

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
      localStorage.setItem('authMode', 'seed');
    }
  } catch (err) {
    console.error(`自動登入 ${role} 失敗：`, err);
  }
};

export const RoleProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [currentRole, setCurrentRole] = useState<Role>(
    (localStorage.getItem('userRole') as Role) || 'sitter'
  );
  const skipAutoLoginRef = useRef(false);
  const isInitialAuthCheckRef = useRef(true);
  // 剛掛載時要等第一次自動登入 (或確認已是 manual session) 完成，
  // 避免 RequireAuth 在 token 還沒寫入 localStorage 前就把使用者導去 /login
  const [isAuthLoading, setIsAuthLoading] = useState(true);

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', currentRole);
    localStorage.setItem('userRole', currentRole);

    const finishInitialCheck = () => {
      if (isInitialAuthCheckRef.current) {
        isInitialAuthCheckRef.current = false;
        setIsAuthLoading(false);
      }
    };

    if (skipAutoLoginRef.current) {
      skipAutoLoginRef.current = false;
      finishInitialCheck();
      return;
    }
    // 已透過正式 /login 頁面登入時，不要用種子帳號蓋掉真實 session
    if (localStorage.getItem('authMode') === 'manual') {
      finishInitialCheck();
      return;
    }
    loginAsRole(currentRole).catch(console.error).finally(finishInitialCheck);
  }, [currentRole]);

  const setRole = async (role: Role) => {
    // Demo 專案為切換不同獨立種子使用者 (Sitter, Owner, Admin)，直接執行獨立登入
    await loginAsRole(role);
    skipAutoLoginRef.current = true;
    setCurrentRole(role);
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
