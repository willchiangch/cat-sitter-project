#!/usr/bin/env node
import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const SCREENSHOTS_DIR = path.join(__dirname, '..', 'screenshot-report', 'screenshots')
const OUTPUT_FILE = path.join(__dirname, '..', 'screenshot-report', 'index.html')

const SECTIONS = [
  { id: 'public', label: '公開頁面', dir: 'public' },
  { id: 'sitter', label: '保母介面 (Sitter)', dir: 'sitter' },
  { id: 'client', label: '飼主介面 (Client)', dir: 'client' },
]

function readScreenshots(dir) {
  const fullPath = path.join(SCREENSHOTS_DIR, dir)
  if (!fs.existsSync(fullPath)) return []
  return fs.readdirSync(fullPath)
    .filter((f) => f.endsWith('.png'))
    .sort()
    .map((filename) => {
      const imgData = fs.readFileSync(path.join(fullPath, filename))
      return {
        filename,
        label: filename.replace('.png', '').replace(/_/g, '/'),
        src: `data:image/png;base64,${imgData.toString('base64')}`,
      }
    })
}

const buildDate = new Date().toLocaleString('zh-TW', {
  timeZone: 'Asia/Taipei',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
})

const sections = SECTIONS.map((s) => ({ ...s, screenshots: readScreenshots(s.dir) })).filter(
  (s) => s.screenshots.length > 0
)
const totalScreenshots = sections.reduce((sum, s) => sum + s.screenshots.length, 0)

