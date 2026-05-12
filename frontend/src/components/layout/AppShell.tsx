import React from 'react';

interface AppShellProps {
  children: React.ReactNode;
}

const AppShell: React.FC<AppShellProps> = ({ children }) => {
  return (
    <div
      style={{
        maxWidth: 'var(--max-width-mobile)',
        margin: '0 auto',
        height: '100vh',
        backgroundColor: 'var(--color-surface)',
        position: 'relative',
        display: 'flex',
        flexDirection: 'column',
        boxShadow: 'var(--shadow-ambient)',
        overflow: 'hidden'
      }}
    >
      <main
        style={{
          flex: 1,
          overflowY: 'auto',
          paddingBottom: '80px', // 為 BottomNav 預留空間
          padding: '0 1.5rem'
        }}
      >
        {children}
      </main>
    </div>
  );
};

export default AppShell;
