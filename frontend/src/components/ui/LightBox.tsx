import React, { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';

interface LightBoxProps {
  isOpen: boolean;
  onClose: () => void;
  mediaUrl: string;
  caption: string;
}

const LightBox: React.FC<LightBoxProps> = ({ isOpen, onClose, mediaUrl, caption }) => {
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
    return () => setMounted(false);
  }, []);

  if (!isOpen || !mounted) return null;

  return createPortal(
    <div
      style={{
        position: 'fixed',
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
        zIndex: 1200,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'rgba(0, 0, 0, 0.85)',
        backdropFilter: 'blur(16px)',
        WebkitBackdropFilter: 'blur(16px)',
        padding: '2rem',
        animation: 'fade-in 0.2s ease forwards'
      }}
      onClick={onClose}
      data-testid="lightbox-overlay"
    >
      {/* Close Button */}
      <button
        onClick={onClose}
        style={{
          position: 'absolute',
          top: '20px',
          right: '20px',
          background: 'none',
          border: 'none',
          color: 'white',
          fontSize: '2.5rem',
          cursor: 'pointer',
          zIndex: 1210
        }}
      >
        ×
      </button>

      {/* Image Container */}
      <div
        style={{
          position: 'relative',
          maxWidth: '100%',
          maxHeight: '80vh',
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'center'
        }}
        onClick={(e) => e.stopPropagation()}
      >
        <img
          src={mediaUrl}
          alt={caption}
          style={{
            maxWidth: '100%',
            maxHeight: '80vh',
            objectFit: 'contain',
            borderRadius: 'var(--radius-lg)',
            boxShadow: '0 20px 40px rgba(0, 0, 0, 0.5)'
          }}
        />
      </div>

      {/* Caption Banner */}
      {caption && (
        <div
          style={{
            marginTop: '1.5rem',
            color: 'white',
            textAlign: 'center',
            fontSize: '1.1rem',
            maxWidth: '600px',
            fontFamily: 'var(--font-body)',
            background: 'rgba(255, 255, 255, 0.1)',
            padding: '0.8rem 1.5rem',
            borderRadius: '9999px',
            backdropFilter: 'blur(8px)'
          }}
          onClick={(e) => e.stopPropagation()}
        >
          {caption}
        </div>
      )}

      <style>{`
        @keyframes fade-in {
          from { opacity: 0; }
          to { opacity: 1; }
        }
      `}</style>
    </div>,
    document.body
  );
};

export default LightBox;
