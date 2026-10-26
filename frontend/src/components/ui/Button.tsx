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
  style: styleProp,
  ...props
}) => {
  // fix-batch13 K6：secondary / danger-outline 過去用 global.css 沒定義的 var(--color-text) / var(--color-danger)，
  // 文字色失效改繼承父層、框線整條消失（婉拒面板「確定拒絕」看起來是沒有框的普通字）。改用已定義的設計標記：
  // secondary #2d2f2e 對 #f3f4f6 約 12:1；danger-outline #b02500 對白底 6.8:1、對 --color-surface 6.3:1
  const variants = {
    primary: {
      backgroundColor: 'var(--color-primary)',
      color: 'white'
    },
    secondary: {
      backgroundColor: '#f3f4f6',
      color: 'var(--color-on-surface)'
    },
    'danger-outline': {
      backgroundColor: 'var(--color-surface-lowest)',
      color: 'var(--color-error)',
      border: '1px solid var(--color-error)'
    }
  };

  // fix-batch13 K6：過去 {...props} 帶進來的 style 整個取代這裡算好的 style，呼叫方只要傳 style
  // （例如婉拒面板的 flex / padding），variant 的底色、文字色、框線、fullWidth、停用樣式全部失效。
  // 改為合併：variant → fullWidth → 停用 → 呼叫方 style（呼叫方優先）。
  // 帶 btn-primary 的 Button 外觀由 .btn-primary 決定（漸層、on-primary 字、:disabled），不疊 variant 與停用 inline 樣式
  const isBtnPrimary = className?.split(/\s+/).includes('btn-primary') ?? false;
  const style: React.CSSProperties = {
    ...(isBtnPrimary ? {} : variants[variant]),
    ...(fullWidth ? { width: '100%' } : {}),
    ...(isBtnPrimary
      ? {}
      : { opacity: props.disabled ? 0.5 : 1, cursor: props.disabled ? 'not-allowed' : 'pointer' }),
    ...styleProp
  };

  return (
    <button style={style} className={cn('btn', className)} {...props}>
      {children}
    </button>
  );
};

export default Button;
