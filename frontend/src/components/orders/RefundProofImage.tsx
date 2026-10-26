import React from 'react';
import { useRefundProofSignedUrlQuery } from '../../hooks/useOrders';

interface RefundProofImageProps {
  orderId: string;
  alt: string;
  style?: React.CSSProperties;
}

// 退款憑證是私有 GCS 物件，只用後端簽發的短效網址以 <img> 顯示，不做成可點的外部連結 (SD-016 §2.5)
const RefundProofImage: React.FC<RefundProofImageProps> = ({ orderId, alt, style }) => {
  const { data, isLoading, isError } = useRefundProofSignedUrlQuery(orderId, true);

  if (isLoading) {
    return (
      <div data-testid="refund-proof-loading" style={{ fontSize: '0.8rem' }}>
        憑證載入中...
      </div>
    );
  }
  if (isError || !data) {
    return (
      <div
        data-testid="refund-proof-error"
        style={{ fontSize: '0.8rem', color: 'var(--color-error)' }}
      >
        退款憑證圖片無法載入，請重新整理頁面
      </div>
    );
  }
  return <img data-testid="refund-proof-preview" src={data.signedUrl} alt={alt} style={style} />;
};

export default RefundProofImage;
