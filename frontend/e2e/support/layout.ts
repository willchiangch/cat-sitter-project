import { expect, type Locator, type Page } from '@playwright/test';

/**
 * 版面 / 可讀性斷言（Backlog 第 6 批 G5，驗收 N1~N8）。
 *
 * AppShell 是 390px 的 flex column，內容區 <main> 自己 overflow:auto：子元素一旦比 main 寬，
 * 真實手機上是整頁可以左右滑，evidence 截圖（UNCLIP_CSS 把 main 設成 overflow:visible）則是內容
 * 畫到 AppShell 外面。所以「main.scrollWidth <= main.clientWidth」就是「沒有溢出 AppShell」。
 */
export interface OverflowReport {
  mainScrollWidth: number;
  mainClientWidth: number;
  /** 左右緣超出 main 內容區（扣掉 padding）的可見元素 */
  offenders: string[];
}

export async function measureHorizontalOverflow(page: Page): Promise<OverflowReport> {
  return page.locator('main').evaluate((main) => {
    // 比的是 main 的內容區（扣掉左右 padding）：子元素畫進 padding 時 scrollWidth 不會變大，
    // 但視覺上已經貼到 / 超出殼的邊緣（驗收 N4 的卡片右緣 849 > 內容區 811）
    const rect = main.getBoundingClientRect();
    const cs = getComputedStyle(main);
    const box = {
      left: rect.left + parseFloat(cs.paddingLeft),
      right: rect.right - parseFloat(cs.paddingRight)
    };
    const offenders: string[] = [];
    for (const el of Array.from(main.querySelectorAll<HTMLElement>('*'))) {
      if (!el.checkVisibility() || getComputedStyle(el).position === 'fixed') continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0 || r.height === 0) continue;
      if (r.right > box.right + 0.5 || r.left < box.left - 0.5) {
        const id = el.getAttribute('data-testid');
        const text = (el.textContent ?? '').trim().slice(0, 20);
        offenders.push(
          `${el.tagName.toLowerCase()}${id ? `[${id}]` : ''} "${text}" ${Math.round(r.left)}~${Math.round(r.right)}`
        );
      }
    }
    return {
      mainScrollWidth: main.scrollWidth,
      mainClientWidth: main.clientWidth,
      offenders: offenders.slice(0, 10)
    };
  });
}

/** 整頁內容沒有比 AppShell 內容區寬（不會出現水平捲動、截圖不會畫到殼外） */
export async function expectNoHorizontalOverflow(page: Page) {
  const report = await measureHorizontalOverflow(page);
  expect(report.offenders, '右緣超出 AppShell <main> 的元素').toEqual([]);
  expect(report.mainScrollWidth, 'main 不應出現水平捲動').toBeLessThanOrEqual(
    report.mainClientWidth
  );
}

/** inner 的外框完整落在 outer 之內（容許 0.5px 次像素誤差） */
export async function expectInside(inner: Locator, outer: Locator, label: string) {
  const a = (await inner.boundingBox())!;
  const b = (await outer.boundingBox())!;
  expect(a, `${label}：找不到元素`).toBeTruthy();
  expect(a.x, `${label} 左緣`).toBeGreaterThanOrEqual(b.x - 0.5);
  expect(a.x + a.width, `${label} 右緣`).toBeLessThanOrEqual(b.x + b.width + 0.5);
}

export interface ContrastResult {
  color: string;
  backgroundColor: string;
  backgroundImage: string;
  ratio: number;
}

/**
 * WCAG 2.x 對比度：前景用元素自己的 color，背景往上找第一個不透明的 background-color。
 * 背景有漸層時 background-color 不代表實際底色，回傳 backgroundImage 讓呼叫方先斷言它是 none。
 */
export async function contrastOf(locator: Locator): Promise<ContrastResult> {
  return locator.evaluate((el) => {
    const parse = (c: string) => (c.match(/[\d.]+/g) ?? []).map(Number);
    const lum = ([r, g, b]: number[]) => {
      const ch = (v: number) => {
        const s = v / 255;
        return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
      };
      return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b);
    };
    const cs = getComputedStyle(el);
    let bgEl: Element | null = el;
    let bg = cs.backgroundColor;
    let backgroundImage = cs.backgroundImage;
    while (bgEl) {
      const s = getComputedStyle(bgEl);
      const rgba = parse(s.backgroundColor);
      if (s.backgroundImage !== 'none') {
        backgroundImage = s.backgroundImage;
        bg = s.backgroundColor;
        break;
      }
      if (rgba.length === 3 || (rgba.length === 4 && rgba[3] === 1)) {
        bg = s.backgroundColor;
        break;
      }
      bgEl = bgEl.parentElement;
    }
    const [hi, lo] = [lum(parse(cs.color)), lum(parse(bg))].sort((a, b) => b - a);
    return {
      color: cs.color,
      backgroundColor: bg,
      backgroundImage,
      ratio: Math.round(((hi + 0.05) / (lo + 0.05)) * 100) / 100
    };
  });
}

/**
 * 漸層底按鈕：文字色對漸層每個色標的對比度取最小值（漸層兩端都要讀得清楚）。
 * 背景沒有漸層或沒有色標時回傳 stops 為空，呼叫方應先斷言 stops 非空。
 */
export async function gradientContrastOf(
  locator: Locator
): Promise<{ color: string; stops: string[]; minRatio: number }> {
  return locator.evaluate((el) => {
    const parse = (c: string) => (c.match(/[\d.]+/g) ?? []).map(Number);
    const lum = ([r, g, b]: number[]) => {
      const ch = (v: number) => {
        const s = v / 255;
        return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
      };
      return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b);
    };
    const cs = getComputedStyle(el);
    const stops = cs.backgroundImage.match(/rgba?\([^)]+\)/g) ?? [];
    const fg = lum(parse(cs.color));
    const ratios = stops.map((stop) => {
      const [hi, lo] = [fg, lum(parse(stop))].sort((a, b) => b - a);
      return (hi + 0.05) / (lo + 0.05);
    });
    return {
      color: cs.color,
      stops,
      minRatio: ratios.length ? Math.round(Math.min(...ratios) * 100) / 100 : 0
    };
  });
}
