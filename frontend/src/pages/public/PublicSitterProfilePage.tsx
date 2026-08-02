import React, { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { getPublicProfile, type PublicProfileResponse } from '../../api/publicProfileApi';
import { getActivePlansForOwner } from '../../api/servicePlanApi';
import type { ServicePlan } from '../../types/servicePlan';

const isLoggedInAsClient = (): boolean =>
  Boolean(localStorage.getItem('accessToken')) && localStorage.getItem('userRole') === 'client';

const PublicSitterProfilePage: React.FC = () => {
  const { sitterId } = useParams<{ sitterId: string }>();
  const [profile, setProfile] = useState<PublicProfileResponse | null>(null);
  const [plans, setPlans] = useState<ServicePlan[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!sitterId) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    getPublicProfile(sitterId)
      .then(async (data) => {
        if (cancelled) return;
        setProfile(data);
        if (!data.gated) {
          const activePlans = await getActivePlansForOwner(sitterId);
          if (!cancelled) setPlans(activePlans);
        }
      })
      .catch((err) => {
        if (!cancelled) setError(err.response?.data?.message || err.message || '無法載入保母資料');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [sitterId]);

  const handleBookNow = () => {
    const bookingPath = `/booking/${sitterId}`;
    window.location.href = isLoggedInAsClient()
      ? bookingPath
      : `/register?redirect=${encodeURIComponent(bookingPath)}`;
  };

  if (loading) {
    return <div style={{ padding: '3rem', textAlign: 'center' }}>載入中...</div>;
  }

  if (error || !profile) {
    return (
      <div style={{ padding: '3rem', textAlign: 'center', color: 'var(--color-error)' }}>
        {error || '找不到這位保母'}
      </div>
    );
  }

  const bookingPath = `/booking/${sitterId}`;

  return (
    <div style={{ minHeight: '100vh', padding: '1.5rem', display: 'flex', justifyContent: 'center' }}>
      <div
        className="card-layered"
        style={{ width: '100%', maxWidth: '640px', padding: '2rem', display: 'flex', flexDirection: 'column', gap: '1.25rem' }}
      >
        <div style={{ display: 'flex', gap: '1rem', alignItems: 'center' }}>
          {profile.avatarUrl && (
            <img
              src={profile.avatarUrl}
              alt={profile.displayName}
              style={{ width: '72px', height: '72px', borderRadius: '50%', objectFit: 'cover' }}
            />
          )}
          <div>
            <h1
              data-testid="public-profile-display-name"
              style={{ margin: 0, fontSize: '1.5rem', fontFamily: 'var(--font-display)', color: 'var(--color-primary)' }}
            >
              {profile.displayName}
            </h1>
            {profile.tags.length > 0 && (
              <div style={{ display: 'flex', gap: '0.4rem', flexWrap: 'wrap', marginTop: '0.4rem' }}>
                {profile.tags.map((tag) => (
                  <span
                    key={tag}
                    style={{
                      fontSize: '0.75rem',
                      padding: '0.15rem 0.5rem',
                      borderRadius: '999px',
                      background: 'var(--color-surface-variant)',
                      color: 'var(--color-on-surface-variant)'
                    }}
                  >
                    {tag}
                  </span>
                ))}
              </div>
            )}
          </div>
        </div>

        {profile.gated ? (
          <div data-testid="public-profile-gated-banner" style={{ color: 'var(--color-on-surface-variant)' }}>
            {profile.bio || '保母休息中，暫不開放預約'}
          </div>
        ) : (
          <>
            {profile.bio && <p style={{ margin: 0, color: 'var(--color-on-surface)' }}>{profile.bio}</p>}

            {profile.serviceAreas.length > 0 && (
              <div style={{ fontSize: '0.875rem', color: 'var(--color-on-surface-variant)' }}>
                服務區域：{profile.serviceAreas.map((area) => `${area.city}${area.district}`).join('、')}
              </div>
            )}

            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
              <h2 style={{ margin: 0, fontSize: '1.1rem' }}>服務方案</h2>
              {plans.length === 0 ? (
                <div style={{ color: 'var(--color-on-surface-variant)', fontSize: '0.875rem' }}>目前暫無上架方案</div>
              ) : (
                plans.map((plan) => (
                  <div
                    key={plan.id}
                    data-testid="public-profile-plan-item"
                    style={{
                      padding: '0.75rem',
                      borderRadius: '8px',
                      border: '1px solid var(--color-outline-variant)',
                      display: 'flex',
                      justifyContent: 'space-between'
                    }}
                  >
                    <span>{plan.name}</span>
                    <span>NT$ {plan.price}</span>
                  </div>
                ))
              )}
            </div>

            {profile.isOpen && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                <button
                  type="button"
                  className="btn-primary"
                  onClick={handleBookNow}
                  data-testid="public-profile-book-now-btn"
                  style={{ padding: '0.75rem' }}
                >
                  立即預約
                </button>
                <a
                  href={`/login?redirect=${encodeURIComponent(bookingPath)}`}
                  data-testid="public-profile-login-link"
                  style={{ fontSize: '0.8rem', textAlign: 'center', color: 'var(--color-on-surface-variant)' }}
                >
                  已有帳號？直接登入
                </a>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
};

export default PublicSitterProfilePage;
