import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import Button from '../../components/ui/Button';
import DateMultiPicker from '../../components/orders/DateMultiPicker';
import BookingSitterCard from '../../components/orders/BookingSitterCard';
import {
  ErrorText,
  LoadingBlock,
  PageContainer,
  QueryErrorBlock,
  SectionCard
} from '../../components/orders/common';
import { INFO_BANNER_STYLE } from '../../components/ui/statusTone';
import { useBookingContextQuery } from '../../hooks/useOrders';
import { usePetsQuery } from '../../hooks/usePets';
import { useIdempotentAction } from '../../hooks/useIdempotentAction';
import { useRole } from '../../contexts/useRole';
import { useHasSession } from '../../hooks/useHasSession';
import { createOrder } from '../../api/orderApi';
import type {
  BookingContext,
  BookingPlan,
  BookingQuestion,
  CreateOrderAnswer
} from '../../api/orderApi';
import { getApiError } from '../../api/apiError';
import { formatMoney } from '../../utils/money';
import { addDays, formatDateTime, toTaipeiDate } from '../../utils/datetime';
import { codePointLength, stripText, truncateCodePoints } from '../../utils/text';
import { getSafeRedirectTarget } from '../../utils/safeRedirect';
import { clearBookingDraft, loadBookingDraft, saveBookingDraft } from '../../utils/bookingDraft';

const URGENT_WINDOW_MS = 2 * 60 * 60 * 1000;
const AMOUNT_MAX = 999_999;
const MAX_GROUPS = 10;
const MAX_TOTAL_DATES = 60;

interface GroupDraft {
  planId: string;
  dates: string[];
  dailyCount: number;
  /** null = 尚未手動調整，採預設「全部毛孩」（BR-17）；登入後還原的草稿與新增群組都從 null 開始 */
  petIds: string[] | null;
}

/** E31 不可預約時的提示文字（PRD-005 §4.1、BR-23） */
const bookabilityMessage = (b: BookingContext['bookability']): string | null => {
  switch (b) {
    case 'SELF':
      return '這是您自己的預約頁，無法預約';
    case 'NOT_VERIFIED':
      return '此保母尚未完成實名認證，暫不開放預約';
    case 'UNAVAILABLE':
      return '保母目前不開放預約';
    case 'PAUSED':
      return '保母目前暫停接受新預約';
    default:
      return null;
  }
};

const answerLimit = (q: BookingQuestion) => (q.type === 'TEXTAREA' ? 1000 : 200);

/**
 * 公開預約頁（SD-ORDER §9.1 `/booking/:sitterId`，E31 + E1；PRD-005）。
 * 「今天」、日期範圍與評估期限試算一律以 E31 `serverTime` 為準；送出帶 Idempotency-Key，同一次送出的重試沿用同一把。
 * 匿名 / 非飼主角色可瀏覽方案、選日期與試算；毛孩與問卷只對登入飼主顯示。匿名按送出 → 暫存已選內容
 * （utils/bookingDraft）並導向登入，登入後回本頁還原（PRD-005 §4.2-3、EX-1、AC-1.1）。
 */
