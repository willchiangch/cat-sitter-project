import React from 'react';
import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

// 雖然沒用 Tailwind，但 clsx 依然好用
function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'danger-outline';
  fullWidth?: boolean;
}

const Button: React.FC<ButtonProps> = ({
  children,
  variant = 'primary',
  fullWidth = false,
  className,
  ...props
}) => {
  const variants = {
    primary: {
      backgroundColor: 'var(--color-primary)',
      color: 'white'
    },
    secondary: {
      backgroundColor: '#f3f4f6',
      color: 'var(--color-text)'
    },
    'danger-outline': {
      backgroundColor: 'transparent',
      color: 'var(--color-danger)',
      border: '1px solid var(--color-danger)'
    }
  };

  const style = {
    ...variants[variant],
    width: fullWidth ? '100%' : 'auto',
    opacity: props.disabled ? 0.5 : 1,
    cursor: props.disabled ? 'not-allowed' : 'pointer'
  };

  return (
    <button style={style as React.CSSProperties} className={cn('btn', className)} {...props}>
      {children}
    </button>
  );
};

export default Button;
