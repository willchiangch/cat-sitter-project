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

  const QuestionCard = ({ q }) => (
    <Reorder.Item 
      value={q} 
      id={q.questionId}
      className="bg-surface-container-low p-5 rounded-[32px] border border-outline-variant/10 shadow-sm flex items-center gap-4 active:scale-[0.98] transition-all cursor-grab"
    >
      <div className="flex-shrink-0 w-10 h-10 rounded-2xl bg-primary/5 flex items-center justify-center text-primary">
        <span className="material-symbols-outlined text-xl">{getTypeIcon(q.type)}</span>
      </div>
      
      <div className="flex-grow min-w-0 pr-2">
        <div className="flex items-center gap-2 mb-1">
          <span className="text-[10px] font-black bg-on-surface-variant/5 text-on-surface-variant px-2 py-0.5 rounded uppercase tracking-widest">
            {q.targetPetType}
          </span>
          {q.required && (
            <span className="text-[10px] font-black text-error uppercase tracking-widest leading-none">* Required</span>
          )}
        </div>
        <p className="text-sm font-bold truncate opacity-80">{q.questionText}</p>
        {q.options && q.options.length > 0 && (
          <p className="text-[10px] opacity-40 italic mt-1">{q.options.length} options</p>
        )}
      </div>

      <button 
        onClick={(e) => { e.stopPropagation(); handleOpenEdit(q); }}
        className="p-3 rounded-full hover:bg-primary/5 text-on-surface-variant transition-colors"
      >
        <span className="material-symbols-outlined text-lg">edit</span>
      </button>
    </Reorder.Item>
  )

  return (
    <div className="min-h-screen bg-surface text-on-surface pb-32">
      {/* Header */}
      <nav className="sticky top-0 z-50 bg-surface/80 backdrop-blur-md px-4 py-4 flex items-center justify-between border-b border-outline-variant/5">
        <button onClick={() => navigate(-1)} className="p-2 -ml-2 text-on-surface-variant hover:text-primary transition-colors">
          <span className="material-symbols-outlined text-2xl">arrow_back</span>
        </button>
        <h1 className="text-sm font-extrabold font-headline uppercase tracking-tighter">Questionnaire Editor</h1>
        <button 
          onClick={() => handleOpenEdit()}
          className="w-10 h-10 bg-primary text-on-primary rounded-full flex items-center justify-center shadow-lg active:scale-95 transition-all"
        >
          <span className="material-symbols-outlined text-xl">add</span>
        </button>
      </nav>

      <motion.main 
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        className="px-5 pt-8 space-y-6 max-w-xl mx-auto"
      >
        <div className="flex flex-col gap-2">
          <h2 className="text-2xl font-black font-headline tracking-tighter">Onboarding Questions</h2>
          <p className="text-xs text-on-surface-variant leading-relaxed opacity-60">
            Customize the questions cat parents answer before booking. <br/>
            Drag to reorder questions.
          </p>
        </div>

        <Reorder.Group 
          axis="y" 
          values={questions} 
          onReorder={handleReorder}
          className="space-y-3"
        >
          {questions.map(q => (
            <QuestionCard key={q.questionId} q={q} />
          ))}
          {questions.length === 0 && !isLoading && (
            <div className="p-20 text-center border-2 border-dashed border-outline-variant/10 rounded-[40px] opacity-20 italic">
              No questions defined yet.
            </div>
          )}
        </Reorder.Group>

        {/* Edit Modal */}
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
                className="relative w-full max-w-lg bg-surface rounded-[40px] p-8 shadow-2xl space-y-8 overflow-y-auto max-h-[90vh]"
              >
                <div className="space-y-1">
                  <h3 className="text-2xl font-black font-headline tracking-tighter">
                    {currentQuestion ? 'Edit Question' : 'New Question'}
                  </h3>
                  <p className="text-[10px] font-bold text-on-surface-variant/40 tracking-widest uppercase italic">
                    Configure your onboarding step
                  </p>
                </div>

                <div className="space-y-6">
                  {/* Type Selection */}
                  <div className="grid grid-cols-3 gap-2">
                    {['TEXT', 'SINGLE_CHOICE', 'MULTIPLE_CHOICE'].map(t => (
                      <button
                        key={t}
                        onClick={() => setFormData({ ...formData, type: t })}
                        className={`py-3 rounded-2xl flex flex-col items-center gap-1 transition-all border ${
                          formData.type === t ? 'bg-primary/5 border-primary text-primary' : 'bg-surface border-outline-variant/10 text-on-surface-variant'
                        }`}
                      >
                        <span className="material-symbols-outlined text-lg">{getTypeIcon(t)}</span>
                        <span className="text-[9px] font-black uppercase tracking-tight">{t.split('_')[0]}</span>
                      </button>
                    ))}
                  </div>

                  {/* Question Text */}
                  <div className="space-y-2">
                    <label className="text-[10px] font-black uppercase tracking-widest opacity-40 ml-4">Question Text</label>
                    <textarea 
                      value={formData.questionText}
                      onChange={(e) => setFormData({ ...formData, questionText: e.target.value })}
                      placeholder="e.g. Does your cat have any allergies?"
                      className="w-full bg-surface-container-low border border-outline-variant/10 rounded-3xl p-5 text-sm font-bold focus:ring-2 ring-primary/20 outline-none transition-all min-h-[100px]"
                    />
                  </div>

                  {/* Options (Conditional) */}
                  {(formData.type === 'SINGLE_CHOICE' || formData.type === 'MULTIPLE_CHOICE') && (
                    <div className="space-y-4">
                      <label className="text-[10px] font-black uppercase tracking-widest opacity-40 ml-4">Choices</label>
                      <div className="space-y-2">
                        {formData.options.map((opt, idx) => (
                          <div key={idx} className="flex gap-2 animate-in fade-in slide-in-from-left-2 duration-300">
                             <input 
                               value={opt}
                               onChange={(e) => handleOptionChange(idx, e.target.value)}
                               className="flex-grow bg-surface-container-low border border-outline-variant/5 rounded-2xl px-4 py-3 text-sm font-medium focus:border-primary outline-none"
                               placeholder={`Option ${idx + 1}`}
                             />
                             <button onClick={() => removeOption(idx)} className="text-error/40 hover:text-error transition-colors">
                               <span className="material-symbols-outlined">delete</span>
                             </button>
                          </div>
                        ))}
                        <button 
                          onClick={addOption}
                          className="w-full py-3 rounded-2xl border-2 border-dashed border-outline-variant/10 text-xs font-bold text-primary hover:bg-primary/5 transition-all"
                        >
                          + ADD OPTION
                        </button>
                      </div>
                    </div>
                  )}

                  {/* Settings Toggle */}
                  <div className="flex items-center justify-between p-4 bg-surface-container-low rounded-3xl">
                    <div className="flex items-center gap-3">
                      <span className="material-symbols-outlined text-primary">priority_high</span>
                      <span className="text-sm font-bold">Required Question</span>
                    </div>
                    <button 
                      onClick={() => setFormData({ ...formData, required: !formData.required })}
                      className={`w-12 h-6 rounded-full transition-all relative ${formData.required ? 'bg-primary' : 'bg-on-surface-variant/20'}`}
                    >
                      <motion.div 
                        animate={{ x: formData.required ? 24 : 4 }}
                        className="absolute top-1 w-4 h-4 bg-white rounded-full shadow-sm"
                      />
                    </button>
                  </div>
                </div>

                <div className="flex gap-4 pt-4">
                  <button 
                    onClick={() => setIsEditing(false)}
                    className="flex-1 py-4 bg-on-surface-variant/5 text-on-surface-variant rounded-full font-bold"
                  >
                    CANCEL
                  </button>
                  <button 
                    onClick={handleSave}
                    disabled={!formData.questionText.trim()}
                    className="flex-[2] py-4 bg-primary text-on-primary rounded-full font-bold shadow-xl shadow-primary/20 disabled:opacity-30"
                  >
                    SAVE QUESTION
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
