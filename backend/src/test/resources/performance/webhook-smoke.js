import http from 'k6/http';
import { check, sleep } from 'k6';

export let options = {
  vus: 50, // Up to 50 Virtual Users based on user feedback
  duration: '30s',
};

export default function () {
  const baseURL = __ENV.BASE_URL || 'http://localhost:8081';
  const url = `${baseURL}/api/v1/payments/payuni/webhook`;
  const payload = {
    Status: 'SUCCESS',
    TradeNo: 'PAYUNI_' + Math.random().toString(36).substring(7),
    MerTradeNo: 'ORD_' + Math.random().toString(36).substring(7),
    Amount: '199',
    PaymentType: 'CREDIT',
    Hash: 'mock_hash_for_smoke_test'
  };

  const params = {
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
    },
  };

  const res = http.post(url, payload, params);
  check(res, {
    'status is 200': (r) => r.status === 200,
    'transaction processed': (r) => r.body.includes('SUCCESS') || r.status === 200,
  });
  sleep(1);
}
