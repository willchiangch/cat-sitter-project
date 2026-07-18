import React, { useEffect, useState } from 'react';
import Card from '../../components/ui/Card';
import {
  getMyQuestions,
  createQuestion,
  updateQuestion,
  deleteQuestion,
  toggleQuestionActive,
  sortQuestions
} from '../../api/questionApi';
import type { SitterQuestion, QuestionAnswerType } from '../../api/questionApi';

const ANSWER_TYPE_LABELS: Record<QuestionAnswerType, string> = {
  RADIO: '單選',
  CHECKBOX: '多選',
  INPUT: '簡答',
  TEXTAREA: '詳答'
};

const emptyForm = (): SitterQuestion => ({
  questionText: '',
  answerType: 'INPUT',
  options: [],
  required: false
});

const SitterQuestionManager: React.FC = () => {
  const [questions, setQuestions] = useState<SitterQuestion[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState<SitterQuestion>(emptyForm());
  const [optionsText, setOptionsText] = useState('');
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const fetchQuestions = async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await getMyQuestions();
      setQuestions(data);
    } catch (err) {
      console.error(err);
      setError('取得問卷題目失敗');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchQuestions();
  }, []);

  const isChoiceType = form.answerType === 'RADIO' || form.answerType === 'CHECKBOX';

  const startCreate = () => {
    setEditingId('NEW');
    setForm(emptyForm());
    setOptionsText('');
    setFormError(null);
  };

  const startEdit = (q: SitterQuestion) => {
    // 修改已存在題目的回答類型可能影響舊答案顯示 (PRD-004 例外處理表)
    setEditingId(q.id!);
    setForm(q);
    setOptionsText(q.options.join('\n'));
    setFormError(null);
  };

  const cancelEdit = () => {
    setEditingId(null);
    setForm(emptyForm());
    setOptionsText('');
    setFormError(null);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError(null);

    if (!form.questionText.trim()) {
      setFormError('請輸入題目內容');
      return;
    }
    if (form.questionText.length > 200) {
      setFormError('題目內容上限 200 字');
      return;
    }

    const options = isChoiceType
      ? optionsText.split('\n').map((s) => s.trim()).filter(Boolean)
      : [];

    if (isChoiceType && options.length === 0) {
      setFormError('單選或多選題必須至少設定一個選項');
      return;
    }
    if (options.some((o) => o.length > 50)) {
      setFormError('選項內容上限 50 字');
      return;
    }

    const isEditingExisting = editingId && editingId !== 'NEW';
    if (isEditingExisting) {
      const original = questions.find((q) => q.id === editingId);
      if (original && original.answerType !== form.answerType) {
        const confirmed = window.confirm(
          '修改問題類型可能影響已填寫的舊答案顯示，建議停用舊題並新增新題。是否仍要繼續？'
        );
        if (!confirmed) return;
      }
    }

    setSaving(true);
    try {
      const payload: SitterQuestion = { ...form, options };
      if (isEditingExisting) {
        await updateQuestion(editingId!, payload);
      } else {
        await createQuestion(payload);
      }
      cancelEdit();
      await fetchQuestions();
    } catch (err: any) {
      console.error(err);
      setFormError(err.response?.data?.message || '儲存失敗，請稍後再試');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (questionId: string) => {
    if (!window.confirm('確定要刪除此題目嗎？（歷史訂單的回覆紀錄仍會保留）')) return;
    try {
      await deleteQuestion(questionId);
      await fetchQuestions();
    } catch (err) {
      console.error(err);
      alert('刪除失敗，請稍後再試');
    }
  };

  const handleToggleActive = async (q: SitterQuestion) => {
    try {
      await toggleQuestionActive(q.id!, !q.isActive);
      await fetchQuestions();
    } catch (err) {
      console.error(err);
      alert('更新狀態失敗，請稍後再試');
    }
  };

  const handleMove = async (index: number, direction: -1 | 1) => {
    const targetIndex = index + direction;
    if (targetIndex < 0 || targetIndex >= questions.length) return;

    const reordered = [...questions];
    [reordered[index], reordered[targetIndex]] = [reordered[targetIndex], reordered[index]];
    setQuestions(reordered);

    try {
      await sortQuestions({ questionIds: reordered.map((q) => q.id!) });
    } catch (err) {
      console.error(err);
      alert('排序更新失敗，請重新整理後再試');
      await fetchQuestions();
    }
  };

  return (
    <div
      style={{
        padding: '2rem 1.5rem',
        fontFamily: 'var(--font-sans)',
        maxWidth: '720px',
        margin: '0 auto'
      }}
    >
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'baseline',
          marginBottom: '1.5rem'
        }}
      >
        <h2
          style={{
            fontSize: '1.75rem',
            fontWeight: '700',
            color: 'var(--color-on-surface)',
            fontFamily: 'var(--font-display)',
            margin: 0
          }}
        >
          事前問卷設定
        </h2>
        <button
          className="btn-primary"
          style={{ padding: '0.5rem 1rem', fontSize: '0.875rem' }}
          onClick={startCreate}
          data-testid="question-add-btn"
        >
          + 新增問題
        </button>
      </div>

      {editingId && (
        <Card style={{ marginBottom: '1.5rem', padding: '1.5rem' }}>
          <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.4rem' }}>
              <label style={{ fontSize: '0.8rem', fontWeight: '600' }}>題目內容</label>
              <input
                type="text"
                value={form.questionText}
                onChange={(e) => setForm({ ...form, questionText: e.target.value })}
                maxLength={200}
                style={{ padding: '0.6rem', borderRadius: '8px', border: '1px solid var(--color-outline-variant)' }}
                data-testid="question-text-input"
              />
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.4rem' }}>
              <label style={{ fontSize: '0.8rem', fontWeight: '600' }}>回答類型</label>
              <select
                value={form.answerType}
                onChange={(e) => setForm({ ...form, answerType: e.target.value as QuestionAnswerType })}
                style={{ padding: '0.6rem', borderRadius: '8px', border: '1px solid var(--color-outline-variant)' }}
                data-testid="question-answer-type-select"
              >
                {Object.entries(ANSWER_TYPE_LABELS).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            </div>

            {isChoiceType && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.4rem' }}>
                <label style={{ fontSize: '0.8rem', fontWeight: '600' }}>選項內容（每行一個）</label>
                <textarea
                  value={optionsText}
                  onChange={(e) => setOptionsText(e.target.value)}
                  rows={4}
                  style={{
                    padding: '0.6rem',
                    borderRadius: '8px',
                    border: '1px solid var(--color-outline-variant)',
                    resize: 'none'
                  }}
                  data-testid="question-options-textarea"
                />
              </div>
            )}

            <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <input
                type="checkbox"
                id="question-required"
                checked={form.required}
                onChange={(e) => setForm({ ...form, required: e.target.checked })}
                data-testid="question-required-checkbox"
              />
              <label htmlFor="question-required" style={{ fontSize: '0.85rem' }}>
                設為必填
              </label>
            </div>

            {formError && (
              <div style={{ color: '#dc2626', fontSize: '0.85rem' }} data-testid="question-form-error">
                {formError}
              </div>
            )}

            <div style={{ display: 'flex', gap: '0.5rem' }}>
              <button
                type="submit"
                className="btn-primary"
                disabled={saving}
                style={{ padding: '0.6rem 1rem' }}
                data-testid="question-save-btn"
              >
                {saving ? '儲存中...' : '儲存'}
              </button>
              <button
                type="button"
                onClick={cancelEdit}
                style={{
                  padding: '0.6rem 1rem',
                  backgroundColor: 'var(--color-surface-low)',
                  border: 'none',
                  borderRadius: '8px',
                  cursor: 'pointer'
                }}
              >
                取消
              </button>
            </div>
          </form>
        </Card>
      )}

      {loading ? (
        <div style={{ textAlign: 'center', padding: '40px 0', color: '#9ca3af' }}>載入中...</div>
      ) : error ? (
        <div style={{ textAlign: 'center', padding: '40px 0', color: '#dc2626' }}>{error}</div>
      ) : questions.length === 0 ? (
        <div style={{ textAlign: 'center', padding: '40px 0', color: '#9ca3af' }}>
          尚未設定任何問卷題目
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }} data-testid="question-list">
          {questions.map((q, index) => (
            <Card key={q.id} data-testid={`question-row-${q.id}`}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '1rem' }}>
                <div style={{ flex: 1 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.4rem' }}>
                    <span
                      style={{
                        fontSize: '0.7rem',
                        fontWeight: '700',
                        color: 'var(--color-primary)',
                        backgroundColor: 'var(--color-surface-low)',
                        padding: '2px 8px',
                        borderRadius: '9999px'
                      }}
                    >
                      {ANSWER_TYPE_LABELS[q.answerType]}
                    </span>
                    {q.required && (
                      <span style={{ fontSize: '0.7rem', fontWeight: '700', color: '#dc2626' }}>必填</span>
                    )}
                    {!q.isActive && (
                      <span style={{ fontSize: '0.7rem', fontWeight: '700', color: 'var(--color-on-surface-variant)' }}>
                        已停用
                      </span>
                    )}
                  </div>
                  <div style={{ fontWeight: '600' }}>{q.questionText}</div>
                  {q.options.length > 0 && (
                    <div style={{ fontSize: '0.8rem', color: 'var(--color-on-surface-variant)', marginTop: '0.3rem' }}>
                      選項：{q.options.join('、')}
                    </div>
                  )}
                </div>

                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.3rem', alignItems: 'flex-end' }}>
                  <div style={{ display: 'flex', gap: '0.3rem' }}>
                    <button
                      onClick={() => handleMove(index, -1)}
                      disabled={index === 0}
                      data-testid={`question-move-up-${q.id}`}
                      style={{ padding: '0.3rem 0.5rem', cursor: 'pointer' }}
                    >
                      ↑
                    </button>
                    <button
                      onClick={() => handleMove(index, 1)}
                      disabled={index === questions.length - 1}
                      data-testid={`question-move-down-${q.id}`}
                      style={{ padding: '0.3rem 0.5rem', cursor: 'pointer' }}
                    >
                      ↓
                    </button>
                  </div>
                  <div style={{ display: 'flex', gap: '0.3rem' }}>
                    <button
                      onClick={() => handleToggleActive(q)}
                      data-testid={`question-toggle-active-${q.id}`}
                      style={{ padding: '0.3rem 0.6rem', fontSize: '0.75rem', cursor: 'pointer' }}
                    >
                      {q.isActive ? '停用' : '啟用'}
                    </button>
                    <button
                      onClick={() => startEdit(q)}
                      data-testid={`question-edit-${q.id}`}
                      style={{ padding: '0.3rem 0.6rem', fontSize: '0.75rem', cursor: 'pointer' }}
                    >
                      編輯
                    </button>
                    <button
                      onClick={() => handleDelete(q.id!)}
                      data-testid={`question-delete-${q.id}`}
                      style={{ padding: '0.3rem 0.6rem', fontSize: '0.75rem', cursor: 'pointer', color: '#dc2626' }}
                    >
                      刪除
                    </button>
                  </div>
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
};

export default SitterQuestionManager;
