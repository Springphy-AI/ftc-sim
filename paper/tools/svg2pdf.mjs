// Converts the report's figures (docs/figures/*.svg, drawn by docs/figures/src/figures.py) to vector PDFs for
// the paper, using headless Chrome, which understands the SVGs' CSS variables. In the PDF the figure keeps its
// light palette, its text is set in Inter, and its headline and subtitle are left out: the paper's caption
// carries them. The headlines are written to figures/captions.json.
//
//   cd paper/tools && npm install && node svg2pdf.mjs            (Chrome or Edge must be installed)
import { readFileSync, readdirSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer-core';

const here = dirname(fileURLToPath(import.meta.url));
const src = join(here, '..', '..', 'docs', 'figures');
const out = join(here, '..', 'figures');
const browserPath = [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser',
].find(existsSync);
if (!browserPath) throw new Error('Chrome or Edge not found');

const LIGHT = '--f-ink:#1f2328;--f-quiet:#59636e;--f-edge:#818b98;--f-grid:#d1d9e0;--f-tint:#f6f8fa;'
  + '--f-accent:#0969da;--f-red:#cf222e;--f-warn:#bf8700;--f-good:#1a7f37';
const page0 = (svg) => `<!doctype html><html><head><meta charset="utf-8">
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=block" rel="stylesheet">
<style>@page{margin:0}html,body{margin:0;background:#fff}svg{display:block}
svg.ftcfig{${LIGHT} !important}svg,svg text{font-family:Inter,'Segoe UI',Arial,sans-serif !important}</style>
</head><body>${svg}</body></html>`;

const browser = await puppeteer.launch({ executablePath: browserPath, headless: true });
const captions = {};
for (const file of readdirSync(src).filter((f) => f.endsWith('.svg')).sort()) {
  const slug = basename(file, '.svg');
  const page = await browser.newPage();
  await page.setContent(page0(readFileSync(join(src, file), 'utf8')), { waitUntil: 'networkidle0' });
  await page.evaluateHandle('document.fonts.ready');
  const box = await page.evaluate(() => {
    const svg = document.querySelector('svg');
    // The headline (x 24, y 32) and the subtitle (x 24, y 52) are the root's own text elements.
    const head = [...svg.children].filter((t) => t.tagName === 'text' && t.getAttribute('x') === '24'
      && ['32', '52'].includes(t.getAttribute('y')));
    const words = head.map((t) => t.textContent.trim());
    head.forEach((t) => t.remove());
    const b = svg.getBBox();
    const pad = 6;
    const vb = [Math.floor(b.x - pad), Math.floor(b.y - pad), Math.ceil(b.width + 2 * pad), Math.ceil(b.height + 2 * pad)];
    svg.setAttribute('viewBox', vb.join(' '));
    svg.setAttribute('width', vb[2]);
    svg.setAttribute('height', vb[3]);
    return { w: vb[2], h: vb[3], words };
  });
  captions[slug] = { title: box.words[0] || '', subtitle: box.words[1] || '' };
  await page.pdf({ path: join(out, `${slug}.pdf`), width: `${box.w}px`, height: `${box.h}px`, printBackground: true, pageRanges: '1' });
  await page.close();
  console.log(`${slug}.pdf  ${box.w} x ${box.h}`);
}
writeFileSync(join(out, 'captions.json'), JSON.stringify(captions, null, 2) + '\n');
await browser.close();
