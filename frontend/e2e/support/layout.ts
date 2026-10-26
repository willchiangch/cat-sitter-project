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

/** 兩個元素的外框不相交（容許 0.5px 次像素誤差）：例如卡片角落的「移除」不可壓到輸入框 */
export async function expectNoOverlap(a: Locator, b: Locator, label: string) {
  const ra = (await a.boundingBox())!;
  const rb = (await b.boundingBox())!;
  expect(ra, `${label}：找不到元素 A`).toBeTruthy();
  expect(rb, `${label}：找不到元素 B`).toBeTruthy();
  const overlapX = Math.min(ra.x + ra.width, rb.x + rb.width) - Math.max(ra.x, rb.x);
  const overlapY = Math.min(ra.y + ra.height, rb.y + rb.height) - Math.max(ra.y, rb.y);
  expect(
    overlapX > 0.5 && overlapY > 0.5,
    `${label}：A=${JSON.stringify(ra)} B=${JSON.stringify(rb)} 重疊 ${overlapX.toFixed(1)}x${overlapY.toFixed(1)}`
  ).toBe(false);
}

/**
 * 同 {@link gradientContrastOf}（沒有漸層時改用實色底），但把元素（含祖先）的 opacity 算進去：半透明按鈕實際看到的是
 * 「文字色 / 漸層色標」與後方底色混合後的顏色（驗收 N7 附帶觀察：未選中的切換鈕 opacity 0.6，
 * 白字壓在變淡的灰底上）。後方底色取祖先第一個不透明的 background-color，找不到時用白色。
 */
export async function effectiveGradientContrastOf(
  locator: Locator
): Promise<{ opacity: number; backdrop: string; stops: string[]; minRatio: number }> {
  return locator.evaluate((el) => {
    const parse = (c: string) => (c.match(/[\d.]+/g) ?? []).map(Number);
    const lum = ([r, g, b]: number[]) => {
      const ch = (v: number) => {
        const s = v / 255;
        return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
      };
      return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b);
    };
    let opacity = 1;
    for (let n: Element | null = el; n; n = n.parentElement) {
      opacity *= parseFloat(getComputedStyle(n).opacity);
    }
    let backdrop = [255, 255, 255];
    let backdropText = 'rgb(255, 255, 255)';
    for (let n = el.parentElement; n; n = n.parentElement) {
      const c = parse(getComputedStyle(n).backgroundColor);
      if (c.length === 3 || (c.length === 4 && c[3] === 1)) {
        backdrop = c.slice(0, 3);
        backdropText = getComputedStyle(n).backgroundColor;
        break;
      }
    }
    const blend = (c: number[]) =>
      c.slice(0, 3).map((v, i) => v * opacity + backdrop[i] * (1 - opacity));
    const cs = getComputedStyle(el);
    // 沒有漸層（例如未選中改成白底細框）時，以實色底當唯一色標；底色透明則就是後方底色
    const bg = parse(cs.backgroundColor);
    const solid =
      bg.length === 3 || (bg.length === 4 && bg[3] === 1) ? cs.backgroundColor : backdropText;
    const gradientStops = cs.backgroundImage.match(/rgba?\([^)]+\)/g) ?? [];
    const stops = gradientStops.length ? gradientStops : [solid];
    const fg = lum(blend(parse(cs.color)));
    const ratios = stops.map((stop) => {
      const [hi, lo] = [fg, lum(blend(parse(stop)))].sort((a, b) => b - a);
      return (hi + 0.05) / (lo + 0.05);
    });
    return {
      opacity: Math.round(opacity * 100) / 100,
      backdrop: backdropText,
      stops,
      minRatio: ratios.length ? Math.round(Math.min(...ratios) * 100) / 100 : 0
    };
  });
}

/**
 * 頁面最外層容器（App 的 Outlet 包裝裡第一個元素）內容區左右各內縮多少（相對 <main> 外框）。
 * 標準是 <main> 1.5rem + 頁面容器 PAGE_PADDING 1.5rem = 48px（驗收 N17：KYC 詳情、記事本管理頁過去只有 24px）
 */
