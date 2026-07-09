import React, { useState, useRef } from 'react';
import {
  usePetsQuery,
  useCreatePetMutation,
  useUpdatePetMutation,
  useDeletePetMutation,
  useUpdatePetNotesMutation,
  usePetEditLogsQuery,
  useUploadAvatarMutation
} from '../../hooks/usePets';
import type { Pet } from '../../types/pet';
import {
  Plus,
  User,
  FileText,
  Activity,
  Trash2,
  Save,
  Edit,
  History,
  Upload,
  AlertCircle,
  Check,
  Loader2,
  X
} from 'lucide-react';

const PetManager: React.FC = () => {
  const { data: pets = [], isLoading: isLoadingPets } = usePetsQuery();
  const createPetMutation = useCreatePetMutation();
  const updatePetMutation = useUpdatePetMutation();
  const deletePetMutation = useDeletePetMutation();
  const updateNotesMutation = useUpdatePetNotesMutation();
  const uploadAvatarMutation = useUploadAvatarMutation();

  const [selectedPetId, setSelectedPetId] = useState<string | null>(null);
  const [isAdding, setIsAdding] = useState(false);
  const [isEditingNotes, setIsEditingNotes] = useState(false);
  const [showLogsModal, setShowLogsModal] = useState(false);

  // 提示訊息狀態
  const [toast, setToast] = useState<{ type: 'success' | 'error'; message: string } | null>(null);
  const [optimisticLockError, setOptimisticLockError] = useState<string | null>(null);

  // 表單資料狀態 (基本資料)
  const [petForm, setPetForm] = useState<Omit<Pet, 'id' | 'ownerId'>>({
    name: '',
    species: 'CAT',
    gender: 'MALE',
    neutered: false,
    weight: undefined,
    birthYear: undefined,
    photoUrl: '',
    medicalPersonalityNotes: '',
    environmentalNotes: ''
  });

  // 注意事項編輯狀態
  const [notesForm, setNotesForm] = useState({
    medicalPersonalityNotes: '',
    environmentalNotes: ''
  });

  // 新增毛孩流程中，使用者已選但尚未上傳的頭像檔案 (等基本資料存檔拿到 petId 後才真的上傳)
  const [pendingAvatarFile, setPendingAvatarFile] = useState<File | null>(null);
  const [avatarPreviewUrl, setAvatarPreviewUrl] = useState<string>('');

  const fileInputRef = useRef<HTMLInputElement>(null);

  // 當前選中的毛孩
  const selectedPet = pets.find((p) => p.id === selectedPetId) || null;

  // 取得異動日誌
  const { data: editLogs = [], isLoading: isLoadingLogs } = usePetEditLogsQuery(
    selectedPetId || ''
  );

  const showToast = (type: 'success' | 'error', message: string) => {
    setToast({ type, message });
    setTimeout(() => {
      setToast(null);
    }, 4000);
  };

  const clearPendingAvatar = () => {
    if (avatarPreviewUrl) {
      URL.revokeObjectURL(avatarPreviewUrl);
    }
    setPendingAvatarFile(null);
    setAvatarPreviewUrl('');
  };

  const handleSelectPet = (pet: Pet) => {
    setSelectedPetId(pet.id || null);
    setIsAdding(false);
    setIsEditingNotes(false);
    setOptimisticLockError(null);
    clearPendingAvatar();
    setPetForm({
      name: pet.name,
      species: pet.species,
      gender: pet.gender || 'MALE',
      neutered: pet.neutered || false,
      weight: pet.weight,
      birthYear: pet.birthYear,
      photoUrl: pet.photoUrl || '',
      medicalPersonalityNotes: pet.medicalPersonalityNotes || '',
      environmentalNotes: pet.environmentalNotes || ''
    });
    setNotesForm({
      medicalPersonalityNotes: pet.medicalPersonalityNotes || '',
      environmentalNotes: pet.environmentalNotes || ''
    });
  };

  const handleStartAdd = () => {
    setIsAdding(true);
    setSelectedPetId(null);
    setIsEditingNotes(false);
    setOptimisticLockError(null);
    clearPendingAvatar();
    setPetForm({
      name: '',
      species: 'CAT',
      gender: 'MALE',
      neutered: false,
      weight: undefined,
      birthYear: new Date().getFullYear(),
      photoUrl: '',
      medicalPersonalityNotes: '',
      environmentalNotes: ''
    });
  };

  const handleFormChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
    const { name, value, type } = e.target;
    if (type === 'checkbox') {
      const checked = (e.target as HTMLInputElement).checked;
      setPetForm((prev) => ({ ...prev, [name]: checked }));
    } else {
      setPetForm((prev) => ({
        ...prev,
        [name]:
          name === 'weight' || name === 'birthYear' ? (value ? Number(value) : undefined) : value
      }));
    }
  };

  const handleNotesChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const { name, value } = e.target;
    setNotesForm((prev) => ({ ...prev, [name]: value }));
  };

  // 儲存基本資料 (新建或更新)
  const handleSavePet = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!petForm.name.trim()) {
      showToast('error', '請輸入毛孩名字');
      return;
    }
    if (!petForm.species.trim()) {
      showToast('error', '請選擇或輸入毛孩物種');
      return;
    }

    try {
      if (isAdding) {
        const newPet = await createPetMutation.mutateAsync(petForm);
        setIsAdding(false);
        if (newPet.id && pendingAvatarFile) {
          try {
            const photoUrl = await uploadAvatarMutation.mutateAsync({
              petId: newPet.id,
              file: pendingAvatarFile
            });
            newPet.photoUrl = photoUrl;
          } catch (avatarErr) {
            console.error(avatarErr);
            showToast('error', '毛孩資料已建立，但頭像上傳失敗，請稍後於編輯頁重新上傳');
          }
        }
        clearPendingAvatar();
        showToast('success', '成功新增毛孩資料');
        if (newPet.id) {
          handleSelectPet(newPet);
        }
      } else if (selectedPetId && selectedPet) {
        const updated = await updatePetMutation.mutateAsync({
          petId: selectedPetId,
          pet: { ...selectedPet, ...petForm }
        });
        showToast('success', '成功更新基本資料');
        handleSelectPet(updated);
      }
    } catch (err: any) {
      console.error(err);
      showToast('error', err.response?.data?.message || '操作失敗，請稍後再試');
    }
  };

  // 上傳大頭照
  const handleAvatarClick = () => {
    fileInputRef.current?.click();
  };

  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    // 限制大小 3MB
    if (file.size > 3 * 1024 * 1024) {
      showToast('error', '檔案大小不能超過 3MB');
      e.target.value = '';
      return;
    }

    // 新增流程尚未有 petId，先在本地暫存檔案並預覽，等「儲存基本資料」建立好毛孩後再一併上傳
    if (!selectedPetId) {
      if (avatarPreviewUrl) {
        URL.revokeObjectURL(avatarPreviewUrl);
      }
      setPendingAvatarFile(file);
      setAvatarPreviewUrl(URL.createObjectURL(file));
      e.target.value = '';
      return;
    }

    try {
      const photoUrl = await uploadAvatarMutation.mutateAsync({ petId: selectedPetId, file });
      showToast('success', '大頭照上傳成功');
      setPetForm((prev) => ({ ...prev, photoUrl }));
    } catch (err: any) {
      console.error(err);
      showToast('error', err.response?.data?.message || '上傳失敗');
    }
  };

  // 刪除毛孩
  const handleDeletePet = async () => {
    if (!selectedPetId) return;
    if (!window.confirm('確定要刪除此毛孩資料嗎？此操作無法復原。')) return;

    try {
      await deletePetMutation.mutateAsync(selectedPetId);
      showToast('success', '毛孩資料已成功刪除');
      setSelectedPetId(null);
      setIsEditingNotes(false);
    } catch (err: any) {
      console.error(err);
      const errMsg = err.response?.data?.message || err.message || '';
      if (errMsg.includes('進行中的服務') || err.response?.status === 400) {
        showToast('error', '此毛孩尚有進行中的服務，無法刪除');
      } else {
        showToast('error', errMsg || '刪除失敗，請稍後再試');
      }
    }
  };

  // 儲存注意事項 (樂觀鎖控制與比對)
  const handleSaveNotes = async () => {
    if (!selectedPetId || !selectedPet) return;
    setOptimisticLockError(null);

    try {
      const updated = await updateNotesMutation.mutateAsync({
        petId: selectedPetId,
        dto: {
          medicalPersonalityNotes: notesForm.medicalPersonalityNotes,
          environmentalNotes: notesForm.environmentalNotes,
          version: selectedPet.version || 1
        }
      });
      showToast('success', '注意事項已成功儲存');
      setIsEditingNotes(false);
      handleSelectPet(updated);
    } catch (err: any) {
      console.error(err);
      if (
        err.response?.status === 409 ||
        err.message?.includes('409') ||
        err.response?.data?.message?.includes('Optimistic')
      ) {
        setOptimisticLockError('內容已被他人更新，請重新整理後再試。');
        showToast('error', '儲存失敗：內容已在其他地方被修改');
      } else {
        showToast('error', err.response?.data?.message || '儲存失敗，請稍後再試');
      }
    }
  };

  // 重新整理注意事項（解決衝突時載入最新版本）
  const handleRefreshNotes = async () => {
    if (!selectedPetId) return;
    try {
      const latestPet = await fetchPetById(selectedPetId);
      if (latestPet) {
        setNotesForm({
          medicalPersonalityNotes: latestPet.medicalPersonalityNotes || '',
          environmentalNotes: latestPet.environmentalNotes || ''
        });
        setOptimisticLockError(null);
        showToast('success', '已載入最新內容');
      }
    } catch (err) {
      showToast('error', '載入最新資料失敗');
    }
  };

  // 一個內置的 Promise fetcher 以利即時讀取最新資料
  const fetchPetById = async (id: string): Promise<Pet> => {
    const { getPetById } = await import('../../api/petApi');
    return await getPetById(id);
  };

  return (
    <div
      style={{
        padding: '2rem 1.5rem',
        fontFamily: 'var(--font-sans)',
        minHeight: '100vh',
        position: 'relative'
      }}
    >
      {/* Toast Alert */}
      {toast && (
        <div
          data-testid="toast-message"
          style={{
            position: 'fixed',
            top: '24px',
            right: '24px',
            backgroundColor: toast.type === 'success' ? '#10b981' : '#ef4444',
            color: 'white',
            padding: '12px 24px',
            borderRadius: '8px',
            boxShadow: '0 4px 12px rgba(0,0,0,0.15)',
            zIndex: 1000,
            display: 'flex',
            alignItems: 'center',
            gap: '8px',
            fontWeight: '600',
            animation: 'slideIn 0.3s ease'
          }}
        >
          {toast.type === 'success' ? <Check size={18} /> : <AlertCircle size={18} />}
          <span>{toast.message}</span>
        </div>
      )}

      {/* Header */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: '2.5rem'
        }}
      >
        <div>
          <h2
            style={{
              fontSize: '2rem',
              fontWeight: '700',
              color: 'var(--color-on-surface)',
              fontFamily: 'var(--font-display)',
              margin: 0
            }}
          >
            毛孩資料管理
          </h2>
          <p
            style={{
              color: 'var(--color-on-surface-variant)',
              fontSize: '0.875rem',
              marginTop: '0.25rem'
            }}
          >
            在這裡管理您的毛孩基礎資料與每日照護注意事項
          </p>
        </div>
        <button
          onClick={handleStartAdd}
          className="btn-primary"
          data-testid="btn-add-pet"
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: '6px',
            padding: '0.625rem 1.25rem',
            borderRadius: '9999px',
            border: 'none',
            fontSize: '0.875rem',
            fontWeight: '600',
            cursor: 'pointer',
            boxShadow: 'var(--shadow-sm)'
          }}
        >
          <Plus size={16} />
          新增毛孩
        </button>
      </div>

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: '1fr',
          gap: '2rem',
          alignItems: 'start'
        }}
      >
        {/* 左側毛孩清單 */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
          <h3
            style={{
              fontSize: '1.125rem',
              fontWeight: '700',
              margin: '0 0 0.5rem 0',
              color: 'var(--color-on-surface)'
            }}
          >
            毛孩清單 ({pets.length})
          </h3>

          {isLoadingPets ? (
            <div
              style={{
                textAlign: 'center',
                padding: '3rem 0',
                color: 'var(--color-on-surface-variant)'
              }}
            >
              <Loader2 className="animate-spin" size={24} style={{ margin: '0 auto 0.5rem' }} />
              載入中...
            </div>
          ) : pets.length === 0 ? (
            <div
              style={{
                textAlign: 'center',
                padding: '3rem 1.5rem',
                border: '2px dashed var(--color-surface-high)',
                borderRadius: '16px',
                backgroundColor: 'var(--color-surface-low)'
              }}
            >
              <p
                style={{
                  color: 'var(--color-on-surface-variant)',
                  fontSize: '0.875rem',
                  marginBottom: '1rem'
                }}
              >
                目前尚未建立任何毛孩資料
              </p>
              <button
                onClick={handleStartAdd}
                className="btn-primary"
                style={{ padding: '6px 16px', fontSize: '0.875rem', borderRadius: '9999px' }}
              >
                立即新增
              </button>
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
              {pets.map((pet) => {
                const isActive = pet.id === selectedPetId;
                return (
                  <div
                    key={pet.id}
                    onClick={() => handleSelectPet(pet)}
                    data-testid="pet-card"
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      padding: '1rem',
                      borderRadius: '16px',
                      backgroundColor: isActive
                        ? 'var(--color-primary-container)'
                        : 'var(--color-surface-low)',
                      border: isActive ? '2px solid var(--color-primary)' : '2px solid transparent',
                      cursor: 'pointer',
                      transition: 'all 0.2s ease',
                      boxShadow: isActive ? 'var(--shadow-md)' : 'none'
                    }}
                  >
                    {/* 頭像 */}
                    <div
                      style={{
                        width: '50px',
                        height: '50px',
                        borderRadius: '12px',
                        backgroundColor: 'var(--color-surface-high)',
                        overflow: 'hidden',
                        marginRight: '1rem',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        border: '1px solid var(--color-surface-high)'
                      }}
                    >
                      {pet.photoUrl ? (
                        <img
                          src={pet.photoUrl}
                          alt={pet.name}
                          style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                        />
                      ) : (
                        <span
                          style={{
                            fontSize: '1.25rem',
                            fontWeight: 'bold',
                            color: 'var(--color-primary)'
                          }}
                        >
                          {pet.name[0]}
                        </span>
                      )}
                    </div>

                    {/* 毛孩簡短資訊 */}
                    <div style={{ flex: 1 }}>
                      <div
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'space-between'
                        }}
                      >
                        <h4
                          style={{
                            margin: 0,
                            fontSize: '1rem',
                            fontWeight: '700',
                            color: 'var(--color-on-surface)'
                          }}
                        >
                          {pet.name}
                        </h4>
                        <span
                          style={{
                            fontSize: '0.75rem',
                            padding: '2px 8px',
                            borderRadius: '9999px',
                            backgroundColor: 'var(--color-surface-high)',
                            color: 'var(--color-on-surface-variant)',
                            fontWeight: '600'
                          }}
                        >
                          {pet.species === 'CAT'
                            ? '🐱 貓咪'
                            : pet.species === 'DOG'
                              ? '🐶 狗狗'
                              : `🐾 ${pet.species}`}
                        </span>
                      </div>
                      <p
                        style={{
                          margin: '0.25rem 0 0 0',
                          fontSize: '0.75rem',
                          color: 'var(--color-on-surface-variant)'
                        }}
                      >
                        {pet.gender === 'MALE' ? '男生' : '女生'} ·{' '}
                        {pet.weight ? `${pet.weight} kg` : '-- kg'} ·{' '}
                        {pet.birthYear ? `${new Date().getFullYear() - pet.birthYear}歲` : '--歲'}
                      </p>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* 右側詳細資料與編輯欄 */}
        <div style={{ minHeight: '400px' }}>
          {!selectedPet && !isAdding ? (
            <div
              style={{
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                justifyContent: 'center',
                height: '100%',
                minHeight: '400px',
                border: '2px dashed var(--color-surface-high)',
                borderRadius: '24px',
                backgroundColor: 'var(--color-surface-low)',
                padding: '2rem'
              }}
            >
              <User
                size={48}
                style={{
                  color: 'var(--color-on-surface-variant)',
                  marginBottom: '1rem',
                  opacity: 0.5
                }}
              />
              <h4
                style={{
                  fontSize: '1.125rem',
                  fontWeight: '700',
                  color: 'var(--color-on-surface)',
                  margin: '0 0 0.5rem 0'
                }}
              >
                請選取毛孩
              </h4>
              <p
                style={{
                  color: 'var(--color-on-surface-variant)',
                  fontSize: '0.875rem',
                  margin: 0
                }}
              >
                點擊左側毛孩卡片查看詳細資料，或點擊「新增毛孩」建立新檔案
              </p>
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '2rem' }}>
              {/* 卡片：基本資料編輯 */}
              <div
                style={{
                  backgroundColor: 'var(--color-surface-low)',
                  borderRadius: '24px',
                  padding: '2rem',
                  boxShadow: 'var(--shadow-sm)'
                }}
              >
                <div
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    marginBottom: '1.5rem',
                    borderBottom: '1px solid var(--color-surface-high)',
                    paddingBottom: '1rem'
                  }}
                >
                  <h3
                    style={{
                      fontSize: '1.25rem',
                      fontWeight: '700',
                      color: 'var(--color-on-surface)',
                      margin: 0,
                      display: 'flex',
                      alignItems: 'center',
                      gap: '8px'
                    }}
                  >
                    <User size={20} />
                    {isAdding ? '建立新毛孩檔案' : `編輯 ${selectedPet?.name} 的基本資料`}
                  </h3>
                  {!isAdding && selectedPetId && (
                    <button
                      onClick={handleDeletePet}
                      data-testid="btn-delete-pet"
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: '4px',
                        padding: '6px 12px',
                        backgroundColor: 'transparent',
                        border: '1px solid #ef4444',
                        borderRadius: '8px',
                        color: '#ef4444',
                        fontSize: '0.75rem',
                        fontWeight: '600',
                        cursor: 'pointer'
                      }}
                    >
                      <Trash2 size={14} />
                      刪除此毛孩
                    </button>
                  )}
                </div>

                <form onSubmit={handleSavePet}>
                  <div
                    style={{
                      display: 'flex',
                      flexDirection: 'column',
                      gap: '1.5rem',
                      marginBottom: '1.5rem'
                    }}
                  >
                    {/* 大頭照區塊 */}
                    <div
                      style={{
                        display: 'flex',
                        flexDirection: 'column',
                        alignItems: 'center',
                        gap: '0.5rem'
                      }}
                    >
                      <div
                        onClick={handleAvatarClick}
                        data-testid="avatar-upload-trigger"
                        style={{
                          width: '120px',
                          height: '120px',
                          borderRadius: '24px',
                          backgroundColor: 'var(--color-surface-high)',
                          overflow: 'hidden',
                          position: 'relative',
                          cursor: 'pointer',
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          border: '2px solid var(--color-surface-high)'
                        }}
                      >
                        {avatarPreviewUrl || petForm.photoUrl ? (
                          <img
                            src={avatarPreviewUrl || petForm.photoUrl}
                            alt="Avatar"
                            style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                          />
                        ) : (
                          <div
                            style={{
                              display: 'flex',
                              flexDirection: 'column',
                              alignItems: 'center',
                              color: 'var(--color-on-surface-variant)'
                            }}
                          >
                            <Upload size={24} style={{ marginBottom: '4px' }} />
                            <span style={{ fontSize: '0.6875rem' }}>上傳頭像</span>
                          </div>
                        )}
                        <div
                          style={{
                            position: 'absolute',
                            bottom: 0,
                            left: 0,
                            right: 0,
                            backgroundColor: 'rgba(0,0,0,0.5)',
                            color: 'white',
                            fontSize: '10px',
                            textAlign: 'center',
                            padding: '4px 0'
                          }}
                        >
                          點擊更換
                        </div>
                      </div>
                      <input
                        type="file"
                        ref={fileInputRef}
                        onChange={handleFileChange}
                        accept="image/*"
                        data-testid="avatar-file-input"
                        style={{ display: 'none' }}
                      />
                      <span
                        style={{
                          fontSize: '0.6875rem',
                          color: 'var(--color-on-surface-variant)',
                          textAlign: 'center'
                        }}
                      >
                        不超過 3MB
                      </span>
                    </div>

                    {/* 輸入表單 */}
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
                      <div>
                        <label
                          style={{
                            display: 'block',
                            fontSize: '0.75rem',
                            fontWeight: '700',
                            marginBottom: '0.375rem',
                            color: 'var(--color-on-surface-variant)'
                          }}
                        >
                          毛孩名字 *
                        </label>
                        <input
                          type="text"
                          name="name"
                          value={petForm.name}
                          onChange={handleFormChange}
                          data-testid="input-pet-name"
                          placeholder="例如：咪咪"
                          style={{
                            width: '100%',
                            padding: '0.625rem',
                            borderRadius: '8px',
                            border: '1px solid var(--color-surface-high)',
                            backgroundColor: 'white',
                            fontSize: '0.875rem',
                            color: 'var(--color-on-surface)'
                          }}
                          required
                        />
                      </div>

                      <div>
                        <label
                          style={{
                            display: 'block',
                            fontSize: '0.75rem',
                            fontWeight: '700',
                            marginBottom: '0.375rem',
                            color: 'var(--color-on-surface-variant)'
                          }}
                        >
                          物種 *
                        </label>
                        <select
                          name="species"
                          value={petForm.species}
                          onChange={handleFormChange}
                          data-testid="select-pet-species"
                          style={{
                            width: '100%',
                            padding: '0.625rem',
                            borderRadius: '8px',
                            border: '1px solid var(--color-surface-high)',
                            backgroundColor: 'white',
                            fontSize: '0.875rem',
                            color: 'var(--color-on-surface)'
                          }}
                        >
                          <option value="CAT">貓咪 (CAT)</option>
                          <option value="DOG">狗狗 (DOG)</option>
                          <option value="BIRD">鳥類 (BIRD)</option>
                          <option value="MOUSE">鼠類 (MOUSE)</option>
                          <option value="RABBIT">兔子 (RABBIT)</option>
                          <option value="REPTILE">爬蟲 (REPTILE)</option>
                          <option value="INSECT">昆蟲 (INSECT)</option>
                          <option value="OTHER">其他 (OTHER)</option>
                        </select>
                      </div>

                      <div>
                        <label
                          style={{
                            display: 'block',
                            fontSize: '0.75rem',
                            fontWeight: '700',
                            marginBottom: '0.375rem',
                            color: 'var(--color-on-surface-variant)'
                          }}
                        >
                          性別
                        </label>
                        <select
                          name="gender"
                          value={petForm.gender}
                          onChange={handleFormChange}
                          data-testid="select-pet-gender"
                          style={{
                            width: '100%',
                            padding: '0.625rem',
                            borderRadius: '8px',
                            border: '1px solid var(--color-surface-high)',
                            backgroundColor: 'white',
                            fontSize: '0.875rem',
                            color: 'var(--color-on-surface)'
                          }}
                        >
                          <option value="MALE">男生 (MALE)</option>
                          <option value="FEMALE">女生 (FEMALE)</option>
                        </select>
                      </div>

                      <div>
                        <label
                          style={{
                            display: 'block',
                            fontSize: '0.75rem',
                            fontWeight: '700',
                            marginBottom: '0.375rem',
                            color: 'var(--color-on-surface-variant)'
                          }}
                        >
                          體重 (kg)
                        </label>
                        <input
                          type="number"
                          name="weight"
                          step="0.01"
                          value={petForm.weight === undefined ? '' : petForm.weight}
                          onChange={handleFormChange}
                          data-testid="input-pet-weight"
                          placeholder="例如：4.5"
                          style={{
                            width: '100%',
                            padding: '0.625rem',
                            borderRadius: '8px',
                            border: '1px solid var(--color-surface-high)',
                            backgroundColor: 'white',
                            fontSize: '0.875rem',
                            color: 'var(--color-on-surface)'
                          }}
                        />
                      </div>

                      <div>
                        <label
                          style={{
                            display: 'block',
                            fontSize: '0.75rem',
                            fontWeight: '700',
                            marginBottom: '0.375rem',
                            color: 'var(--color-on-surface-variant)'
                          }}
                        >
                          出生年份
                        </label>
                        <input
                          type="number"
                          name="birthYear"
                          value={petForm.birthYear === undefined ? '' : petForm.birthYear}
                          onChange={handleFormChange}
                          data-testid="input-pet-birth-year"
                          placeholder="例如：2020"
                          style={{
                            width: '100%',
                            padding: '0.625rem',
                            borderRadius: '8px',
                            border: '1px solid var(--color-surface-high)',
                            backgroundColor: 'white',
                            fontSize: '0.875rem',
                            color: 'var(--color-on-surface)'
                          }}
                        />
                      </div>

                      <div
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          height: '100%',
                          paddingTop: '1.25rem'
                        }}
                      >
                        <label
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            gap: '8px',
                            cursor: 'pointer',
                            fontSize: '0.875rem',
                            color: 'var(--color-on-surface)'
                          }}
                        >
                          <input
                            type="checkbox"
                            name="neutered"
                            checked={petForm.neutered}
                            onChange={handleFormChange}
                            data-testid="checkbox-pet-neutered"
                            style={{
                              width: '18px',
                              height: '18px',
                              accentColor: 'var(--color-primary)'
                            }}
                          />
                          是否已結紮
                        </label>
                      </div>
                    </div>
                  </div>

                  <div
                    style={{
                      display: 'flex',
                      justifyContent: 'flex-end',
                      gap: '1rem',
                      borderTop: '1px solid var(--color-surface-high)',
                      paddingTop: '1rem'
                    }}
                  >
                    {isAdding && (
                      <button
                        type="button"
                        onClick={() => setIsAdding(false)}
                        className="btn-secondary"
                        style={{
                          padding: '0.5rem 1.25rem',
                          borderRadius: '8px',
                          border: 'none',
                          fontSize: '0.875rem',
                          cursor: 'pointer'
                        }}
                      >
                        取消
                      </button>
                    )}
                    <button
                      type="submit"
                      className="btn-primary"
                      data-testid="btn-save-pet"
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: '6px',
                        padding: '0.5rem 1.25rem',
                        borderRadius: '8px',
                        border: 'none',
                        fontSize: '0.875rem',
                        fontWeight: '600',
                        cursor: 'pointer'
                      }}
                    >
                      <Save size={16} />
                      儲存基本資料
                    </button>
                  </div>
                </form>
              </div>

              {/* 注意事項與編輯區 */}
              {!isAdding && selectedPet && (
                <div
                  style={{
                    backgroundColor: 'var(--color-surface-low)',
                    borderRadius: '24px',
                    padding: '2rem',
                    boxShadow: 'var(--shadow-sm)'
                  }}
                >
                  <div
                    style={{
                      display: 'flex',
                      justifyContent: 'space-between',
                      alignItems: 'center',
                      marginBottom: '1.5rem',
                      borderBottom: '1px solid var(--color-surface-high)',
                      paddingBottom: '1rem'
                    }}
                  >
                    <h3
                      style={{
                        fontSize: '1.25rem',
                        fontWeight: '700',
                        color: 'var(--color-on-surface)',
                        margin: 0,
                        display: 'flex',
                        alignItems: 'center',
                        gap: '8px'
                      }}
                    >
                      <FileText size={20} />
                      照護注意事項 (共同編輯)
                    </h3>

                    <div style={{ display: 'flex', gap: '0.5rem' }}>
                      <button
                        onClick={() => setShowLogsModal(true)}
                        data-testid="btn-view-logs"
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: '4px',
                          padding: '6px 12px',
                          backgroundColor: 'var(--color-surface-high)',
                          border: 'none',
                          borderRadius: '8px',
                          color: 'var(--color-on-surface)',
                          fontSize: '0.75rem',
                          fontWeight: '600',
                          cursor: 'pointer'
                        }}
                      >
                        <History size={14} />
                        編輯紀錄
                      </button>

                      {!isEditingNotes ? (
                        <button
                          onClick={() => {
                            setIsEditingNotes(true);
                            setOptimisticLockError(null);
                          }}
                          className="btn-primary"
                          data-testid="btn-edit-notes"
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            gap: '4px',
                            padding: '6px 12px',
                            borderRadius: '8px',
                            border: 'none',
                            fontSize: '0.75rem',
                            fontWeight: '600',
                            cursor: 'pointer'
                          }}
                        >
                          <Edit size={14} />
                          編輯注意事項
                        </button>
                      ) : (
                        <div style={{ display: 'flex', gap: '0.5rem' }}>
                          <button
                            onClick={() => {
                              setIsEditingNotes(false);
                              setOptimisticLockError(null);
                              setNotesForm({
                                medicalPersonalityNotes: selectedPet.medicalPersonalityNotes || '',
                                environmentalNotes: selectedPet.environmentalNotes || ''
                              });
                            }}
                            className="btn-secondary"
                            style={{
                              padding: '6px 12px',
                              borderRadius: '8px',
                              border: 'none',
                              fontSize: '0.75rem',
                              cursor: 'pointer'
                            }}
                          >
                            取消
                          </button>
                          <button
                            onClick={handleSaveNotes}
                            className="btn-primary"
                            data-testid="btn-save-notes"
                            style={{
                              display: 'flex',
                              alignItems: 'center',
                              gap: '4px',
                              padding: '6px 12px',
                              borderRadius: '8px',
                              border: 'none',
                              fontSize: '0.75rem',
                              fontWeight: '600',
                              cursor: 'pointer'
                            }}
                          >
                            <Save size={14} />
                            儲存變更
                          </button>
                        </div>
                      )}
                    </div>
                  </div>

                  {/* 樂觀鎖衝突提示 */}
                  {optimisticLockError && (
                    <div
                      data-testid="lock-conflict-alert"
                      style={{
                        backgroundColor: '#fef2f2',
                        border: '1px solid #fee2e2',
                        borderRadius: '12px',
                        padding: '1rem',
                        marginBottom: '1.5rem',
                        display: 'flex',
                        flexDirection: 'column',
                        gap: '0.5rem'
                      }}
                    >
                      <div
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: '8px',
                          color: '#ef4444',
                          fontWeight: '700',
                          fontSize: '0.875rem'
                        }}
                      >
                        <AlertCircle size={18} />
                        <span>樂觀鎖衝突警告</span>
                      </div>
                      <p style={{ margin: 0, fontSize: '0.8125rem', color: '#7f1d1d' }}>
                        {optimisticLockError}
                      </p>
                      <button
                        onClick={handleRefreshNotes}
                        style={{
                          alignSelf: 'flex-start',
                          padding: '4px 10px',
                          backgroundColor: '#ef4444',
                          color: 'white',
                          border: 'none',
                          borderRadius: '6px',
                          fontSize: '0.75rem',
                          fontWeight: '600',
                          cursor: 'pointer',
                          marginTop: '4px'
                        }}
                      >
                        覆蓋並載入最新內容 (放棄目前的修改)
                      </button>
                    </div>
                  )}

                  <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
                    {/* 區塊一：醫療與個性備註 */}
                    <div
                      style={{
                        backgroundColor: 'white',
                        borderRadius: '16px',
                        padding: '1.5rem',
                        border: '1px solid var(--color-surface-high)'
                      }}
                    >
                      <h4
                        style={{
                          margin: '0 0 1rem 0',
                          fontSize: '0.9375rem',
                          fontWeight: '700',
                          color: 'var(--color-on-surface)',
                          display: 'flex',
                          alignItems: 'center',
                          gap: '6px'
                        }}
                      >
                        <Activity size={16} style={{ color: 'var(--color-primary)' }} />
                        1. 醫療、飲食與個性備註
                      </h4>

                      {isEditingNotes ? (
                        <textarea
                          name="medicalPersonalityNotes"
                          value={notesForm.medicalPersonalityNotes}
                          onChange={handleNotesChange}
                          data-testid="textarea-medical-notes"
                          rows={6}
                          placeholder="請輸入過敏史、醫療用藥、日常飲食習慣及貓咪個性注意事項..."
                          style={{
                            width: '100%',
                            padding: '0.75rem',
                            borderRadius: '8px',
                            border: '1px solid var(--color-surface-high)',
                            fontSize: '0.875rem',
                            lineHeight: '1.5',
                            resize: 'vertical'
                          }}
                        />
                      ) : (
                        <div
                          style={{
                            fontSize: '0.875rem',
                            color: selectedPet.medicalPersonalityNotes
                              ? 'var(--color-on-surface)'
                              : 'var(--color-on-surface-variant)',
                            whiteSpace: 'pre-line',
                            lineHeight: '1.6',
                            minHeight: '80px',
                            fontStyle: selectedPet.medicalPersonalityNotes ? 'normal' : 'italic'
                          }}
                        >
                          {selectedPet.medicalPersonalityNotes || '尚無備註資訊。'}
                        </div>
                      )}
                    </div>

                    {/* 區塊二：環境備註 */}
                    <div
                      style={{
                        backgroundColor: 'white',
                        borderRadius: '16px',
                        padding: '1.5rem',
                        border: '1px solid var(--color-surface-high)'
                      }}
                    >
                      <h4
                        style={{
                          margin: '0 0 1rem 0',
                          fontSize: '0.9375rem',
                          fontWeight: '700',
                          color: 'var(--color-on-surface)',
                          display: 'flex',
                          alignItems: 'center',
                          gap: '6px'
                        }}
                      >
                        <FileText size={16} style={{ color: 'var(--color-primary)' }} />
                        2. 環境與行為備註
                      </h4>

                      {isEditingNotes ? (
                        <textarea
                          name="environmentalNotes"
                          value={notesForm.environmentalNotes}
                          onChange={handleNotesChange}
                          data-testid="textarea-environmental-notes"
                          rows={6}
                          placeholder="請輸入家中擺設限制、開關門禁忌、敏感行為或特定環境管理注意事項..."
                          style={{
                            width: '100%',
                            padding: '0.75rem',
                            borderRadius: '8px',
                            border: '1px solid var(--color-surface-high)',
                            fontSize: '0.875rem',
                            lineHeight: '1.5',
                            resize: 'vertical'
                          }}
                        />
                      ) : (
                        <div
                          style={{
                            fontSize: '0.875rem',
                            color: selectedPet.environmentalNotes
                              ? 'var(--color-on-surface)'
                              : 'var(--color-on-surface-variant)',
                            whiteSpace: 'pre-line',
                            lineHeight: '1.6',
                            minHeight: '80px',
                            fontStyle: selectedPet.environmentalNotes ? 'normal' : 'italic'
                          }}
                        >
                          {selectedPet.environmentalNotes || '尚無備註資訊。'}
                        </div>
                      )}
                    </div>
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {/* 編輯歷程 Modal */}
      {showLogsModal && selectedPetId && (
        <div
          style={{
            position: 'fixed',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            backgroundColor: 'rgba(0,0,0,0.5)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1100,
            backdropFilter: 'blur(4px)'
          }}
        >
          <div
            style={{
              backgroundColor: 'white',
              width: '600px',
              maxHeight: '80vh',
              borderRadius: '24px',
              display: 'flex',
              flexDirection: 'column',
              overflow: 'hidden',
              boxShadow: 'var(--shadow-lg)'
            }}
          >
            {/* Modal Header */}
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                padding: '1.5rem',
                borderBottom: '1px solid var(--color-surface-high)'
              }}
            >
              <h3
                data-testid="logs-modal-title"
                style={{
                  margin: 0,
                  fontSize: '1.25rem',
                  fontWeight: '700',
                  color: 'var(--color-on-surface)'
                }}
              >
                {selectedPet?.name} 的注意事項異動紀錄
              </h3>
              <button
                onClick={() => setShowLogsModal(false)}
                style={{
                  border: 'none',
                  backgroundColor: 'transparent',
                  cursor: 'pointer',
                  color: 'var(--color-on-surface-variant)'
                }}
              >
                <X size={20} />
              </button>
            </div>

            {/* Modal Content */}
            <div
              style={{
                padding: '1.5rem',
                overflowY: 'auto',
                flex: 1,
                backgroundColor: 'var(--color-surface-low)',
                display: 'flex',
                flexDirection: 'column',
                gap: '1rem'
              }}
            >
              {isLoadingLogs ? (
                <div style={{ textAlign: 'center', padding: '2rem 0' }}>載入日誌中...</div>
              ) : editLogs.length === 0 ? (
                <div
                  style={{
                    textAlign: 'center',
                    padding: '2rem 0',
                    color: 'var(--color-on-surface-variant)'
                  }}
                >
                  尚無任何修改紀錄
                </div>
              ) : (
                editLogs.map((log) => {
                  const roleMap = {
                    OWNER: '飼主',
                    SITTER: '保母',
                    ADMIN: '系統管理員'
                  };
                  const roleName = roleMap[log.diffSummary?.editorRole || 'OWNER'] || '使用者';

                  return (
                    <div
                      key={log.id}
                      data-testid="log-item"
                      style={{
                        backgroundColor: 'white',
                        padding: '1rem',
                        borderRadius: '16px',
                        border: '1px solid var(--color-surface-high)',
                        fontSize: '0.8125rem'
                      }}
                    >
                      <div
                        style={{
                          display: 'flex',
                          justifyContent: 'space-between',
                          marginBottom: '0.5rem',
                          color: 'var(--color-on-surface-variant)'
                        }}
                      >
                        <span style={{ fontWeight: '700', color: 'var(--color-primary)' }}>
                          編輯者角色：{roleName}
                        </span>
                        <span>{new Date(log.createdAt).toLocaleString()}</span>
                      </div>

                      {log.diffSummary?.changes ? (
                        <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                          {log.diffSummary.changes.medicalPersonalityNotes && (
                            <div
                              style={{
                                borderLeft: '3px solid var(--color-primary)',
                                paddingLeft: '8px'
                              }}
                            >
                              <strong>醫療/個性備註變更：</strong>
                              <div
                                style={{
                                  color: '#ef4444',
                                  textDecoration: 'line-through',
                                  margin: '2px 0'
                                }}
                              >
                                原內容：
                                {log.diffSummary.changes.medicalPersonalityNotes.before || '（空）'}
                              </div>
                              <div style={{ color: '#10b981', margin: '2px 0' }}>
                                新內容：
                                {log.diffSummary.changes.medicalPersonalityNotes.after || '（空）'}
                              </div>
                            </div>
                          )}

                          {log.diffSummary.changes.environmentalNotes && (
                            <div style={{ borderLeft: '3px solid #10b981', paddingLeft: '8px' }}>
                              <strong>環境/行為備註變更：</strong>
                              <div
                                style={{
                                  color: '#ef4444',
                                  textDecoration: 'line-through',
                                  margin: '2px 0'
                                }}
                              >
                                原內容：
                                {log.diffSummary.changes.environmentalNotes.before || '（空）'}
                              </div>
                              <div style={{ color: '#10b981', margin: '2px 0' }}>
                                新內容：
                                {log.diffSummary.changes.environmentalNotes.after || '（空）'}
                              </div>
                            </div>
                          )}
                        </div>
                      ) : (
                        <span style={{ color: 'var(--color-on-surface-variant)' }}>無變更詳情</span>
                      )}
                    </div>
                  );
                })
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default PetManager;