const PublicBookingPage: React.FC<{ sitterId?: string }> = ({ sitterId }) => {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { currentRole } = useRole();
  const hasSession = useHasSession();
  const isOwner = hasSession && currentRole === 'client';
  const ctxQuery = useBookingContextQuery(sitterId);
  const { data: pets = [] } = usePetsQuery(isOwner);
  const [restoredDraft] = useState(() => (sitterId ? loadBookingDraft(sitterId) : null));
  const [groups, setGroups] = useState<GroupDraft[]>(() =>
    (restoredDraft ?? []).map((g) => ({ ...g, petIds: null }))
  );
  const [answers, setAnswers] = useState<Record<string, string | string[]>>({});
  const [questionsOverride, setQuestionsOverride] = useState<BookingQuestion[] | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const action = useIdempotentAction();

  // 草稿只還原一次：讀進 state 後即清除，之後重開頁面不再帶入舊選擇
  useEffect(() => {
    if (sitterId && restoredDraft) clearBookingDraft(sitterId);
  }, [sitterId, restoredDraft]);

  const ctx = ctxQuery.data;
  const plans = useMemo(() => ctx?.plans ?? [], [ctx]);
  const planById = useMemo(() => new Map(plans.map((p) => [p.planId, p])), [plans]);
  const ownerPets = (isOwner ? pets : []).filter((p) => !!p.id) as ((typeof pets)[number] & {
    id: string;
  })[];
  const petIdsOf = (g: GroupDraft): string[] => g.petIds ?? ownerPets.map((p) => p.id);
  const questions = (questionsOverride ?? ctx?.questionnaire?.questions ?? [])
    .slice()
    .sort((a, b) => a.sortOrder - b.sortOrder);
  // 問卷只在登入飼主時顯示（PRD-005 §3：匿名「登入後才顯示」）
  const questionnaireRequired = isOwner && !!ctx?.questionnaire?.required;

  if (!sitterId)
    return (
      <PageContainer>
        <p className="muted">找不到此保母</p>
      </PageContainer>
    );
  if (ctxQuery.isLoading)
    return (
      <PageContainer>
        <LoadingBlock />
      </PageContainer>
    );
  if (ctxQuery.error || !ctx)
    return (
      <PageContainer>
        <QueryErrorBlock
          error={ctxQuery.error}
          context="booking"
          onRetry={() => ctxQuery.refetch()}
        />
      </PageContainer>
    );

  // 以後端 serverTime 為準（SD-ORDER §9.2），不讀瀏覽器時間
  const serverNow = new Date(ctx.serverTime).getTime();
  const today = toTaipeiDate(ctx.serverTime);
  const maxDate = addDays(today, 365);
  const unavailable = bookabilityMessage(ctx.bookability);
  const showPlans = ctx.bookability !== 'NOT_VERIFIED' && ctx.bookability !== 'UNAVAILABLE';

  const planRange = (plan?: BookingPlan) => ({
    min: plan?.bookableFrom && plan.bookableFrom > today ? plan.bookableFrom : today,
    max: plan?.bookableTo && plan.bookableTo < maxDate ? plan.bookableTo : maxDate
  });

  const addGroup = (planId: string) => {
    if (groups.length >= MAX_GROUPS) return;
    setGroups([...groups, { planId, dates: [], dailyCount: 1, petIds: null }]);
  };
  const updateGroup = (idx: number, patch: Partial<GroupDraft>) =>
    setGroups(groups.map((g, i) => (i === idx ? { ...g, ...patch } : g)));

  // 試算與驗證（BR-3、BR-18）
  const estimate = groups.reduce(
    (sum, g) => sum + g.dates.length * g.dailyCount * (planById.get(g.planId)?.unitPrice ?? 0),
    0
  );
  const totalDates = groups.reduce((n, g) => n + g.dates.length, 0);
  const dupKeys = new Set<string>();
  let duplicate = false;
  for (const g of groups)
    for (const d of g.dates) {
      const k = `${g.planId}|${d}`;
      if (dupKeys.has(k)) duplicate = true;
      dupKeys.add(k);
    }
  const outOfRange = groups.some((g) => {
    const r = planRange(planById.get(g.planId));
    return g.dates.some((d) => d < r.min || d > r.max);
  });
  const missingRequired = questionnaireRequired
    ? questions.some((q) => {
        if (!q.required) return false;
        const a = answers[q.questionId];
        return Array.isArray(a) ? a.length === 0 : !a || codePointLength(stripText(a)) === 0;
      })
    : false;
  const firstDate = groups.flatMap((g) => g.dates).sort()[0];
  const firstDayMs = firstDate ? Date.parse(`${firstDate}T00:00:00+08:00`) : null;
  const reviewExpiresMs =
    firstDayMs !== null ? Math.max(firstDayMs, serverNow + URGENT_WINDOW_MS) : null;
  const urgent = firstDayMs !== null && firstDayMs < serverNow + URGENT_WINDOW_MS;

  // 方案 / 日期 / 金額的共同條件；匿名只檢查這些（按下後導向登入），登入飼主另需毛孩與問卷
  const selectionValid =
    ctx.bookability === 'BOOKABLE' &&
    groups.length > 0 &&
    groups.every((g) => g.dates.length > 0 && planById.has(g.planId)) &&
    totalDates <= MAX_TOTAL_DATES &&
    !duplicate &&
    !outOfRange &&
    estimate <= AMOUNT_MAX &&
    !action.pending;
  const canSubmit = !hasSession
    ? selectionValid
    : isOwner &&
      selectionValid &&
      ownerPets.length > 0 &&
      groups.every((g) => petIdsOf(g).length > 0) &&
      !missingRequired;

  /** 匿名送出（EX-1）：不呼叫 E1，暫存已選內容後導向登入，登入後回本頁（redirect 經 safeRedirect） */
  const goLogin = () => {
    saveBookingDraft(sitterId, groups);
    const back = getSafeRedirectTarget(`/booking/${encodeURIComponent(sitterId)}`, '/demo');
    navigate(`/login?redirect=${encodeURIComponent(back)}`);
  };

  const submit = async () => {
    if (!hasSession) {
      goLogin();
      return;
    }
    setNotice(null);
    const answerList: CreateOrderAnswer[] | undefined = questionnaireRequired
      ? questions.flatMap((q): CreateOrderAnswer[] => {
          const a = answers[q.questionId];
          if (Array.isArray(a)) return a.length ? [{ questionId: q.questionId, value: a }] : [];
          const v = a ? stripText(a) : '';
          return v ? [{ questionId: q.questionId, value: v }] : [];
        })
      : undefined;
    const res = await action.run(async (key) => {
      try {
        return await createOrder(
          {
            sitterId,
            groups: groups.map((g) => ({
              planId: g.planId,
              dates: [...g.dates].sort(),
              dailyCount: g.dailyCount,
              petIds: petIdsOf(g)
            })),
            ...(answerList ? { answers: answerList } : {}),
            expectedAmount: estimate
          },
          key
        );
      } catch (e) {
        const err = getApiError(e);
        if (err.code === 'QUESTIONNAIRE_CHANGED' && Array.isArray(err.payload.questions)) {
          // BR-24：重新載入問卷並保留仍有效的答案
          const next = err.payload.questions as BookingQuestion[];
          setQuestionsOverride(next);
          setAnswers((prev) => {
            const kept: Record<string, string | string[]> = {};
            for (const q of next) {
              const a = prev[q.questionId];
              if (a === undefined) continue;
              if (q.type === 'RADIO') {
                if (typeof a === 'string' && (q.options ?? []).includes(a)) kept[q.questionId] = a;
              } else if (q.type === 'CHECKBOX') {
                if (Array.isArray(a))
                  kept[q.questionId] = a.filter((x) => (q.options ?? []).includes(x));
              } else if (typeof a === 'string') kept[q.questionId] = a;
            }
            return kept;
          });
        }
        if (err.code === 'PRICE_CHANGED') {
          qc.invalidateQueries({ queryKey: ['booking-context', sitterId] });
        }
        throw e;
      }
    });
    if (res) navigate(`/owner/orders/${res.orderId}`);
  };

  return (
    <PageContainer testId="public-booking-page">
      <BookingSitterCard sitterId={sitterId} canFavorite={isOwner} />

      {unavailable && (
        <div
          role="status"
          style={INFO_BANNER_STYLE}
          data-testid="public-booking-unavailable-message"
        >
          {unavailable}
        </div>
      )}

      {showPlans && (
        <SectionCard title="服務方案">
          {plans.length === 0 ? (
            <p className="muted" style={{ margin: 0 }}>
              目前沒有可預約的方案
            </p>
          ) : (
            plans.map((p) => (
              <div
                key={p.planId}
                className="surface-block stack"
                style={{ gap: '0.375rem' }}
                data-testid={`public-booking-plan-card-${p.planId}`}
              >
                <div className="kv-row">
                  <span style={{ color: 'var(--color-on-surface)', fontWeight: 600 }}>
                    {p.title}
                  </span>
                  <span>{formatMoney(p.unitPrice)} / 次</span>
                </div>
                {(p.bookableFrom || p.bookableTo) && (
                  <span className="muted">
                    可預約 {p.bookableFrom ?? today} ~ {p.bookableTo ?? maxDate}
                  </span>
                )}
                <div className="btn-row">
                  <Button
                    variant="secondary"
                    style={{ padding: '0.375rem 0.875rem' }}
                    disabled={groups.length >= MAX_GROUPS}
                    onClick={() => addGroup(p.planId)}
                    data-testid={`public-booking-plan-card-${p.planId}-btn-add`}
                  >
                    加入日期群組
                  </Button>
                </div>
              </div>
            ))
          )}
        </SectionCard>
      )}

      {showPlans &&
        groups.map((g, idx) => {
          const plan = planById.get(g.planId);
          const range = planRange(plan);
          const types = plan?.petTypes ?? [];
          const groupPetIds = petIdsOf(g);
          const selectedPets = ownerPets.filter((p) => groupPetIds.includes(p.id));
          const typeMismatch =
            types.length > 0 &&
            selectedPets.length > 0 &&
            !selectedPets.some((p) => types.includes(p.species));
          return (
            <SectionCard
              key={idx}
              title={`日期群組 ${idx + 1}`}
              testId={`public-booking-group-${idx}`}
            >
              <div>
                <label className="field-label" htmlFor={`group-${idx}-plan`}>
                  方案
                </label>
                <select
                  id={`group-${idx}-plan`}
                  className="form-input"
                  value={g.planId}
                  onChange={(e) => updateGroup(idx, { planId: e.target.value })}
                  data-testid={`public-booking-group-${idx}-plan`}
                >
                  {plans.map((p) => {
                    const r = planRange(p);
                    const fits = g.dates.every((d) => d >= r.min && d <= r.max);
                    return (
                      <option
                        key={p.planId}
                        value={p.planId}
                        disabled={!fits && p.planId !== g.planId}
                      >
                        {p.title}
                        {!fits ? '（所選日期不在可預約區間）' : ''}
                      </option>
                    );
                  })}
                </select>
              </div>
              {typeMismatch && (
                <p
                  role="status"
                  className="field-error"
                  data-testid="public-booking-pet-type-warning"
                >
                  您沒有適合此方案的寵物，請先至個人檔案新增或選擇其他方案
                </p>
              )}
              <DateMultiPicker
                selected={g.dates}
                min={range.min}
                max={range.max}
                testIdPrefix={`public-booking-group-${idx}-date-`}
                onToggle={(d) =>
                  updateGroup(idx, {
                    dates: g.dates.includes(d)
                      ? g.dates.filter((x) => x !== d)
                      : [...g.dates, d].sort()
                  })
                }
              />
              <span className="muted" style={{ overflowWrap: 'anywhere' }}>
                已選 {g.dates.length} 天{g.dates.length ? `：${g.dates.join('、')}` : ''}
              </span>
              <div>
                <label className="field-label" htmlFor={`group-${idx}-count`}>
                  每日服務次數
                </label>
                <select
                  id={`group-${idx}-count`}
                  className="form-input"
                  value={g.dailyCount}
                  onChange={(e) => updateGroup(idx, { dailyCount: Number(e.target.value) })}
                  data-testid={`public-booking-group-${idx}-daily-count`}
                >
                  {[1, 2, 3].map((n) => (
                    <option key={n} value={n}>
                      {n}
                    </option>
                  ))}
                </select>
              </div>
              {isOwner && (
                <div className="stack" style={{ gap: '0.375rem' }}>
                  <span className="field-label" style={{ margin: 0 }}>
                    照顧的毛孩
                  </span>
                  {ownerPets.length === 0 ? (
                    <p className="field-error" data-testid="public-booking-no-pet-message">
                      您尚未新增毛孩，請先至個人檔案新增
                    </p>
                  ) : (
                    <div className="btn-row">
                      {ownerPets.map((pet) => (
                        <label
                          key={pet.id}
                          style={{ display: 'flex', gap: '0.25rem', alignItems: 'center' }}
                        >
                          <input
                            type="checkbox"
                            checked={groupPetIds.includes(pet.id)}
                            onChange={() =>
                              updateGroup(idx, {
                                petIds: groupPetIds.includes(pet.id)
                                  ? groupPetIds.filter((x) => x !== pet.id)
                                  : [...groupPetIds, pet.id]
                              })
                            }
                            data-testid={`public-booking-group-${idx}-pet-checkbox-${pet.id}`}
                          />
                          {pet.name}
                        </label>
                      ))}
                    </div>
                  )}
                  {ownerPets.length > 0 && groupPetIds.length === 0 && (
                    <p className="field-error">每個日期群組至少選擇 1 隻毛孩</p>
                  )}
                </div>
              )}
              <div className="btn-row">
                <Button
                  variant="secondary"
                  style={{ padding: '0.375rem 0.875rem' }}
                  onClick={() => setGroups(groups.filter((_, i) => i !== idx))}
                  data-testid={`public-booking-group-${idx}-btn-remove`}
                >
                  刪除此群組
                </Button>
              </div>
            </SectionCard>
          );
        })}

      {showPlans && plans.length > 0 && (
        <div className="btn-row">
          <Button
            variant="secondary"
            disabled={groups.length >= MAX_GROUPS}
            onClick={() => addGroup(plans[0].planId)}
            data-testid="public-booking-btn-add-group"
          >
            新增日期群組
          </Button>
        </div>
      )}

      {questionnaireRequired && questions.length > 0 && (
        <SectionCard title="事前問卷">
          {questions.map((q) => {
            const a = answers[q.questionId];
            const id = `q-${q.questionId}`;
            return (
              <div
                key={q.questionId}
                className="stack"
                style={{ gap: '0.375rem' }}
                data-testid={`public-booking-question-${q.questionId}`}
              >
                <span className="field-label" style={{ margin: 0 }}>
                  {q.text}
                  {q.required && <span style={{ color: 'var(--color-error)' }}>（必填）</span>}
                </span>
                {q.type === 'RADIO' &&
                  (q.options ?? []).map((o) => (
                    <label
                      key={o}
                      style={{ display: 'flex', gap: '0.375rem', alignItems: 'center' }}
                    >
                      <input
                        type="radio"
                        name={id}
                        checked={a === o}
                        onChange={() => setAnswers({ ...answers, [q.questionId]: o })}
                      />
                      <span style={{ minWidth: 0, overflowWrap: 'anywhere' }}>{o}</span>
                    </label>
                  ))}
                {q.type === 'CHECKBOX' &&
                  (q.options ?? []).map((o) => {
                    const list = Array.isArray(a) ? a : [];
                    return (
                      <label
                        key={o}
                        style={{ display: 'flex', gap: '0.375rem', alignItems: 'center' }}
                      >
                        <input
                          type="checkbox"
                          checked={list.includes(o)}
                          onChange={() =>
                            setAnswers({
                              ...answers,
                              [q.questionId]: list.includes(o)
                                ? list.filter((x) => x !== o)
                                : [...list, o]
                            })
                          }
                        />
                        <span style={{ minWidth: 0, overflowWrap: 'anywhere' }}>{o}</span>
                      </label>
                    );
                  })}
                {(q.type === 'INPUT' || q.type === 'TEXTAREA') && (
                  <>
                    {q.type === 'INPUT' ? (
                      <input
                        id={id}
                        aria-label={q.text}
                        className="form-input"
                        value={typeof a === 'string' ? a : ''}
                        onChange={(e) =>
                          setAnswers({
                            ...answers,
                            [q.questionId]: truncateCodePoints(e.target.value, answerLimit(q))
                          })
                        }
                      />
                    ) : (
                      <textarea
                        id={id}
                        aria-label={q.text}
                        className="form-input"
                        rows={4}
                        value={typeof a === 'string' ? a : ''}
                        onChange={(e) =>
                          setAnswers({
                            ...answers,
                            [q.questionId]: truncateCodePoints(e.target.value, answerLimit(q))
                          })
                        }
                      />
                    )}
                    <span className="muted" style={{ textAlign: 'right', fontSize: '0.75rem' }}>
                      {codePointLength(typeof a === 'string' ? a : '')} / {answerLimit(q)}
                    </span>
                  </>
                )}
              </div>
            );
          })}
        </SectionCard>
      )}

      {showPlans && (
        <SectionCard title="確認送出">
          <div className="kv-row" style={{ fontSize: '1rem' }}>
            <span>試算金額</span>
            <span data-testid="public-booking-estimate-amount">{formatMoney(estimate)}</span>
          </div>
          <p className="muted" style={{ margin: 0 }}>
            最終總額由保母評估後確認
          </p>
          {estimate > AMOUNT_MAX && (
            <p className="field-error" data-testid="public-booking-amount-limit-message">
              單筆預約金額不可超過 $ 999,999，請拆成多筆預約
            </p>
          )}
          {totalDates > MAX_TOTAL_DATES && (
            <p className="field-error">單筆預約日期總數不可超過 60 天</p>
          )}
          {duplicate && <p className="field-error">同一方案的同一天只能出現在一個日期群組</p>}
          {reviewExpiresMs !== null && (
            <p style={{ margin: 0 }} data-testid="public-booking-review-expires-at">
              保母需於 {formatDateTime(new Date(reviewExpiresMs).toISOString()).replace(/-/g, '/')}{' '}
              前回覆，逾時將自動取消
            </p>
          )}
          {urgent && (
            <p className="field-error" data-testid="public-booking-urgent-hint">
              距服務日很近，保母可能來不及回覆
            </p>
          )}
          {!hasSession && (
            <p className="muted" style={{ margin: 0 }} data-testid="public-booking-login-hint">
              送出前需先登入，登入後會回到此頁並保留已選內容
            </p>
          )}
          {hasSession && currentRole !== 'client' && (
            <p className="field-error">請切換為飼主身分後再預約</p>
          )}
          {notice && <p className="field-error">{notice}</p>}
          <ErrorText error={action.error} context="booking" />
          <div className="btn-row">
            <Button
              className="btn-primary"
              fullWidth
              disabled={!canSubmit}
              onClick={submit}
              data-testid="public-booking-btn-submit"
            >
              {action.pending ? '送出中…' : '送出預約'}
            </Button>
          </div>
        </SectionCard>
      )}
    </PageContainer>
  );
};

export default PublicBookingPage;
