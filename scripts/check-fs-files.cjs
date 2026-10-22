const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require(path.join(process.env.USERPROFILE, '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core'));

(async () => {
    const response = await fetch('https://fs.poscenter.org/', { signal: AbortSignal.timeout(30000) });
    assert(response.ok);
    const html = (await response.text()).replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '');
    const activity = fs.readFileSync('app/src/main/java/ru/poscenter/okdesk/mobile/MainActivity.java', 'utf8');
    const themeCss = JSON.parse(activity.match(/String themeCss = ("[^\n]+")\;/)[1]);
    fs.mkdirSync('app/build/fs-file-checks', { recursive: true });
    const browser = await chromium.launch({ headless: true });
    try {
        for (const width of [280, 360, 412, 1024]) {
            const page = await browser.newPage({ viewport: { width, height: 900 } });
            const errors = [];
            page.on('pageerror', error => errors.push(error.message));
            await page.route('**/*', route => route.fulfill({ contentType: 'text/html', body: html }));
            await page.goto('https://fs.poscenter.org/');
            await page.evaluate(() => {
                document.documentElement.dataset.okdeskAppTheme = 'dark';
                window.copied = [];
                Object.defineProperty(navigator, 'clipboard', { value: { writeText: async value => copied.push(value) } });
                document.getElementById('list').innerHTML = '';
                for (const ext of ['zip', 'pdf', 'docx', 'jpg', 'exe', 'txt', 'mp4', 'unknown']) {
                    const row = document.createElement('div');
                    row.className = 'row';
                    row.dataset.testExt = ext;
                    row.innerHTML = `<div class="name-cell"><span class="icon">F</span><div class="name-main">Очень длинное имя файла с инструкцией.${ext}</div></div><div>1 MB</div><div>16.10.2026</div><div class="actions"><a class="action-link" download href="/folder/test.${ext}">Скачать</a></div>`;
                    document.getElementById('list').appendChild(row);
                }
            });
            await page.addScriptTag({ path: 'app/src/main/assets/poscenter-files.user.js' });
            await page.addStyleTag({ content: themeCss });
            await page.waitForFunction(() => document.querySelectorAll('.poscenter-copy-file-link').length === 8);
            assert.equal(await page.locator('.poscenter-file-type').count(), 8);
            for (const ext of ['zip', 'pdf', 'docx', 'jpg', 'exe', 'txt', 'mp4', 'unknown']) {
                const row = page.locator(`[data-test-ext="${ext}"]`);
                assert.equal(await row.locator('.poscenter-file-type').textContent(), ext === 'unknown' ? 'FILE' : ext.toUpperCase());
                const preview = row.locator('.poscenter-open-file');
                const supported = ['pdf', 'jpg', 'txt', 'mp4'].includes(ext);
                assert.equal(await preview.count(), supported ? 1 : 0);
                if (supported) {
                    assert.equal(await preview.getAttribute('target'), '_blank');
                    assert.equal(await preview.getAttribute('href'), `https://fs.poscenter.org/folder/test.${ext}`);
                    assert.match(await preview.getAttribute('rel'), /noopener/);
                }
                await row.locator('.poscenter-copy-file-link').click();
                assert.equal(await page.evaluate(() => copied.at(-1)), `https://fs.poscenter.org/folder/test.${ext}`);
            }
            const overflow = await page.evaluate(() => [...document.querySelectorAll('#list .row, #list .actions, #list .actions > *')].filter(el => {
                const r = el.getBoundingClientRect();
                return r.width && (r.left < -1 || r.right > innerWidth + 1);
            }).map(el => ({ className: el.className, width: el.getBoundingClientRect().width })));
            assert.deepEqual(overflow, [], `Overflow at ${width}px`);
            await page.screenshot({ path: `app/build/fs-file-checks/${width}.png`, fullPage: true });
            await page.evaluate(() => {
                const old = document.getElementById('list');
                const fresh = document.createElement('div'); fresh.id = 'list';
                const row = document.createElement('div'); row.className = 'row';
                row.innerHTML = '<div class="name-cell"><span class="icon">F</span><span class="name-main">new.pdf</span></div><div class="actions"><a class="action-link" download href="/new.pdf">Скачать</a></div>';
                fresh.appendChild(row); old.replaceWith(fresh);
            });
            await page.waitForFunction(() => document.querySelectorAll('.poscenter-open-file').length === 1);
            assert.deepEqual(errors, []);
            console.log(`FS ${width}px: badges, previews, direct links, layout, replacement list passed.`);
            await page.close();
        }
    } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
