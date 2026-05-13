import React from 'react';

interface CardProps extends React.HTMLAttributes<HTMLDivElement> {
  children: React.ReactNode;
  onClick?: () => void;
  className?: string;
  style?: React.CSSProperties;
}

const Card: React.FC<CardProps> = ({ children, onClick, style, className, ...props }) => {
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
      {...props}
    >
      {children}
    </div>
  );
};

export default Card;
