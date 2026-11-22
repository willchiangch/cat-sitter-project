import { createContext, useContext } from 'react';

/**
 * 角色 context 物件與 hook（與 RoleProvider 分檔：react-refresh 要求元件檔只匯出元件）。
 */
export type Role = 'sitter' | 'client' | 'admin';

export interface RoleContextType {
  currentRole: Role;
  toggleRole: () => Promise<void>;
  setRole: (role: Role) => Promise<void>;
  isAuthLoading: boolean;
}

export const RoleContext = createContext<RoleContextType | undefined>(undefined);

export const useRole = () => {
  const context = useContext(RoleContext);
  if (!context) {
    throw new Error('useRole must be used within a RoleProvider');
  }
  return context;
};