export async function pageContentInset(page: Page): Promise<{ left: number; right: number }> {
  const container = page.getByTestId('app-page-outlet').locator(':scope > *').first();
  await expect(container).toBeVisible();
  return container.evaluate((el) => {
    const main = el.closest('main')!.getBoundingClientRect();
    const box = el.getBoundingClientRect();
    const cs = getComputedStyle(el);
    return {
      left: Math.round(box.left + parseFloat(cs.paddingLeft) - main.left),
      right: Math.round(main.right - (box.right - parseFloat(cs.paddingRight)))
    };
  });
}

export const STANDARD_PAGE_INSET = 48;

export async function expectStandardPageInset(page: Page, label: string) {
  expect(await pageContentInset(page), `${label} 內容左右內縮`).toEqual({
    left: STANDARD_PAGE_INSET,
    right: STANDARD_PAGE_INSET
  });
}

/**
 * 元素文字實際排成幾行（以文字 Range 的行框 top 去重計算，fix-batch13 K7 同一手法）。
 * 「不換行」斷言用這個，不用高度猜：padding / line-height 不同的按鈕高度門檻不一樣。
 */
export async function textLineCount(locator: Locator): Promise<number> {
  return locator.evaluate((el) => {
    // 只量文字節點：按鈕裡的 SVG 圖示（lucide Plus / ArrowRight）也會產生 client rect，
    // 與文字垂直位置不同會被誤算成另一行。行框以 bottom 對齊（同一行不同字型的 top 會差幾 px），容許 2px
    const bottoms: number[] = [];
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    for (let n = walker.nextNode(); n; n = walker.nextNode()) {
      if (!n.textContent?.trim()) continue;
      const range = document.createRange();
      range.selectNodeContents(n);
      for (const r of Array.from(range.getClientRects())) {
        if (r.width > 0 && !bottoms.some((b) => Math.abs(b - r.bottom) <= 2))
          bottoms.push(r.bottom);
      }
    }
    return bottoms.length;
  });
}

export async function expectSingleLine(locator: Locator, label: string) {
  expect(await textLineCount(locator), `${label} 行數`).toBe(1);
  const clipped = await locator.evaluate((el) => el.scrollWidth > el.clientWidth + 0.5);
  expect(clipped, `${label} 文字被裁切（scrollWidth > clientWidth）`).toBe(false);
}

export interface ButtonLook {
  classes: string[];
  fontFamily: string;
  borderRadius: string;
  borderTopStyle: string;
  backgroundColor: string;
  color: string;
}

/** 按鈕實際渲染外觀（回傳給 step 留 JSON 證據；fix-batch14 L2 原生 <button> 統一處理） */
export async function buttonLookOf(locator: Locator): Promise<ButtonLook> {
  return locator.evaluate((el) => {
    const cs = getComputedStyle(el);
    return {
      classes: Array.from(el.classList),
      fontFamily: cs.fontFamily,
      borderRadius: cs.borderTopLeftRadius,
      borderTopStyle: cs.borderTopStyle,
      backgroundColor: cs.backgroundColor,
      color: cs.color
    };
  });
}

/**
 * 專案 Button 元件基底（.btn，fix-batch13 K4）：膠囊圓角、Plus Jakarta Sans；secondary 為 #f3f4f6 底、無框線。
 * 原生 <button> 會是 2px outset 框、直角、系統字。
 */
export async function expectProjectButton(
  locator: Locator,
  label: string,
  variant: 'secondary' | 'danger-outline' = 'secondary'
): Promise<ButtonLook> {
  const look = await buttonLookOf(locator);
  expect(look.classes, `${label} class`).toContain('btn');
  expect(look.borderRadius, `${label} 圓角`).toBe('9999px');
  expect(look.fontFamily, `${label} 字型`).toContain('Plus Jakarta Sans');
  if (variant === 'secondary') {
    expect(look.backgroundColor, `${label} 底色`).toBe('rgb(243, 244, 246)');
    expect(look.borderTopStyle, `${label} 框線`).toBe('none');
  } else {
    expect(look.borderTopStyle, `${label} 框線`).toBe('solid');
    expect(look.color, `${label} 文字色（--color-error）`).toBe('rgb(176, 37, 0)');
  }
  return look;
}
