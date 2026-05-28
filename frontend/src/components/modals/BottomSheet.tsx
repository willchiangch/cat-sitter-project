import React from 'react';
import { createPortal } from 'react-dom';

interface BottomSheetProps {
  isOpen: boolean;
  onClose: () => void;
  title: string;
  children: React.ReactNode;
}

const BottomSheet: React.FC<BottomSheetProps> = ({ isOpen, onClose, title, children }) => {
  if (!isOpen) return null;

  return createPortal(
    <div
      style={{
        position: 'fixed',
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
        zIndex: 1000,
        display: 'flex',
        alignItems: 'flex-end',
        justifyContent: 'center',
        background: 'rgba(45, 47, 46, 0.4)',
        backdropFilter: 'blur(4px)',
        WebkitBackdropFilter: 'blur(4px)',
        transition: 'opacity 0.3s ease'
      }}
      onClick={onClose}
      data-testid="bottom-sheet-backdrop"
    >
      <div
        style={{
          width: '100%',
          maxWidth: 'var(--max-width-mobile)',
          background: 'rgba(255, 255, 255, 0.85)',
          backdropFilter: 'blur(20px)',
          WebkitBackdropFilter: 'blur(20px)',
          borderTopLeftRadius: 'var(--radius-xl)',
          borderTopRightRadius: 'var(--radius-xl)',
          boxShadow: 'var(--shadow-ambient)',
          padding: '2rem 1.5rem',
          maxHeight: '80vh',
          display: 'flex',
          flexDirection: 'column',
          animation: 'slide-up 0.3s cubic-bezier(0.16, 1, 0.3, 1) forwards',
          border: '1px solid var(--color-outline-variant)'
        }}
        onClick={(e) => e.stopPropagation()}
      >
        {/* Handle Bar */}
        <div
          style={{
            width: '40px',
            height: '4px',
            background: 'var(--color-on-surface-variant)',
            opacity: 0.2,
            borderRadius: '2px',
            margin: '0 auto 1.5rem auto'
          }}
        />

        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            marginBottom: '1.5rem'
          }}
        >
          <h2
            style={{
              margin: 0,
              fontSize: '1.5rem',
              fontFamily: 'var(--font-display)',
              color: 'var(--color-on-surface)'
            }}
          >
            {title}
          </h2>
          <button
            onClick={onClose}
            style={{
              background: 'none',
              border: 'none',
              fontSize: '1.5rem',
              cursor: 'pointer',
              color: 'var(--color-on-surface-variant)',
              padding: '0.2rem'
            }}
          >
            ×
          </button>
        </div>

        <div style={{ flex: 1, overflowY: 'auto', paddingBottom: '2rem' }}>{children}</div>
      </div>
    </div>,
    document.body
  );
};

export default BottomSheet;
