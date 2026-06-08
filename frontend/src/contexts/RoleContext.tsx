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
  const isAuthLoading = false;

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', currentRole);
    localStorage.setItem('userRole', currentRole);
    if (skipAutoLoginRef.current) {
      skipAutoLoginRef.current = false;
      return;
    }
    loginAsRole(currentRole).catch(console.error);
  }, [currentRole]);

  const setRole = async (role: Role) => {
    const token = localStorage.getItem('accessToken');
    if (token) {
      try {
        const response = await fetch('/api/auth/switch-role', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${token}`
          },
          body: JSON.stringify({ targetRole: role.toUpperCase() })
        });

        if (response.ok) {
          const data = await response.json();
          if (data.accessToken) {
            localStorage.setItem('accessToken', data.accessToken);
            localStorage.setItem('refreshToken', data.refreshToken || '');
            skipAutoLoginRef.current = true;
          }
        } else {
          console.warn(`切換角色 API 返回錯誤，執行舊的登入 Fallback`);
        }
      } catch (err) {
        console.warn(`切換角色 API 失敗，執行舊的登入 Fallback：`, err);
      }
    }
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
