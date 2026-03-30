import React, { useState, useEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { motion, AnimatePresence, Reorder } from 'framer-motion'
import { questionnaireService } from '../../services/api'

const QuestionnaireEditor = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [questions, setQuestions] = useState([])
  const [isLoading, setIsLoading] = useState(true)
  const [isEditing, setIsEditing] = useState(false)
  const [currentQuestion, setCurrentQuestion] = useState(null)
  const [isSaving, setIsSaving] = useState(false)

  // Form State
  const [formData, setFormData] = useState({
    targetPetType: 'CAT',
    questionText: '',
    type: 'TEXT',
    required: true,
    options: []
  })

  useEffect(() => {
    fetchQuestions()
  }, [])

  const fetchQuestions = async () => {
    try {
      setIsLoading(true)
      const data = await questionnaireService.list()
      setQuestions(data)
    } catch (e) {
      console.error('Failed to fetch questions:', e)
    } finally {
      setIsLoading(false)
    }
  }

  const handleOpenEdit = (q = null) => {
    if (q) {
      setCurrentQuestion(q)
      setFormData({
        targetPetType: q.targetPetType,
        questionText: q.questionText,
        type: q.type,
        required: q.required,
        options: q.options || []
      })
    } else {
      setCurrentQuestion(null)
      setFormData({
        targetPetType: 'CAT',
        questionText: '',
        type: 'TEXT',
        required: true,
        options: []
      })
    }
    setIsEditing(true)
  }

  const handleSave = async () => {
    try {
      setIsSaving(true)
      if (currentQuestion) {
        await questionnaireService.update(currentQuestion.questionId, {
          ...formData,
          sortOrder: currentQuestion.sortOrder,
          isActive: currentQuestion.isActive
        })
      } else {
        await questionnaireService.create({
          ...formData,
          sortOrder: questions.length
        })
      }
      setIsEditing(false)
      fetchQuestions()
    } catch (e) {
      console.error('Save failed:', e)
    } finally {
      setIsSaving(false)
    }
  }

  const handleDelete = async (questionId) => {
    if (!window.confirm('確定要刪除這個問題嗎？這將無法復原。')) return
    
    try {
      await questionnaireService.delete(questionId)
      fetchQuestions()
    } catch (e) {
      console.error('Delete failed:', e)
    }
  }

  const handleReorder = async (newOrder) => {
    setQuestions(newOrder)
    try {
      await questionnaireService.reorder(newOrder.map(q => q.questionId))
    } catch (e) {
      console.error('Reorder failed:', e)
    }
  }

  const handleOptionChange = (index, value) => {
    const newOptions = [...formData.options]
    newOptions[index] = value
    setFormData({ ...formData, options: newOptions })
  }

  const addOption = () => {
    setFormData({ ...formData, options: [...formData.options, ''] })
  }

  const removeOption = (index) => {
    setFormData({ ...formData, options: formData.options.filter((_, i) => i !== index) })
  }

  const getTypeIcon = (type) => {
    switch (type) {
      case 'TEXT': return 'notes'
      case 'SINGLE_CHOICE': return 'radio_button_checked'
      case 'MULTIPLE_CHOICE': return 'check_box'
      default: return 'help'
    }
  }

  const getTypeText = (type) => {
    switch (type) {
      case 'TEXT': return '簡答'
      case 'SINGLE_CHOICE': return '單選'
      case 'MULTIPLE_CHOICE': return '多選'
      default: return '未知'
    }
  }

  const QuestionCard = ({ q }) => (
    <Reorder.Item 
      value={q} 
      id={q.questionId}
      className="bg-surface-container-low p-5 rounded-[24px] border border-outline-variant/10 shadow-sm flex items-center gap-4 active:scale-[0.98] transition-all cursor-grab group"
    >
      <div className="flex-shrink-0 w-10 h-10 rounded-xl bg-primary/10 flex items-center justify-center text-primary">
        <span className="material-symbols-outlined text-xl">{getTypeIcon(q.type)}</span>
      </div>
      
      <div className="flex-grow min-w-0 pr-2">
        <div className="flex items-center gap-2 mb-1">
          <span className="text-[10px] font-black bg-on-surface-variant/5 text-on-surface-variant px-2 py-0.5 rounded-full uppercase tracking-widest text-xs">
            {q.targetPetType === 'CAT' ? '🐱 貓咪' : q.targetPetType}
          </span>
          {q.required && (
            <span className="text-[10px] font-bold text-red-500/80 bg-red-50 px-2 py-0.5 rounded-full uppercase tracking-tighter">必填</span>
          )}
        </div>
        <p className="text-sm font-bold truncate opacity-90">{q.questionText}</p>
        <div className="flex items-center gap-3 mt-1.5 opacity-50">
           <span className="text-[10px] flex items-center gap-1 font-bold">
             <span className="material-symbols-outlined text-[12px]">{getTypeIcon(q.type)}</span>
             {getTypeText(q.type)}
           </span>
           {q.options && q.options.length > 0 && (
             <span className="text-[10px] font-bold">• {q.options.length} 個選項</span>
           )}
        </div>
      </div>

      <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
        <button 
          onClick={(e) => { e.stopPropagation(); handleOpenEdit(q); }}
          className="p-2.5 rounded-full hover:bg-primary/10 text-primary transition-colors"
          title="編輯"
        >
          <span className="material-symbols-outlined text-xl">edit</span>
        </button>
        <button 
          onClick={(e) => { e.stopPropagation(); handleDelete(q.questionId); }}
          className="p-2.5 rounded-full hover:bg-red-50 text-red-500 transition-colors"
          title="刪除代辦"
        >
          <span className="material-symbols-outlined text-xl">delete</span>
        </button>
      </div>
    </Reorder.Item>
  )

  return (
    <div className="min-h-screen bg-surface text-on-surface pb-32 font-body">
      {/* Header */}
      <nav className="sticky top-0 z-50 bg-white/70 backdrop-blur-xl px-4 py-4 flex items-center justify-between border-b border-outline-variant/10">
        <button onClick={() => navigate(-1)} className="p-2 -ml-2 text-on-surface-variant hover:text-primary transition-colors">
          <span className="material-symbols-outlined text-2xl">arrow_back</span>
        </button>
        <h1 className="text-sm font-black font-headline uppercase tracking-tight">問卷編輯器</h1>
        <button 
          onClick={() => handleOpenEdit()}
          className="w-10 h-10 bg-primary text-on-primary rounded-full flex items-center justify-center shadow-lg active:scale-95 transition-all"
        >
          <span className="material-symbols-outlined text-xl">add</span>
        </button>
      </nav>

      <motion.main 
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        className="px-5 pt-8 space-y-8 max-w-xl mx-auto"
      >
        <div className="flex flex-col gap-2">
          <h2 className="text-3xl font-black font-headline tracking-tighter">入站問卷設定</h2>
          <p className="text-sm text-on-surface-variant/70 leading-relaxed font-medium">
            自定義家長在預約前必須填寫的問題。 <br/>
            您可以直接拖拽卡片來調整問題顯示的順序。
          </p>
        </div>

        {isLoading ? (
          <div className="py-20 flex flex-col items-center justify-center gap-4 opacity-40">
            <div className="w-8 h-8 border-2 border-primary border-t-transparent rounded-full animate-spin" />
            <p className="text-xs font-bold tracking-widest">載入中...</p>
          </div>
        ) : (
          <Reorder.Group 
            axis="y" 
            values={questions} 
            onReorder={handleReorder}
            className="space-y-4"
          >
            {questions.map(q => (
              <QuestionCard key={q.questionId} q={q} />
            ))}
            {questions.length === 0 && (
              <div className="p-20 text-center border-2 border-dashed border-outline-variant/10 rounded-[40px] opacity-20 italic">
                目前還沒有設定任何問題
              </div>
            )}
          </Reorder.Group>
        )}

        {/* Edit Modal */}
        <AnimatePresence>
          {isEditing && (
            <div className="fixed inset-0 z-[100] flex items-end sm:items-center justify-center p-4">
              <motion.div 
                initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
                onClick={() => !isSaving && setIsEditing(false)}
                className="absolute inset-0 bg-black/40 backdrop-blur-md"
              />
              <motion.div 
                initial={{ y: "100%", opacity: 0 }} 
                animate={{ y: 0, opacity: 1 }} 
                exit={{ y: "100%", opacity: 0 }}
                transition={{ type: 'spring', damping: 25, stiffness: 300 }}
                className="relative w-full max-w-lg bg-surface rounded-t-[40px] sm:rounded-[40px] p-8 shadow-2xl space-y-8 overflow-y-auto max-h-[90vh]"
              >
                <div className="space-y-1">
                  <h3 className="text-3xl font-black font-headline tracking-tighter">
                    {currentQuestion ? '編輯問題' : '新增問題'}
                  </h3>
                  <p className="text-[10px] font-black text-primary uppercase tracking-[0.2em] italic">
                    Configure onboarding step
                  </p>
                </div>

                <div className="space-y-6">
                  {/* Type Selection */}
                  <div className="space-y-3">
                    <label className="text-xs font-black uppercase tracking-widest opacity-40 ml-4">問題類型</label>
                    <div className="grid grid-cols-3 gap-3">
                      {[
                        { id: 'TEXT', label: '簡答', icon: 'notes', color: 'blue' },
                        { id: 'SINGLE_CHOICE', label: '單選', icon: 'radio_button_checked', color: 'purple' },
                        { id: 'MULTIPLE_CHOICE', label: '多選', icon: 'check_box', color: 'indigo' }
                      ].map(t => (
                        <button
                          key={t.id}
                          onClick={() => setFormData({ ...formData, type: t.id })}
                          className={`py-4 rounded-2xl flex flex-col items-center gap-2 transition-all border-2 ${
                            formData.type === t.id 
                              ? 'bg-primary/5 border-primary text-primary shadow-sm' 
                              : 'bg-white border-outline-variant/10 text-on-surface-variant/60 hover:border-outline-variant/30'
                          }`}
                        >
                          <span className="material-symbols-outlined text-2xl">{t.icon}</span>
                          <span className="text-[11px] font-black">{t.label}</span>
                        </button>
                      ))}
                    </div>
                  </div>

                  {/* Question Text */}
                  <div className="space-y-2">
                    <label className="text-xs font-black uppercase tracking-widest opacity-40 ml-4">問題內容</label>
                    <textarea 
                      value={formData.questionText}
                      onChange={(e) => setFormData({ ...formData, questionText: e.target.value })}
                      placeholder="例如：您的貓咪是否有任何過敏史？"
                      className="w-full bg-surface-container-low border-2 border-transparent rounded-[32px] p-6 text-base font-bold focus:bg-white focus:border-primary/20 outline-none transition-all min-h-[120px] shadow-inner"
                    />
                  </div>

                  {/* Options (Conditional) */}
                  {(formData.type === 'SINGLE_CHOICE' || formData.type === 'MULTIPLE_CHOICE') && (
                    <div className="space-y-4 animate-in fade-in slide-in-from-bottom-4">
                      <label className="text-xs font-black uppercase tracking-widest opacity-40 ml-4 line-through hidden">Choices</label>
                      <div className="flex items-center justify-between px-4">
                        <span className="text-xs font-black uppercase tracking-widest opacity-40">選項清單</span>
                        <span className="text-[10px] font-bold opacity-30 italic">{formData.options.length} 個已設定</span>
                      </div>
                      <div className="space-y-3">
                        {formData.options.map((opt, idx) => (
                          <div key={idx} className="flex gap-2 group animate-in fade-in slide-in-from-left-4 duration-300">
                             <input 
                               value={opt}
                               onChange={(e) => handleOptionChange(idx, e.target.value)}
                               className="flex-grow bg-surface-container-low border-2 border-transparent rounded-2xl px-5 py-4 text-sm font-bold focus:bg-white focus:border-primary/10 outline-none shadow-sm"
                               placeholder={`選項 ${idx + 1}`}
                             />
                             <button onClick={() => removeOption(idx)} className="p-3 text-red-500/20 hover:text-red-500 hover:bg-red-50 rounded-xl transition-all">
                               <span className="material-symbols-outlined">delete</span>
                             </button>
                          </div>
                        ))}
                        <button 
                          onClick={addOption}
                          className="w-full py-4 rounded-2xl border-2 border-dashed border-outline-variant/20 text-xs font-black text-primary hover:bg-primary/5 hover:border-primary/40 transition-all active:scale-[0.99]"
                        >
                          + 新增選項
                        </button>
                      </div>
                    </div>
                  )}

                  {/* Settings Toggle */}
                  <div className="flex items-center justify-between p-6 bg-surface-container-low rounded-[32px] border border-outline-variant/10">
                    <div className="flex items-center gap-4">
                      <div className={`w-10 h-10 rounded-full flex items-center justify-center ${formData.required ? 'bg-primary/10 text-primary' : 'bg-on-surface-variant/5 text-on-surface-variant/40'}`}>
                        <span className="material-symbols-outlined text-xl">{formData.required ? 'notification_important' : 'question_mark'}</span>
                      </div>
                      <div>
                        <p className="text-sm font-black">必填問題</p>
                        <p className="text-[10px] opacity-40 font-bold uppercase tracking-tight">Requirement Status</p>
                      </div>
                    </div>
                    <button 
                      onClick={() => setFormData({ ...formData, required: !formData.required })}
                      className={`w-14 h-8 rounded-full transition-all relative ${formData.required ? 'bg-primary shadow-inner shadow-black/10' : 'bg-on-surface-variant/20'}`}
                    >
                      <motion.div 
                        animate={{ x: formData.required ? 28 : 4 }}
                        className="absolute top-1.5 w-5 h-5 bg-white rounded-full shadow-md"
                      />
                    </button>
                  </div>
                </div>

                <div className="flex gap-4 pt-4 pb-2">
                  <button 
                    onClick={() => setIsEditing(false)}
                    disabled={isSaving}
                    className="flex-1 py-5 bg-on-surface-variant/5 text-on-surface-variant/60 rounded-full font-black text-sm active:scale-95 transition-all"
                  >
                    取消
                  </button>
                  <button 
                    onClick={handleSave}
                    disabled={isSaving || !formData.questionText.trim()}
                    className="flex-[2] py-5 bg-primary text-on-primary rounded-full font-black text-sm shadow-xl shadow-primary/20 disabled:opacity-30 active:scale-95 transition-all flex items-center justify-center gap-2"
                  >
                    {isSaving ? (
                      <div className="w-5 h-5 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                    ) : (
                      <>
                        <span>儲存設定</span>
                        <span className="material-symbols-outlined text-sm">check</span>
                      </>
                    )}
                  </button>
                </div>
              </motion.div>
            </div>
          )}
        </AnimatePresence>
      </motion.main>
    </div>
  )
}

export default QuestionnaireEditor
