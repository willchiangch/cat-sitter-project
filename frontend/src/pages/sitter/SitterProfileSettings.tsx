import React, { useState, useEffect, useRef } from 'react';
import Card from '../../components/ui/Card';
import Button from '../../components/ui/Button';
import {
  usePublicProfileQuery,
  useUpdateProfileMutation,
  useUploadAvatarMutation
} from '../../hooks/usePublicProfile';
import { Camera, Plus, X, CheckCircle2, AlertCircle } from 'lucide-react';

const decodeJwt = (token: string) => {
  try {
    const base64Url = token.split('.')[1];
    const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
    const jsonPayload = decodeURIComponent(
      window
        .atob(base64)
        .split('')
        .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join('')
    );
    return JSON.parse(jsonPayload);
  } catch (e) {
    return null;
  }
};

const getUserIdFromToken = (): string => {
  const token = localStorage.getItem('accessToken');
  if (!token) return '3d498178-14c0-4376-b81e-7fb02e615dda'; // fallback mock
  const decoded = decodeJwt(token);
  return decoded?.userId || '3d498178-14c0-4376-b81e-7fb02e615dda';
};

const SitterProfileSettings: React.FC = () => {
  const sitterId = getUserIdFromToken();
  const { data: profile, isLoading, error, refetch } = usePublicProfileQuery(sitterId, 'edit');
  const updateProfileMutation = useUpdateProfileMutation(sitterId);
  const uploadAvatarMutation = useUploadAvatarMutation(sitterId);

  const [displayName, setDisplayName] = useState('');
  const [bio, setBio] = useState('');
  const [isVisible, setIsVisible] = useState(true);
  const [tags, setTags] = useState<string[]>([]);
  const [newTag, setNewTag] = useState('');
  const [serviceAreas, setServiceAreas] = useState<{ city: string; district: string }[]>([]);
  const [newCity, setNewCity] = useState('');
  const [newDistrict, setNewDistrict] = useState('');
  const [avatarPreview, setAvatarPreview] = useState('');

  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const [uploadMessage, setUploadMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (profile) {
      setDisplayName(profile.displayName || '');
      setBio(profile.bio || '');
      setIsVisible(profile.isVisible ?? true);
      setTags(profile.tags || []);
      setServiceAreas(profile.serviceAreas || []);
      setAvatarPreview(profile.avatarUrl || '');
    }
  }, [profile]);

  const handleAddTag = (e: React.FormEvent) => {
    e.preventDefault();
    const cleanTag = newTag.trim();
    if (!cleanTag) return;
    if (cleanTag.length > 10) {
      setMessage({ type: 'error', text: '單一標籤最多 10 字' });
      return;
    }
    if (tags.length >= 10) {
      setMessage({ type: 'error', text: '最多僅能設定 10 個標籤' });
      return;
    }
    if (tags.includes(cleanTag)) {
      setMessage({ type: 'error', text: '標籤已存在' });
      return;
    }
    setTags([...tags, cleanTag]);
    setNewTag('');
    setMessage(null);
  };

  const handleRemoveTag = (indexToRemove: number) => {
    setTags(tags.filter((_, i) => i !== indexToRemove));
  };

  const handleAddArea = (e: React.FormEvent) => {
    e.preventDefault();
    const city = newCity.trim();
    const district = newDistrict.trim();
    if (!city || !district) {
      setMessage({ type: 'error', text: '縣市與行政區不可為空' });
      return;
    }
    if (serviceAreas.some((a) => a.city === city && a.district === district)) {
      setMessage({ type: 'error', text: '此服務區域已設定' });
      return;
    }
    setServiceAreas([...serviceAreas, { city, district }]);
    setNewCity('');
    setNewDistrict('');
    setMessage(null);
  };

  const handleRemoveArea = (indexToRemove: number) => {
    setServiceAreas(serviceAreas.filter((_, i) => i !== indexToRemove));
  };

  const handleAvatarChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    if (file.size > 2 * 1024 * 1024) {
      setUploadMessage({ type: 'error', text: '頭像檔案大小不可超過 2MB' });
      return;
    }
    if (!['image/jpeg', 'image/png', 'image/jpg'].includes(file.type)) {
      setUploadMessage({ type: 'error', text: '僅支援 JPG 或 PNG 格式圖片' });
      return;
    }

    setUploadMessage(null);
    try {
      const newAvatarUrl = await uploadAvatarMutation.mutateAsync(file);
      setAvatarPreview(newAvatarUrl);
      setUploadMessage({ type: 'success', text: '頭像上傳成功！' });
      refetch();
    } catch (err: any) {
      console.error(err);
      setUploadMessage({ type: 'error', text: err.response?.data?.message || '頭像上傳失敗' });
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setMessage(null);

    if (!displayName.trim()) {
      setMessage({ type: 'error', text: '暱稱不可為空' });
      return;
    }

    try {
      await updateProfileMutation.mutateAsync({
        displayName: displayName.trim(),
        bio: bio.trim(),
        isVisible: isVisible,
        tags: tags,
        serviceAreas: serviceAreas,
        version: profile?.version ?? 0
      });
      setMessage({ type: 'success', text: '公開檔案儲存成功！' });
      refetch();
    } catch (err: any) {
      console.error(err);
      const errMsg = err.response?.data?.message || '更新失敗，請檢查輸入欄位格式';
      setMessage({ type: 'error', text: errMsg });
    }
  };

  if (isLoading) {
    return (
      <div style={{ padding: '4rem 2rem', textAlign: 'center', color: 'var(--color-on-surface-variant)' }}>
        <div style={{ fontSize: '1.25rem', fontWeight: '800' }}>載入個人公開檔案中...</div>
      </div>
    );
  }

  if (error) {
    return (
      <div style={{ padding: '4rem 2rem', textAlign: 'center', color: 'var(--color-error)' }}>
        <div style={{ fontSize: '1.25rem', fontWeight: '800' }}>載入公開檔案失敗</div>
        <p style={{ marginTop: '0.5rem' }}>請確認保母帳號設定是否正確。</p>
      </div>
    );
  }

  return (
    <div style={{ padding: '2rem 1.5rem', fontFamily: 'var(--font-sans)', maxWidth: '640px', margin: '0 auto' }}>
      <h2
        style={{
          fontSize: '1.75rem',
          fontWeight: '700',
          color: 'var(--color-on-surface)',
          fontFamily: 'var(--font-display)',
          marginBottom: '0.5rem'
        }}
      >
        保母公開檔案管理
      </h2>
      <p style={{ fontSize: '0.875rem', color: 'var(--color-on-surface-variant)', marginBottom: '2rem' }}>
        設定您的展示資訊與個人簡介。這些內容將公開在您的專屬預約頁面上，讓飼主能夠更快速地了解您。
      </p>

      {/* 大頭貼上傳卡片 */}
      <Card style={{ marginBottom: '1.5rem', padding: '1.5rem' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '1.5rem' }}>
          <div style={{ position: 'relative', width: '96px', height: '96px' }}>
            <img
              src={avatarPreview || 'https://via.placeholder.com/150'}
              alt="Avatar Preview"
              style={{
                width: '100%',
                height: '100%',
                borderRadius: '50%',
                objectFit: 'cover',
                border: '3px solid var(--color-primary-light)'
              }}
            />
            <button
              onClick={() => fileInputRef.current?.click()}
              style={{
                position: 'absolute',
                bottom: '0',
                right: '0',
                backgroundColor: 'var(--color-primary)',
                color: 'white',
                border: 'none',
                borderRadius: '50%',
                width: '32px',
                height: '32px',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                cursor: 'pointer',
                boxShadow: 'var(--shadow-md)'
              }}
              data-testid="sitter-profile-btn-trigger-avatar"
            >
              <Camera size={16} />
            </button>
            <input
              type="file"
              ref={fileInputRef}
              onChange={handleAvatarChange}
              accept="image/jpeg,image/png"
              style={{ display: 'none' }}
              data-testid="sitter-profile-input-avatar"
            />
          </div>
          <div>
            <h4 style={{ margin: 0, fontWeight: '700', color: 'var(--color-on-surface)' }}>大頭照</h4>
            <p style={{ margin: '4px 0 0 0', fontSize: '0.75rem', color: 'var(--color-on-surface-variant)' }}>
              支援 JPG 或 PNG，檔案大小上限為 2MB。
            </p>
            {uploadMessage && (
              <div
                style={{
                  marginTop: '8px',
                  padding: '4px 8px',
                  borderRadius: '4px',
                  fontSize: '0.75rem',
                  backgroundColor: uploadMessage.type === 'success' ? '#dcfce7' : '#fee2e2',
                  color: uploadMessage.type === 'success' ? '#16a34a' : '#ef4444'
                }}
              >
                {uploadMessage.text}
              </div>
            )}
          </div>
        </div>
      </Card>

      {/* 公開檔案設定卡片 */}
      <Card style={{ padding: '1.5rem' }}>
        <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
          
          {/* 暱稱 */}
          <div>
            <label
              style={{
                display: 'block',
                fontSize: '0.875rem',
                fontWeight: '600',
                marginBottom: '0.5rem',
                color: 'var(--color-on-surface)'
              }}
            >
              暱稱 (展示名稱)
            </label>
            <input
              type="text"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              placeholder="例如 愛貓保母阿香"
              maxLength={100}
              style={{
                width: '100%',
                padding: '0.75rem',
                borderRadius: '8px',
                border: '1px solid var(--color-surface-high)',
                backgroundColor: 'var(--color-surface)',
                color: 'var(--color-on-surface)'
              }}
              data-testid="sitter-profile-input-display-name"
            />
          </div>

          {/* 自我介紹 */}
          <div>
            <label
              style={{
                display: 'block',
                fontSize: '0.875rem',
                fontWeight: '600',
                marginBottom: '0.5rem',
                color: 'var(--color-on-surface)'
              }}
            >
              自我介紹 (Bio)
            </label>
            <textarea
              value={bio}
              onChange={(e) => setBio(e.target.value)}
              placeholder="詳細說明您的照顧理念、經驗與特色..."
              maxLength={2000}
              style={{
                width: '100%',
                height: '140px',
                padding: '0.75rem',
                borderRadius: '8px',
                border: '1px solid var(--color-surface-high)',
                backgroundColor: 'var(--color-surface)',
                color: 'var(--color-on-surface)',
                fontFamily: 'var(--font-sans)',
                resize: 'none'
              }}
              data-testid="sitter-profile-input-bio"
            />
          </div>

          {/* 是否公開顯示 */}
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0.5rem 0' }}>
            <div>
              <h4 style={{ margin: 0, fontSize: '0.875rem', fontWeight: '600', color: 'var(--color-on-surface)' }}>
                公開此檔案
              </h4>
              <p style={{ margin: '4px 0 0 0', fontSize: '0.75rem', color: 'var(--color-on-surface-variant)' }}>
                關閉後，飼主將無法搜尋到您，已預約的飼主不受影響。
              </p>
            </div>
            <label className="switch" style={{ position: 'relative', display: 'inline-block', width: '50px', height: '26px' }}>
              <input
                type="checkbox"
                checked={isVisible}
                onChange={(e) => setIsVisible(e.target.checked)}
                data-testid="sitter-profile-toggle-visible"
                style={{ opacity: 0, width: 0, height: 0 }}
              />
              <span
                style={{
                  position: 'absolute',
                  cursor: 'pointer',
                  top: 0, left: 0, right: 0, bottom: 0,
                  backgroundColor: isVisible ? 'var(--color-primary)' : '#ccc',
                  borderRadius: '34px',
                  transition: '0.4s'
                }}
              >
                <span
                  style={{
                    position: 'absolute',
                    content: '""',
                    height: '18px', width: '18px',
                    left: isVisible ? '28px' : '4px',
                    bottom: '4px',
                    backgroundColor: 'white',
                    borderRadius: '50%',
                    transition: '0.4s'
                  }}
                />
              </span>
            </label>
          </div>

          {/* 標籤管理 */}
          <div>
            <label
              style={{
                display: 'block',
                fontSize: '0.875rem',
                fontWeight: '600',
                marginBottom: '0.5rem',
                color: 'var(--color-on-surface)'
              }}
            >
              特點標籤 (最多 10 個，上限 10 字)
            </label>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px', marginBottom: '8px' }}>
              {tags.map((tag, idx) => (
                <span
                  key={idx}
                  style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: '4px',
                    padding: '4px 10px',
                    borderRadius: '20px',
                    fontSize: '0.75rem',
                    backgroundColor: 'var(--color-primary-lowest)',
                    color: 'var(--color-primary)',
                    border: '1px solid var(--color-primary-light)'
                  }}
                >
                  {tag}
                  <X
                    size={12}
                    onClick={() => handleRemoveTag(idx)}
                    style={{ cursor: 'pointer', color: 'var(--color-primary)' }}
                  />
                </span>
              ))}
            </div>
            <div style={{ display: 'flex', gap: '8px' }}>
              <input
                type="text"
                value={newTag}
                onChange={(e) => setNewTag(e.target.value)}
                placeholder="新增特色標籤"
                maxLength={10}
                style={{
                  flex: 1,
                  padding: '0.5rem',
                  borderRadius: '6px',
                  border: '1px solid var(--color-surface-high)',
                  backgroundColor: 'var(--color-surface)',
                  color: 'var(--color-on-surface)',
                  fontSize: '0.875rem'
                }}
                data-testid="sitter-profile-input-tag"
              />
              <Button
                type="button"
                onClick={handleAddTag}
                variant="secondary"
                style={{ padding: '0.5rem 1rem', display: 'flex', alignItems: 'center' }}
                data-testid="sitter-profile-btn-add-tag"
              >
                <Plus size={16} /> 新增
              </Button>
            </div>
          </div>

          {/* 服務區域管理 */}
          <div>
            <label
              style={{
                display: 'block',
                fontSize: '0.875rem',
                fontWeight: '600',
                marginBottom: '0.5rem',
                color: 'var(--color-on-surface)'
              }}
            >
              服務行政區設定
            </label>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px', marginBottom: '8px' }}>
              {serviceAreas.map((area, idx) => (
                <span
                  key={idx}
                  style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: '4px',
                    padding: '4px 10px',
                    borderRadius: '6px',
                    fontSize: '0.75rem',
                    backgroundColor: 'var(--color-surface-high)',
                    color: 'var(--color-on-surface)',
                    border: '1px solid var(--color-surface-higher)'
                  }}
                >
                  {area.city} {area.district}
                  <X
                    size={12}
                    onClick={() => handleRemoveArea(idx)}
                    style={{ cursor: 'pointer', color: 'var(--color-on-surface-variant)' }}
                  />
                </span>
              ))}
            </div>
            <div style={{ display: 'flex', gap: '8px' }}>
              <input
                type="text"
                value={newCity}
                onChange={(e) => setNewCity(e.target.value)}
                placeholder="縣市 (e.g. 台北市)"
                style={{
                  flex: 1,
                  padding: '0.5rem',
                  borderRadius: '6px',
                  border: '1px solid var(--color-surface-high)',
                  backgroundColor: 'var(--color-surface)',
                  color: 'var(--color-on-surface)',
                  fontSize: '0.875rem'
                }}
                data-testid="sitter-profile-input-city"
              />
              <input
                type="text"
                value={newDistrict}
                onChange={(e) => setNewDistrict(e.target.value)}
                placeholder="行政區 (e.g. 信義區)"
                style={{
                  flex: 1,
                  padding: '0.5rem',
                  borderRadius: '6px',
                  border: '1px solid var(--color-surface-high)',
                  backgroundColor: 'var(--color-surface)',
                  color: 'var(--color-on-surface)',
                  fontSize: '0.875rem'
                }}
                data-testid="sitter-profile-input-district"
              />
              <Button
                type="button"
                onClick={handleAddArea}
                variant="secondary"
                style={{ padding: '0.5rem 1rem', display: 'flex', alignItems: 'center' }}
                data-testid="sitter-profile-btn-add-area"
              >
                <Plus size={16} /> 新增
              </Button>
            </div>
          </div>

          {message && (
            <div
              style={{
                padding: '0.75rem',
                borderRadius: '6px',
                fontSize: '0.875rem',
                fontWeight: '500',
                display: 'flex',
                alignItems: 'center',
                gap: '8px',
                backgroundColor: message.type === 'success' ? '#dcfce7' : '#fee2e2',
                color: message.type === 'success' ? '#16a34a' : '#ef4444'
              }}
              data-testid="sitter-profile-message"
            >
              {message.type === 'success' ? <CheckCircle2 size={18} /> : <AlertCircle size={18} />}
              {message.text}
            </div>
          )}

          <Button
            type="submit"
            disabled={updateProfileMutation.isPending}
            className="btn-primary"
            style={{ width: '100%', padding: '0.875rem', fontSize: '1rem', fontWeight: '700' }}
            data-testid="sitter-profile-btn-save"
          >
            {updateProfileMutation.isPending ? '儲存中...' : '儲存公開檔案'}
          </Button>

        </form>
      </Card>
    </div>
  );
};

export default SitterProfileSettings;
