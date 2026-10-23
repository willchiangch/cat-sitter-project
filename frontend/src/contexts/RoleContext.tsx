import React, { createContext, useContext, useEffect, useState, useRef } from 'react';
import { shouldSeedAutoLogin } from './seedAutoLogin';

export type Role = 'sitter' | 'client' | 'admin';

interface RoleContextType {
  currentRole: Role;
  toggleRole: () => Promise<void>;
  setRole: (role: Role) => Promise<void>;
  isAuthLoading: boolean;
}

const RoleContext = createContext<RoleContextType | undefined>(undefined);

const seedLoginInFlight = new Map<Role, Promise<void>>();
const SEED_LOGIN_MAX_ATTEMPTS = 4;

const doSeedLogin = async (role: Role) => {
  let email = 'sitter@test.com';
  if (role === 'client') {
    email = 'owner@test.com';
  } else if (role === 'admin') {
    email = 'admin@test.com';
  }

  try {
    for (let attempt = 1; attempt <= SEED_LOGIN_MAX_ATTEMPTS; attempt++) {
      const response = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password: 'password' })
      });
      // 種子帳號是所有 Demo 訪客共用的：別的分頁/裝置同時登入同一個種子帳號時，後端改寫該帳號唯一一組
      // refresh token 會撞樂觀鎖回 409 MSG_DATA_CONCURRENCY_CONFLICT。這不是帳密錯誤，稍後重送即可
      if (response.status === 409 && attempt < SEED_LOGIN_MAX_ATTEMPTS) {
        await new Promise((resolve) => setTimeout(resolve, 150 * attempt + Math.random() * 150));
        continue;
      }
      const data = await response.json();
      if (data.accessToken) {
        localStorage.setItem('accessToken', data.accessToken);
        localStorage.setItem('refreshToken', data.refreshToken || '');
        localStorage.setItem('authMode', 'seed');
      }
      return;
    }
  } catch (err) {
    console.error(`自動登入 ${role} 失敗：`, err);
  }
};

/**
 * 種子帳號登入要具冪等性：同一角色已有登入請求進行中時，直接共用同一個 Promise。
 * dev 模式 <StrictMode> 會讓 RoleProvider 的 effect 跑兩次，過去同時送出兩個同帳號登入，後端
 * refresh token 的樂觀鎖讓其中一個 409；若 409 那個先回來，finishInitialCheck() 先結束 loading、
 * token 還沒寫入，RequireAuth 就把人導去 /login。跨分頁/跨裝置（mock E2E 平行 worker）同時登入同一個
 * 種子帳號也會 409，模組層級去重擋不到，由 doSeedLogin() 對 409 重送補上（Backlog 第 3 批 D4）。
 * 去重放在模組層級，與元件生命週期無關；自動登入與 /demo 切換角色同時觸發同一角色時也共用同一個請求。
 */
const loginAsRole = (role: Role): Promise<void> => {
  const pending = seedLoginInFlight.get(role);
  if (pending) return pending;
  const request = doSeedLogin(role).finally(() => seedLoginInFlight.delete(role));
  seedLoginInFlight.set(role, request);
  return request;
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
    // 種子帳號自動登入只在明確的 Demo 模式下發生（範圍與理由見 seedAutoLogin.ts）。
    // /login 等公開頁本身會送出真正的登入請求，這裡若也對同一組種子帳號並發登入，後端樂觀鎖會讓
    // 其中一個請求 409，使用者可能在帳密正確的情況下看到假的「登入失敗」。
    if (!shouldSeedAutoLogin(window.location.pathname, localStorage.getItem('authMode'))) {
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
