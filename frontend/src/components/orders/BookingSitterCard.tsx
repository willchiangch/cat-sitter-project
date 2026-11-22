import React from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Heart } from 'lucide-react';
import { usePublicProfileQuery } from '../../hooks/usePublicProfile';
import { addFavoriteSitter, getMyFavorites, removeFavoriteSitter } from '../../api/favoriteApi';

/**
 * 預約頁頁首（PRD-018 公開檔案資料 + PRD-019 收藏愛心）。保留原 testid `client-booking-sitter-*`、
 * `client-booking-favorite-toggle`。收藏只對飼主有效，查詢失敗時維持未收藏樣式。
 */
const BookingSitterCard: React.FC<{ sitterId: string; canFavorite: boolean }> = ({
  sitterId,
  canFavorite
}) => {
  const qc = useQueryClient();
  const { data: profile } = usePublicProfileQuery(sitterId);
  const favorites = useQuery({
    queryKey: ['favorites'],
    queryFn: getMyFavorites,
    enabled: canFavorite,
    retry: false
  });
  const isFavorited = (favorites.data ?? []).some((f) => f.sitterId === sitterId);
  const toggle = useMutation({
    mutationFn: () => (isFavorited ? removeFavoriteSitter(sitterId) : addFavoriteSitter(sitterId)),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['favorites'] })
  });
  return (
    <section
      className="card-layered stack"
      style={{ padding: '1.25rem' }}
      data-testid="client-booking-sitter-card"
    >
      <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center' }}>
        {profile?.avatarUrl ? (
          <img
            src={profile.avatarUrl}
            alt=""
            data-testid="client-booking-sitter-avatar"
            style={{
              width: 48,
              height: 48,
              borderRadius: '50%',
              objectFit: 'cover',
              flexShrink: 0
            }}
          />
        ) : (
          <div
            data-testid="client-booking-sitter-avatar"
            style={{
              width: 48,
              height: 48,
              borderRadius: '50%',
              flexShrink: 0,
              background: 'var(--color-primary-container)',
              color: 'var(--color-on-primary-container)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontWeight: 700
            }}
          >
            {(profile?.displayName ?? '保').slice(0, 1)}
          </div>
        )}
        <h2
          style={{ margin: 0, flex: 1, minWidth: 0, overflowWrap: 'anywhere' }}
          data-testid="client-booking-sitter-name"
        >
          {profile?.displayName ?? '保母'}
        </h2>
        {canFavorite && (
          <button
            type="button"
            onClick={() => !toggle.isPending && toggle.mutate()}
            disabled={toggle.isPending}
            aria-label={isFavorited ? '移除收藏' : '加入收藏'}
            aria-pressed={isFavorited}
            data-testid="client-booking-favorite-toggle"
            style={{
              background: 'none',
              border: 'none',
              cursor: 'pointer',
              flexShrink: 0,
              padding: 4
            }}
          >
            <Heart
              size={22}
              fill={isFavorited ? '#dc2626' : 'none'}
              color={isFavorited ? '#dc2626' : 'var(--color-on-surface-variant)'}
            />
          </button>
        )}
      </div>
      {profile?.bio && (
        <p
          className="muted"
          style={{ margin: 0, overflowWrap: 'anywhere', whiteSpace: 'pre-wrap' }}
          data-testid="client-booking-sitter-bio"
        >
          {profile.bio}
        </p>
      )}
      {profile?.tags && profile.tags.length > 0 && (
        <div className="btn-row" data-testid="client-booking-sitter-tags">
          {profile.tags.map((tag) => (
            <span
              key={tag}
              className="surface-block"
              style={{ padding: '0.125rem 0.5rem', fontSize: '0.75rem' }}
            >
              {tag}
            </span>
          ))}
        </div>
      )}
      {profile?.serviceAreas && profile.serviceAreas.length > 0 && (
        <span
          className="muted"
          style={{ overflowWrap: 'anywhere' }}
          data-testid="client-booking-sitter-areas"
        >
          服務區域：{profile.serviceAreas.map((a) => `${a.city}${a.district}`).join('、')}
        </span>
      )}
    </section>
  );
};

export default BookingSitterCard;
