import React, { createContext, useContext, useEffect, useState } from 'react';

type Role = 'sitter' | 'client';

interface RoleContextType {
  currentRole: Role;
  toggleRole: () => void;
}

const RoleContext = createContext<RoleContextType | undefined>(undefined);

export const RoleProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [currentRole, setCurrentRole] = useState<Role>(
    (localStorage.getItem('userRole') as Role) || 'sitter'
  );

  useEffect(() => {
    // 核心防雷：動態切換 <html> 標籤的 data-theme
    document.documentElement.setAttribute('data-theme', currentRole);
    localStorage.setItem('userRole', currentRole);
  }, [currentRole]);

  const toggleRole = () => {
    setCurrentRole((prev) => (prev === 'sitter' ? 'client' : 'sitter'));
  };

  return (
    <RoleContext.Provider value={{ currentRole, toggleRole }}>{children}</RoleContext.Provider>
  );
};

// eslint-disable-next-line react-refresh/only-export-components
export const useRole = () => {
  const context = useContext(RoleContext);
  if (!context) {
    throw new Error('useRole must be used within a RoleProvider');
  }
  return context;
};
