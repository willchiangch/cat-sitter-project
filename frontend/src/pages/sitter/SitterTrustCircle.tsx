import React, { useEffect, useState } from 'react';
import Card from '../../components/ui/Card';
import {
  getMyTrustCircle,
  getIncomingTrustRequests,
  getOutgoingTrustRequests,
  searchSitterForTrustCircle,
  sendTrustRequest,
  respondToTrustRequest,
  removeTrustRelationship
} from '../../api/trustCircleApi';
import type { TrustRelationship, SitterSearchResult } from '../../api/trustCircleApi';

const SitterTrustCircle: React.FC = () => {
  const [circle, setCircle] = useState<TrustRelationship[]>([]);
  const [incoming, setIncoming] = useState<TrustRelationship[]>([]);
  const [outgoing, setOutgoing] = useState<TrustRelationship[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [isSearchOpen, setIsSearchOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [searchResult, setSearchResult] = useState<SitterSearchResult | null>(null);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [searching, setSearching] = useState(false);
  const [sending, setSending] = useState(false);

  const fetchAll = async () => {
    setLoading(true);
    setError(null);
    try {
      const [circleData, incomingData, outgoingData] = await Promise.all([
        getMyTrustCircle(),
        getIncomingTrustRequests(),
        getOutgoingTrustRequests()
      ]);
      setCircle(circleData);
      setIncoming(incomingData);
      setOutgoing(outgoingData);
    } catch (err) {
      console.error(err);
      setError('取得信任圈資料失敗');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchAll();
  }, []);

  const openSearchModal = () => {
    setIsSearchOpen(true);
    setSearchQuery('');
    setSearchResult(null);
    setSearchError(null);
  };

  const handleSearch = async (e: React.FormEvent) => {
    e.preventDefault();
    setSearchError(null);
    setSearchResult(null);
    setSearching(true);
    try {
      const result = await searchSitterForTrustCircle(searchQuery.trim());
      setSearchResult(result);
    } catch (err: any) {
      setSearchError(err.response?.data?.message || '查無此保母，請確認 ID 是否正確');
    } finally {
      setSearching(false);
    }
  };

  const handleSendRequest = async () => {
    if (!searchResult) return;
    setSending(true);
    try {
      await sendTrustRequest(searchResult.sitterId);
      setIsSearchOpen(false);
      await fetchAll();
    } catch (err: any) {
      setSearchError(err.response?.data?.message || '送出邀請失敗');
    } finally {
      setSending(false);
    }
  };

  const handleRespond = async (relationshipId: string, accept: boolean) => {
    try {
      await respondToTrustRequest(relationshipId, accept);
      await fetchAll();
    } catch (err) {
      console.error(err);
      alert('操作失敗，請稍後再試');
    }
  };

  const handleRemove = async (relationshipId: string) => {
    if (!window.confirm('確定要移除此信任關係嗎？')) return;
    try {
      await removeTrustRelationship(relationshipId);
      await fetchAll();
    } catch (err) {
      console.error(err);
      alert('移除失敗，請稍後再試');
    }
  };

  return (
    <div style={{ padding: '2rem 1.5rem', fontFamily: 'var(--font-sans)', maxWidth: '640px', margin: '0 auto' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: '1.5rem' }}>
        <h2 style={{ fontSize: '1.75rem', fontWeight: '700', color: 'var(--color-on-surface)', fontFamily: 'var(--font-display)', margin: 0 }}>
          信任圈
        </h2>
        <button className="btn-primary" style={{ padding: '0.5rem 1rem', fontSize: '0.875rem' }} onClick={openSearchModal} data-testid="trust-add-btn">
          + 邀請保母
        </button>
      </div>

      {isSearchOpen && (
        <Card style={{ marginBottom: '1.5rem', padding: '1.5rem' }}>
          <form onSubmit={handleSearch} style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.4rem' }}>
              <label style={{ fontSize: '0.8rem', fontWeight: '600' }}>保母帳號 ID 或 Email</label>
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                style={{ padding: '0.6rem', borderRadius: '8px', border: '1px solid var(--color-outline-variant)' }}
                data-testid="trust-search-input"
              />
            </div>
            <button type="submit" className="btn-primary" disabled={searching || !searchQuery.trim()} style={{ padding: '0.6rem' }} data-testid="trust-search-submit-btn">
              {searching ? '搜尋中...' : '搜尋'}
            </button>
            {searchError && <div style={{ color: '#dc2626', fontSize: '0.85rem' }} data-testid="trust-search-error">{searchError}</div>}
            {searchResult && (
              <div style={{ padding: '0.8rem', backgroundColor: 'var(--color-surface-low)', borderRadius: '8px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }} data-testid="trust-search-result">
                <div>
                  <div style={{ fontWeight: '600' }}>{searchResult.displayName}</div>
                  <div style={{ fontSize: '0.75rem', color: 'var(--color-on-surface-variant)' }}>{searchResult.email}</div>
                </div>
                <button className="btn-primary" onClick={handleSendRequest} disabled={sending} style={{ padding: '0.4rem 0.8rem', fontSize: '0.85rem' }} data-testid="trust-search-confirm-send-btn">
                  {sending ? '送出中...' : '送出邀請'}
                </button>
              </div>
            )}
            <button type="button" onClick={() => setIsSearchOpen(false)} style={{ padding: '0.5rem', background: 'none', border: 'none', color: 'var(--color-on-surface-variant)', cursor: 'pointer', fontSize: '0.8rem' }}>
              取消
            </button>
          </form>
        </Card>
      )}

      {loading ? (
        <div style={{ textAlign: 'center', padding: '40px 0', color: '#9ca3af' }}>載入中...</div>
      ) : error ? (
        <div style={{ textAlign: 'center', padding: '40px 0', color: '#dc2626' }}>{error}</div>
      ) : (
        <>
          {incoming.length > 0 && (
            <div style={{ marginBottom: '2rem' }}>
              <h3 style={{ fontSize: '1rem', fontWeight: '700', marginBottom: '0.75rem' }}>待處理的邀請</h3>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }} data-testid="trust-incoming-list">
                {incoming.map((r) => (
                  <Card key={r.relationshipId} data-testid={`trust-incoming-${r.relationshipId}`}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <div style={{ fontWeight: '600' }}>{r.displayName}</div>
                      <div style={{ display: 'flex', gap: '0.4rem' }}>
                        <button className="btn-primary" onClick={() => handleRespond(r.relationshipId, true)} style={{ padding: '0.4rem 0.8rem', fontSize: '0.8rem' }} data-testid={`trust-accept-${r.relationshipId}`}>
                          同意
                        </button>
                        <button onClick={() => handleRespond(r.relationshipId, false)} style={{ padding: '0.4rem 0.8rem', fontSize: '0.8rem', background: 'none', border: '1px solid var(--color-outline-variant)', borderRadius: '8px', cursor: 'pointer' }} data-testid={`trust-reject-${r.relationshipId}`}>
                          拒絕
                        </button>
                      </div>
                    </div>
                  </Card>
                ))}
              </div>
            </div>
          )}

          {outgoing.length > 0 && (
            <div style={{ marginBottom: '2rem' }}>
              <h3 style={{ fontSize: '1rem', fontWeight: '700', marginBottom: '0.75rem' }}>已送出，等待對方同意</h3>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }} data-testid="trust-outgoing-list">
                {outgoing.map((r) => (
                  <Card key={r.relationshipId} data-testid={`trust-outgoing-${r.relationshipId}`}>
                    <div style={{ fontWeight: '600' }}>{r.displayName}</div>
                  </Card>
                ))}
              </div>
            </div>
          )}

          <h3 style={{ fontSize: '1rem', fontWeight: '700', marginBottom: '0.75rem' }}>我的信任圈</h3>
          {circle.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '40px 0', color: '#9ca3af' }}>尚未建立任何信任關係</div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }} data-testid="trust-circle-list">
              {circle.map((r) => (
                <Card key={r.relationshipId} data-testid={`trust-circle-${r.sitterId}`}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <div>
                      <div style={{ fontWeight: '600' }}>{r.displayName}</div>
                      <div style={{ fontSize: '0.75rem', color: 'var(--color-on-surface-variant)' }}>{r.email}</div>
                    </div>
                    <button
                      onClick={() => handleRemove(r.relationshipId)}
                      style={{ padding: '0.4rem 0.7rem', fontSize: '0.8rem', color: '#dc2626', background: 'none', border: 'none', cursor: 'pointer' }}
                      data-testid={`trust-remove-${r.relationshipId}`}
                    >
                      移除
                    </button>
                  </div>
                </Card>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
};

export default SitterTrustCircle;
