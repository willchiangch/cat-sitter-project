import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Card from '../../components/ui/Card';
import {
  getMyFavorites,
  searchSitterForFavorite,
  addFavoriteSitter,
  removeFavoriteSitter
} from '../../api/favoriteApi';
import type { FavoriteSitter, SitterSearchResult } from '../../api/favoriteApi';

const FavoriteSitters: React.FC = () => {
  const navigate = useNavigate();
  const [favorites, setFavorites] = useState<FavoriteSitter[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [isSearchOpen, setIsSearchOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [searchResult, setSearchResult] = useState<SitterSearchResult | null>(null);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [searching, setSearching] = useState(false);
  const [adding, setAdding] = useState(false);

  const fetchFavorites = async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await getMyFavorites();
      setFavorites(data);
    } catch (err) {
      console.error(err);
      setError('取得收藏清單失敗');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchFavorites();
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
      const result = await searchSitterForFavorite(searchQuery.trim());
      setSearchResult(result);
    } catch (err: any) {
      setSearchError(err.response?.data?.message || '查無此保母，請確認 ID 是否正確');
    } finally {
      setSearching(false);
    }
  };

  const handleAddFromSearch = async () => {
    if (!searchResult) return;
    setAdding(true);
    try {
      await addFavoriteSitter(searchResult.sitterId);
      setIsSearchOpen(false);
      await fetchFavorites();
    } catch (err: any) {
      setSearchError(err.response?.data?.message || '加入收藏失敗');
    } finally {
      setAdding(false);
    }
  };

  const handleRemove = async (sitterId: string) => {
    if (!window.confirm('確定要移除此收藏嗎？')) return;
    try {
      await removeFavoriteSitter(sitterId);
      await fetchFavorites();
    } catch (err) {
      console.error(err);
      alert('移除失敗，請稍後再試');
    }
  };

  return (
    <div
      style={{
        padding: '2rem 1.5rem',
        fontFamily: 'var(--font-sans)',
        maxWidth: '640px',
        margin: '0 auto'
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: '1.5rem' }}>
        <h2
          style={{
            fontSize: '1.75rem',
            fontWeight: '700',
            color: 'var(--color-on-surface)',
            fontFamily: 'var(--font-display)',
            margin: 0
          }}
        >
          我的最愛保母
        </h2>
        <button
          className="btn-primary"
          style={{ padding: '0.5rem 1rem', fontSize: '0.875rem' }}
          onClick={openSearchModal}
          data-testid="favorite-add-btn"
        >
          + 新增收藏
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
                data-testid="favorite-search-input"
              />
            </div>

            <button
              type="submit"
              className="btn-primary"
              disabled={searching || !searchQuery.trim()}
              style={{ padding: '0.6rem' }}
              data-testid="favorite-search-submit-btn"
            >
              {searching ? '搜尋中...' : '搜尋'}
            </button>

            {searchError && (
              <div style={{ color: '#dc2626', fontSize: '0.85rem' }} data-testid="favorite-search-error">
                {searchError}
              </div>
            )}

            {searchResult && (
              <div
                style={{
                  padding: '0.8rem',
                  backgroundColor: 'var(--color-surface-low)',
                  borderRadius: '8px',
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center'
                }}
                data-testid="favorite-search-result"
              >
                <div>
                  <div style={{ fontWeight: '600' }}>{searchResult.displayName}</div>
                  <div style={{ fontSize: '0.75rem', color: 'var(--color-on-surface-variant)' }}>
                    {searchResult.email}
                  </div>
                </div>
                <button
                  className="btn-primary"
                  onClick={handleAddFromSearch}
                  disabled={adding}
                  style={{ padding: '0.4rem 0.8rem', fontSize: '0.85rem' }}
                  data-testid="favorite-search-confirm-add-btn"
                >
                  {adding ? '加入中...' : '加入'}
                </button>
              </div>
            )}

            <button
              type="button"
              onClick={() => setIsSearchOpen(false)}
              style={{
                padding: '0.5rem',
                backgroundColor: 'transparent',
                border: 'none',
                color: 'var(--color-on-surface-variant)',
                cursor: 'pointer',
                fontSize: '0.8rem'
              }}
            >
              取消
            </button>
          </form>
        </Card>
      )}

      {loading ? (
        <div style={{ textAlign: 'center', padding: '40px 0', color: '#9ca3af' }}>載入中...</div>
      ) : error ? (
        <div style={{ textAlign: 'center', padding: '40px 0', color: '#dc2626' }}>{error}</div>
      ) : favorites.length === 0 ? (
        <div style={{ textAlign: 'center', padding: '40px 0', color: '#9ca3af' }}>尚未收藏任何保母</div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }} data-testid="favorite-list">
          {favorites.map((f) => (
            <Card
              key={f.sitterId}
              data-testid={`favorite-row-${f.sitterId}`}
              style={{ opacity: f.removed || f.hidden ? 0.6 : 1 }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <div
                  onClick={() => !f.removed && navigate(`/booking/${f.sitterId}`)}
                  style={{ cursor: f.removed ? 'default' : 'pointer', flex: 1 }}
                >
                  <div style={{ fontWeight: '700' }}>{f.removed ? '帳號已移除' : f.displayName}</div>
                  {!f.removed && (
                    <div style={{ fontSize: '0.75rem', color: 'var(--color-on-surface-variant)', marginTop: '0.2rem' }}>
                      {f.hidden ? '休息中/隱藏中' : '服務中'}
                      {f.tags && f.tags.length > 0 && ` · ${f.tags.join('、')}`}
                    </div>
                  )}
                </div>
                <button
                  onClick={() => handleRemove(f.sitterId)}
                  data-testid={`favorite-remove-${f.sitterId}`}
                  style={{
                    padding: '0.4rem 0.7rem',
                    fontSize: '0.8rem',
                    color: '#dc2626',
                    background: 'none',
                    border: 'none',
                    cursor: 'pointer'
                  }}
                >
                  移除
                </button>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
};

export default FavoriteSitters;
