import React from 'react';
import { usePaymentProofSignedUrlQuery } from '../../hooks/useOrders';

interface PaymentProofImageProps {
  orderId: string;
  alt: string;
  style?: React.CSSProperties;
}

// 付款憑證是私有 GCS 物件，畫面上只能用後端簽發的短效網址顯示 (SD-007 §4.7)
const PaymentProofImage: React.FC<PaymentProofImageProps> = ({ orderId, alt, style }) => {
  const { data, isLoading, isError } = usePaymentProofSignedUrlQuery(orderId, true);

  if (isLoading) {
    return (
      <div data-testid="payment-proof-loading" style={{ fontSize: '0.8rem' }}>
        憑證載入中...
      </div>
    );
  }
  if (isError || !data) {
    return (
      <div
        data-testid="payment-proof-error"
        style={{ fontSize: '0.8rem', color: 'var(--color-error)' }}
      >
        憑證圖片無法載入，請重新整理頁面
      </div>
    );
  }
  return <img data-testid="payment-proof-preview" src={data.signedUrl} alt={alt} style={style} />;
};

export default PaymentProofImage;
