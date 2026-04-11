import React, { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { motion, AnimatePresence } from 'framer-motion'
import { sitterService } from '../../services/api'

const SPECIES_OPTIONS = [
  { value: 'DOG', label: '犬' },
  { value: 'CAT', label: '貓' },
  { value: 'HAMSTER', label: '鼠' },
  { value: 'RABBIT', label: '兔' },
  { value: 'BIRD', label: '鳥' },
  { value: 'OTHER', label: '其他' },
]

const DEFAULT_FORM = {
  name: '',
  species: [],
  basePrice: '',
  durationMinutes: '',
  isActive: true,
  effectiveStart: '',
  effectiveEnd: '',
  bookableStartDate: '',
  bookableEndDate: '',
}

// Compute isActive from effective date range. Returns null if no range set (use stored isActive).
const computeActiveFromRange = (effectiveStart, effectiveEnd) => {
  if (!effectiveStart || !effectiveEnd) return null
  const today = new Date().toISOString().slice(0, 10)
  return today >= effectiveStart && today <= effectiveEnd
}

const ServicePackages = () => {
  const navigate = useNavigate()
  const [packages, setPackages] = useState([])
  const [isLoading, setIsLoading] = useState(true)
  const [isEditing, setIsEditing] = useState(false)
  const [currentPkg, setCurrentPkg] = useState(null)
  const [form, setForm] = useState(DEFAULT_FORM)
  const [isSaving, setIsSaving] = useState(false)
  const [validationError, setValidationError] = useState('')

  useEffect(() => {
    document.querySelector('main')?.scrollTo({ top: 0, behavior: 'instant' })
    fetchPackages()
  }, [])

  const fetchPackages = async () => {
    try {
      setIsLoading(true)
      const data = await sitterService.list()
      // Compute live isActive from effective date range on load
      const enriched = data.map(pkg => {
        const rangeActive = computeActiveFromRange(pkg.effectiveStartDate, pkg.effectiveEndDate)
        return rangeActive !== null ? { ...pkg, isActive: rangeActive } : pkg
      })
      setPackages(enriched)
    } catch (e) {
      console.error('Failed to fetch packages:', e)
    } finally {
      setIsLoading(false)
    }
  }

  const openEdit = (pkg = null) => {
    setValidationError('')
    if (pkg) {
      setForm({
        name: pkg.name || '',
        species: pkg.supportedPetTypes || [],
        basePrice: pkg.basePrice || '',
        durationMinutes: pkg.durationMinutes || '',
        isActive: pkg.isActive ?? true,
        effectiveStart: pkg.effectiveStartDate || '',
        effectiveEnd: pkg.effectiveEndDate || '',
        bookableStartDate: pkg.bookableStartDate || '',
        bookableEndDate: pkg.bookableEndDate || '',
      })
    } else {
      setForm(DEFAULT_FORM)
    }
    setCurrentPkg(pkg)
    setIsEditing(true)
  }

  const handleToggleSpecies = (val) => {
    setForm(prev => ({
      ...prev,
      species: prev.species.includes(val)
        ? prev.species.filter(s => s !== val)
        : [...prev.species, val],
    }))
  }

  const validate = () => {
    if (!form.name.trim()) return '請填寫方案名稱'
    if (form.species.length === 0) return '請至少選擇一種適用種類'
    if (!form.basePrice || Number(form.basePrice) <= 0) return '請填寫有效的基本價格（必須大於 0）'
    if (!form.durationMinutes || Number(form.durationMinutes) <= 0) return '請填寫有效的時間長度（必須大於 0）'
    const hasStart = !!form.effectiveStart
    const hasEnd = !!form.effectiveEnd
    if (hasStart !== hasEnd) return '生效日期起訖必須同時填寫或同時留空'
    if (hasStart && hasEnd && form.effectiveStart > form.effectiveEnd) return '生效開始日期不能晚於結束日期'
    const hasBookStart = !!form.bookableStartDate
    const hasBookEnd = !!form.bookableEndDate
    if (hasBookStart !== hasBookEnd) return '可接單日期起訖必須同時填寫或同時留空'
    return ''
  }

  const handleSave = async () => {
    const err = validate()
    if (err) { setValidationError(err); return }
    setValidationError('')
    setIsSaving(true)
    try {
      // Compute isActive from effective date range; fall back to manual toggle
      const rangeActive = computeActiveFromRange(form.effectiveStart, form.effectiveEnd)
      const isActive = rangeActive !== null ? rangeActive : form.isActive

      const payload = {
        name: form.name.trim(),
        supportedPetTypes: form.species,
        basePrice: Number(form.basePrice),
        durationMinutes: Number(form.durationMinutes),
        isActive,
        effectiveStartDate: form.effectiveStart || null,
        effectiveEndDate: form.effectiveEnd || null,
        bookableStartDate: form.bookableStartDate || null,
        bookableEndDate: form.bookableEndDate || null,
      }

      if (currentPkg) {
        const updated = await sitterService.update(currentPkg.id, payload)
        setPackages(prev => prev.map(p => p.id === currentPkg.id ? updated : p))
      } else {
        const created = await sitterService.create(payload)
        setPackages(prev => [...prev, created])
      }
      setIsEditing(false)
    } catch (e) {
      console.error('Save failed:', e)
      setValidationError('儲存失敗，請再試一次')
    } finally {
      setIsSaving(false)
    }
  }

  const handleToggleActive = async (pkg) => {
    // If package has an effective date range, direct toggle is not allowed
    if (pkg.effectiveStartDate && pkg.effectiveEndDate) return
    try {
      const updated = await sitterService.update(pkg.id, {
        name: pkg.name,
        supportedPetTypes: pkg.supportedPetTypes,
        basePrice: pkg.basePrice,
        durationMinutes: pkg.durationMinutes,
        isActive: !pkg.isActive,
        effectiveStartDate: null,
        effectiveEndDate: null,
        bookableStartDate: pkg.bookableStartDate || null,
        bookableEndDate: pkg.bookableEndDate || null,
      })
      setPackages(prev => prev.map(p => p.id === pkg.id ? updated : p))
    } catch (e) {
      console.error('Toggle active failed:', e)
    }
  }

  const handleDelete = async (pkg) => {
    if (!window.confirm(`確定要刪除方案「${pkg.name}」？`)) return
    try {
      await sitterService.delete(pkg.id)
      setPackages(prev => prev.filter(p => p.id !== pkg.id))
    } catch (e) {
      console.error('Delete failed:', e)
    }
  }

  const PackageCard = ({ pkg }) => {
    const hasEffectiveRange = pkg.effectiveStartDate && pkg.effectiveEndDate
    const today = new Date().toISOString().slice(0, 10)
    const isScheduled = hasEffectiveRange && today < pkg.effectiveStartDate
    const isExpired = hasEffectiveRange && today > pkg.effectiveEndDate

    return (
      <motion.div
        layout
        className={`p-6 rounded-[40px] border transition-all ${
          pkg.isActive
            ? 'bg-surface-container-low border-outline-variant/10'
            : 'bg-surface/40 border-outline-variant/5 grayscale opacity-60'
        }`}
      >
        <div className="flex justify-between items-start mb-4">
          <div className="space-y-1.5 flex-1 min-w-0">
            <h4 className="text-lg font-extrabold tracking-tight">{pkg.name}</h4>
            <div className="flex flex-wrap items-center gap-2">
              {(pkg.supportedPetTypes || []).map(s => (
                <span key={s} className="px-2 py-0.5 bg-primary/5 text-primary text-[9px] font-bold rounded-md border border-primary/10 uppercase">
                  {SPECIES_OPTIONS.find(o => o.value === s)?.label || s}
                </span>
              ))}
              {pkg.durationMinutes && (
                <span className="px-2 py-0.5 bg-surface-container text-on-surface-variant text-[9px] font-bold rounded-md border border-outline-variant/10">
                  {pkg.durationMinutes} 分鐘
                </span>
              )}
            </div>
            {hasEffectiveRange && (
              <div className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[9px] font-bold border ${
                isScheduled ? 'bg-amber-500/10 text-amber-600 border-amber-500/20' :
                isExpired   ? 'bg-error/10 text-error border-error/20' :
                              'bg-primary/10 text-primary border-primary/20'
              }`}>
                <span className="material-symbols-outlined text-[11px]">
                  {isScheduled ? 'schedule' : isExpired ? 'event_busy' : 'event_available'}
                </span>
                {isScheduled ? `排程啟用：${pkg.effectiveStartDate}` :
                 isExpired   ? `已到期：${pkg.effectiveEndDate}` :
                               `生效中：${pkg.effectiveStartDate} ~ ${pkg.effectiveEndDate}`}
              </div>
            )}
            {(pkg.bookableStartDate || pkg.bookableEndDate) && (
              <p className="text-[10px] font-bold opacity-40">
                可接單：{pkg.bookableStartDate || '—'} 至 {pkg.bookableEndDate || '—'}
              </p>
            )}
          </div>
          <div className="text-right ml-4 flex-shrink-0">
            <p className="text-2xl font-black font-headline tracking-tighter text-primary">${pkg.basePrice}</p>
            <p className="text-[10px] font-bold opacity-30 uppercase tracking-widest">Base Rate</p>
          </div>
        </div>

        <div className="flex items-center justify-between pt-4 border-t border-outline-variant/5">
          <div className="flex items-center gap-3">
            <button
              onClick={() => openEdit(pkg)}
              className="text-[10px] font-bold text-on-surface-variant hover:text-primary transition-colors flex items-center gap-1.5"
            >
              <span className="material-symbols-outlined text-sm">edit</span>
              編輯
            </button>
            <button
              onClick={() => handleDelete(pkg)}
              className="text-[10px] font-bold text-error/60 hover:text-error transition-colors flex items-center gap-1.5"
            >
              <span className="material-symbols-outlined text-sm">delete</span>
              刪除
            </button>
          </div>
          <button
            onClick={() => handleToggleActive(pkg)}
            disabled={hasEffectiveRange}
            title={hasEffectiveRange ? '由生效日期區間自動控制' : ''}
            className={`px-4 py-1.5 rounded-full text-[10px] font-black tracking-widest transition-all ${
              hasEffectiveRange ? 'cursor-default opacity-60' :
              pkg.isActive
                ? 'bg-primary/10 text-primary border border-primary/20'
                : 'bg-on-surface-variant/5 text-on-surface-variant border border-on-surface-variant/10'
            }`}
          >
            {hasEffectiveRange ? '日期控制' : pkg.isActive ? '啟用中' : '已停用'}
          </button>
        </div>
      </motion.div>
    )
  }

  const hasEffectiveRange = !!form.effectiveStart && !!form.effectiveEnd
  const previewActive = hasEffectiveRange ? computeActiveFromRange(form.effectiveStart, form.effectiveEnd) : null

  return (
    <div className="min-h-screen bg-surface text-on-surface pb-32">
      {/* Header */}
      <nav className="sticky top-0 z-50 bg-surface/80 backdrop-blur-md px-4 py-4 flex items-center justify-between border-b border-outline-variant/5">
        <button onClick={() => navigate(-1)} className="p-2 -ml-2 text-on-surface-variant hover:text-primary transition-colors">
          <span className="material-symbols-outlined text-2xl">arrow_back</span>
        </button>
        <h1 className="text-sm font-extrabold font-headline uppercase tracking-tighter">服務方案管理</h1>
        <button
          onClick={() => openEdit()}
          className="w-10 h-10 bg-primary text-on-primary rounded-full flex items-center justify-center shadow-lg active:scale-95 transition-all"
        >
          <span className="material-symbols-outlined text-xl">add</span>
        </button>
      </nav>

      <motion.main
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        className="px-5 pt-8 space-y-4 max-w-xl mx-auto"
      >
        {packages.map(pkg => (
          <PackageCard key={pkg.id} pkg={pkg} />
        ))}
        {packages.length === 0 && !isLoading && (
          <div className="p-20 text-center border-2 border-dashed border-outline-variant/10 rounded-[40px] opacity-20 italic">
            尚未設定任何服務方案。
          </div>
        )}
      </motion.main>

      {/* Edit / Create Modal */}
      <AnimatePresence>
        {isEditing && (
          <div className="fixed inset-0 z-[100] flex items-end sm:items-center justify-center p-4">
            <motion.div
              initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
              onClick={() => setIsEditing(false)}
              className="absolute inset-0 bg-black/60 backdrop-blur-sm"
            />
            <motion.div
              initial={{ y: 100, opacity: 0 }} animate={{ y: 0, opacity: 1 }} exit={{ y: 100, opacity: 0 }}
              className="relative w-full max-w-lg bg-surface rounded-[40px] p-8 shadow-2xl space-y-6 max-h-[90vh] overflow-y-auto"
            >
              <h3 className="text-2xl font-black font-headline tracking-tighter">
                {currentPkg ? '編輯方案' : '新增方案'}
              </h3>

              {/* 方案名稱 */}
              <div className="space-y-2">
                <label className="text-sm font-black uppercase tracking-widest text-on-surface-variant/60">方案名稱*</label>
                <input
                  type="text"
                  value={form.name}
                  onChange={e => setForm(p => ({ ...p, name: e.target.value }))}
                  placeholder="例：30 分鐘到府照護"
                  className="w-full px-5 py-3.5 bg-surface-container-low border border-outline-variant/20 rounded-2xl text-sm font-bold outline-none focus:border-primary transition-colors"
                />
              </div>

              {/* 種類（多選） */}
              <div className="space-y-2">
                <label className="text-sm font-black uppercase tracking-widest text-on-surface-variant/60">適用種類（可複選）</label>
                <div className="flex flex-wrap gap-2">
                  {SPECIES_OPTIONS.map(opt => (
                    <button
                      key={opt.value}
                      type="button"
                      onClick={() => handleToggleSpecies(opt.value)}
                      className={`px-4 py-2 rounded-full text-xs font-extrabold border transition-all active:scale-95 ${
                        form.species.includes(opt.value)
                          ? 'bg-primary text-on-primary border-primary shadow-lg shadow-primary/20'
                          : 'bg-surface-container-low border-outline-variant/20 text-on-surface-variant'
                      }`}
                    >
                      {opt.label}
                    </button>
                  ))}
                </div>
              </div>

              {/* 基本價格 + 時間長度 */}
              <div className="grid grid-cols-2 gap-4">
                <div className="space-y-2">
                  <label className="text-sm font-black uppercase tracking-widest text-on-surface-variant/60">基本價格 (元)*</label>
                  <input
                    type="number"
                    value={form.basePrice}
                    onChange={e => setForm(p => ({ ...p, basePrice: e.target.value }))}
                    placeholder="例：800"
                    className="w-full px-4 py-3.5 bg-surface-container-low border border-outline-variant/20 rounded-2xl text-sm font-bold outline-none focus:border-primary transition-colors"
                  />
                </div>
                <div className="space-y-2">
                  <label className="text-sm font-black uppercase tracking-widest text-on-surface-variant/60">時間長度 (分鐘)*</label>
                  <input
                    type="number"
                    value={form.durationMinutes}
                    onChange={e => setForm(p => ({ ...p, durationMinutes: e.target.value }))}
                    placeholder="例：60"
                    className="w-full px-4 py-3.5 bg-surface-container-low border border-outline-variant/20 rounded-2xl text-sm font-bold outline-none focus:border-primary transition-colors"
                  />
                </div>
              </div>

              {/* 生效日期區間 */}
              <div className="space-y-3">
                <div>
                  <label className="text-sm font-black uppercase tracking-widest text-on-surface-variant/60">生效日期區間（選填）</label>
                  <p className="text-[10px] font-bold opacity-30 mt-1">設定後系統將依日期自動開關此方案；兩個日期必須同時填寫或同時留空</p>
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div className="space-y-1">
                    <p className="text-[10px] font-bold opacity-40">生效開始</p>
                    <input
                      type="date"
                      value={form.effectiveStart}
                      onChange={e => setForm(p => ({ ...p, effectiveStart: e.target.value }))}
                      className="w-full px-3 py-3 bg-surface-container-low border border-outline-variant/20 rounded-2xl text-xs font-bold outline-none focus:border-primary transition-colors"
                    />
                  </div>
                  <div className="space-y-1">
                    <p className="text-[10px] font-bold opacity-40">生效結束</p>
                    <input
                      type="date"
                      value={form.effectiveEnd}
                      min={form.effectiveStart || undefined}
                      onChange={e => setForm(p => ({ ...p, effectiveEnd: e.target.value }))}
                      className="w-full px-3 py-3 bg-surface-container-low border border-outline-variant/20 rounded-2xl text-xs font-bold outline-none focus:border-primary transition-colors"
                    />
                  </div>
                </div>
                {hasEffectiveRange && (
                  <div className={`flex items-center gap-2 px-3 py-2 rounded-xl text-[10px] font-bold border ${
                    previewActive
                      ? 'bg-primary/10 text-primary border-primary/20'
                      : 'bg-on-surface-variant/10 text-on-surface-variant border-outline-variant/20'
                  }`}>
                    <span className="material-symbols-outlined text-sm">
                      {previewActive ? 'check_circle' : 'schedule'}
                    </span>
                    儲存後此方案將{previewActive ? '立即啟用' : '暫時停用，於生效開始日啟用'}
                  </div>
                )}
              </div>

              {/* 手動啟用（僅在無日期區間時顯示） */}
              {!hasEffectiveRange && (
                <div className="flex items-center justify-between">
                  <div>
                    <p className="text-xs font-extrabold">方案啟用</p>
                    <p className="text-[10px] font-bold opacity-40 mt-0.5">關閉後客戶將看不到此方案</p>
                  </div>
                  <button
                    type="button"
                    onClick={() => setForm(p => ({ ...p, isActive: !p.isActive }))}
                    className={`w-14 h-7 rounded-full transition-all relative ${form.isActive ? 'bg-primary' : 'bg-surface-container-high'}`}
                  >
                    <motion.div
                      animate={{ x: form.isActive ? 28 : 4 }}
                      className="absolute top-1.5 left-0 w-4 h-4 rounded-full bg-white shadow-sm"
                    />
                  </button>
                </div>
              )}

              {/* 可接單日期區間 */}
              <div className="space-y-2">
                <label className="text-sm font-black uppercase tracking-widest text-on-surface-variant/60">可接單日期區間（選填）</label>
                <p className="text-[10px] font-bold opacity-30">超出此區間的預約日期將顯示「此日期未提供服務」</p>
                <div className="grid grid-cols-2 gap-3">
                  <div className="space-y-1">
                    <p className="text-[10px] font-bold opacity-40">開始日期</p>
                    <input
                      type="date"
                      value={form.bookableStartDate}
                      onChange={e => setForm(p => ({ ...p, bookableStartDate: e.target.value }))}
                      className="w-full px-3 py-3 bg-surface-container-low border border-outline-variant/20 rounded-2xl text-xs font-bold outline-none focus:border-primary transition-colors"
                    />
                  </div>
                  <div className="space-y-1">
                    <p className="text-[10px] font-bold opacity-40">結束日期</p>
                    <input
                      type="date"
                      value={form.bookableEndDate}
                      min={form.bookableStartDate || undefined}
                      onChange={e => setForm(p => ({ ...p, bookableEndDate: e.target.value }))}
                      className="w-full px-3 py-3 bg-surface-container-low border border-outline-variant/20 rounded-2xl text-xs font-bold outline-none focus:border-primary transition-colors"
                    />
                  </div>
                </div>
              </div>

              {validationError && (
                <p className="text-xs font-bold text-error px-1">{validationError}</p>
              )}

              <div className="flex gap-3 pt-2">
                <button
                  onClick={() => setIsEditing(false)}
                  className="flex-1 py-4 bg-surface-container-low border border-outline-variant/20 rounded-full text-sm font-black hover:bg-surface-container transition-colors"
                >
                  取消
                </button>
                <button
                  onClick={handleSave}
                  disabled={isSaving}
                  className="flex-1 py-4 bg-primary text-on-primary rounded-full text-sm font-black shadow-xl shadow-primary/20 disabled:opacity-50 active:scale-95 transition-all"
                >
                  {isSaving ? '儲存中...' : '儲存方案'}
                </button>
              </div>
            </motion.div>
          </div>
        )}
      </AnimatePresence>
    </div>
  )
}

export default ServicePackages
