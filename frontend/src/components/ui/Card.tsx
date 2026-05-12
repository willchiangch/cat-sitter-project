import React from 'react';

interface CardProps {
  children: React.ReactNode;
  onClick?: () => void;
  className?: string;
  style?: React.CSSProperties;
}

const Card: React.FC<CardProps> = ({ children, onClick, style, className }) => {
  return (
    <div 
      className={`card-layered ${className || ''}`}
      style={{
        cursor: onClick ? 'pointer' : 'default',
        padding: '1.5rem',
        marginBottom: '1.5rem',
        ...style
      }} 
      onClick={onClick}
    >
      {children}
    </div>
  );
};

export default Card;