const html = `<!DOCTYPE html>
<html lang="zh-TW">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>WhiskerWatch UI Overview</title>
  <style>
    *{box-sizing:border-box;margin:0;padding:0}
    body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#f4f4f5;color:#18181b}
    header{background:#1e3a8a;color:#fff;padding:20px 32px;display:flex;justify-content:space-between;align-items:center}
    header h1{font-size:1.25rem;font-weight:700;letter-spacing:-.02em}
    header .meta{font-size:.8rem;opacity:.75;text-align:right;line-height:1.8}
    nav{background:#fff;border-bottom:1px solid #e4e4e7;padding:0 24px;display:flex;gap:0;position:sticky;top:0;z-index:10;overflow-x:auto}
    nav a{padding:12px 18px;text-decoration:none;color:#71717a;font-size:.875rem;font-weight:500;border-bottom:3px solid transparent;white-space:nowrap;transition:color .15s}
    nav a:hover,nav a.active{color:#1e3a8a;border-bottom-color:#1e3a8a}
    main{max-width:1400px;margin:0 auto;padding:28px 24px}
    .section{margin-bottom:48px}
    .section-header{display:flex;align-items:center;gap:10px;margin-bottom:16px}
    .section-header h2{font-size:1.05rem;font-weight:600}
    .badge{background:#e4e4e7;color:#52525b;padding:2px 10px;border-radius:20px;font-size:.75rem}
    .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:16px}
    .card{background:#fff;border-radius:10px;overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,.08);cursor:pointer;transition:transform .18s,box-shadow .18s}
    .card:hover{transform:translateY(-3px);box-shadow:0 6px 16px rgba(0,0,0,.12)}
    .card img{width:100%;display:block;border-bottom:1px solid #f0f0f0}
    .card-label{padding:9px 12px;font-size:.78rem;color:#3f3f46;font-weight:500}
    .lightbox{display:none;position:fixed;inset:0;background:rgba(0,0,0,.92);z-index:1000;align-items:center;justify-content:center}
    .lightbox.open{display:flex}
    .lightbox img{max-width:90vw;max-height:88vh;object-fit:contain;border-radius:8px}
    .lb-close{position:fixed;top:16px;right:20px;color:#fff;font-size:2rem;cursor:pointer;background:none;border:none;line-height:1;opacity:.8}
    .lb-close:hover{opacity:1}
    .lb-caption{position:fixed;bottom:16px;left:50%;transform:translateX(-50%);color:#fff;font-size:.85rem;background:rgba(0,0,0,.55);padding:5px 16px;border-radius:20px;white-space:nowrap}
    .lb-nav{position:fixed;top:50%;transform:translateY(-50%);color:#fff;font-size:2rem;cursor:pointer;background:rgba(255,255,255,.12);border:none;width:44px;height:44px;border-radius:50%;display:flex;align-items:center;justify-content:center;transition:background .15s}
    .lb-nav:hover{background:rgba(255,255,255,.22)}
    .lb-prev{left:12px}.lb-next{right:12px}
  </style>
</head>
<body>
  <header>
    <h1>WhiskerWatch UI Overview</h1>
    <div class="meta">
      <div>共 ${totalScreenshots} 個頁面</div>
      <div>產生於 ${buildDate}</div>
    </div>
  </header>

  <nav>
    ${sections.map((s, i) => `<a href="#${s.id}"${i === 0 ? ' class="active"' : ''}>${s.label} (${s.screenshots.length})</a>`).join('\n    ')}
  </nav>

  <main>
    ${sections
      .map(
        (section) => `
    <section class="section" id="${section.id}">
      <div class="section-header">
        <h2>${section.label}</h2>
        <span class="badge">${section.screenshots.length} 頁</span>
      </div>
      <div class="grid">
        ${section.screenshots
          .map(
            ({ filename, label, src }) => `
        <div class="card" data-src="${src}" data-label="${label}" onclick="open(this)">
          <img src="${src}" alt="${label}" loading="lazy">
          <div class="card-label">/${label}</div>
        </div>`
          )
          .join('')}
      </div>
    </section>`
      )
      .join('')}
  </main>

  <div class="lightbox" id="lb">
    <button class="lb-close" onclick="close()">×</button>
    <button class="lb-nav lb-prev" onclick="nav(-1)">‹</button>
    <img id="lb-img" src="" alt="">
    <button class="lb-nav lb-next" onclick="nav(1)">›</button>
    <div class="lb-caption" id="lb-cap"></div>
  </div>

  <script>
    const allCards = [...document.querySelectorAll('.card')]
    let cur = 0
    function open(el) {
      cur = allCards.indexOf(el)
      show()
      document.getElementById('lb').classList.add('open')
    }
    function show() {
      const c = allCards[cur]
      document.getElementById('lb-img').src = c.dataset.src
      document.getElementById('lb-cap').textContent = '/' + c.dataset.label
    }
    function close() { document.getElementById('lb').classList.remove('open') }
    function nav(d) { cur = (cur + d + allCards.length) % allCards.length; show() }
    document.getElementById('lb').addEventListener('click', e => { if (e.target === e.currentTarget) close() })
    document.addEventListener('keydown', e => {
      if (!document.getElementById('lb').classList.contains('open')) return
      if (e.key === 'Escape') close()
      if (e.key === 'ArrowLeft') nav(-1)
      if (e.key === 'ArrowRight') nav(1)
    })

    const secs = document.querySelectorAll('.section')
    const links = document.querySelectorAll('nav a')
    new IntersectionObserver(entries => {
      entries.forEach(e => {
        if (e.isIntersecting) {
          links.forEach(l => l.classList.remove('active'))
          const a = document.querySelector(\`nav a[href="#\${e.target.id}"]\`)
          if (a) a.classList.add('active')
        }
      })
    }, { threshold: 0.25 }).observe(...secs)
    secs.forEach(s => new IntersectionObserver(entries => {
      entries.forEach(e => {
        if (e.isIntersecting) {
          links.forEach(l => l.classList.remove('active'))
          const a = document.querySelector(\`nav a[href="#\${e.target.id}"]\`)
          if (a) a.classList.add('active')
        }
      })
    }, { threshold: 0.25 }).observe(s))
  </script>
</body>
</html>`

fs.mkdirSync(path.dirname(OUTPUT_FILE), { recursive: true })
fs.writeFileSync(OUTPUT_FILE, html)
console.log(`✓ Report generated: ${OUTPUT_FILE}`)
console.log(`✓ Total screenshots: ${totalScreenshots}`)
