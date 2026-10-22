const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(path.join(process.env.USERPROFILE, '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core'));

async function measure(page, name) {
    const result = await page.evaluate(() => {
        const roots = [...document.querySelectorAll('[data-okdesk-responsive-dialog="true"], .opc-overlay')];
        const root = roots.at(-1) || document.getElementById('okdesk-mobile-app') || document.body;
        const bad = [...root.querySelectorAll('*')].filter(el => {
            const r = el.getBoundingClientRect();
            const css = getComputedStyle(el);
            return r.width && r.height && css.visibility !== 'hidden' &&
                (r.left < -1 || r.right > innerWidth + 1 ||
                (el.scrollWidth > el.clientWidth + 2 && !['auto', 'scroll'].includes(css.overflowX) &&
                !['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName)));
        }).map(el => ({ tag: el.tagName, text: el.textContent.slice(0, 65), width: el.clientWidth, scroll: el.scrollWidth, left: el.getBoundingClientRect().left, right: el.getBoundingClientRect().right }));
        return { width: innerWidth, bad };
    });
    console.log(JSON.stringify({ name, ...result }));
    await page.screenshot({ path: path.join('app/build/width-checks', `${name}-${result.width}.png`), fullPage: true });
    return result.bad.length;
}

(async () => {
    fs.mkdirSync('app/build/width-checks', { recursive: true });
    const browser = await chromium.launch({ headless: true });
    const fsResponse = await browser.newContext();
    const fsHtml = await (await fsResponse.request.get('https://fs.poscenter.org/')).text();
    const activity = fs.readFileSync('app/src/main/java/ru/poscenter/okdesk/mobile/MainActivity.java', 'utf8');
    const fsCss = JSON.parse(activity.match(/String themeCss = ("[^\n]+")\;/)[1]);
    await fsResponse.close();
    let violations = 0;
    try {
        for (const width of [280, 320, 360, 412]) {
            const page = await browser.newPage({ viewport: { width, height: 740 }, isMobile: true });
            await page.route('**/*', route => route.fulfill({ contentType: 'text/html', body: '<html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body></body></html>' }));
            await page.goto('https://test.okdesk.ru/issues');
            await page.evaluate(() => {
                window.__commands = {};
                window.GM_getValue = (key, fallback) => key.startsWith('okdesk_mobile_view_') ? 'off' : key.startsWith('okdesk_script_theme_') ? 'dark' : fallback;
                window.GM_setValue = () => {};
                window.GM_registerMenuCommand = (name, fn) => window.__commands[name] = fn;
                window.GM_xmlhttpRequest = () => {};
                window.GM_setClipboard = () => {};
                window.unsafeWindow = window;
                window.OkdeskNative = { postMessage() {} };
            });
            await page.addScriptTag({ path: 'app/src/main/assets/okdesk.user.js' });
            await page.evaluate(() => window.__commands['Настройки скрипта Okdesk']());
            violations += await measure(page, 'settings');
            for (const action of ['quick-replies', 'templates', 'transfer', 'domain-rules']) {
                await page.locator(`#okdesk-settings-dialog [data-action="${action}"]`).click();
                violations += await measure(page, action);
                await page.evaluate(() => {
                    const roots = document.querySelectorAll('[data-okdesk-responsive-dialog="true"]');
                    if (roots.length > 1) roots[roots.length - 1].remove();
                });
            }
            await page.goto('https://test.okdesk.ru/issues');
            await page.evaluate(() => {
                window.GM_getValue = (key, fallback) => key.startsWith('okdesk_mobile_view_') ? 'on' : key.startsWith('okdesk_script_theme_') ? 'dark' : key.startsWith('okdesk_api_token_') ? 'offline-test' : fallback;
                window.GM_setValue = () => {};
                window.GM_registerMenuCommand = () => {};
                window.unsafeWindow = window;
                window.__testIssue = { id: 123, title: 'Очень длинное название оборудования и описание заявки '.repeat(3), status: { code: 'opened', name: 'Открыта' }, contact: { name: 'Очень длинное имя клиента' }, description: '<p>Описание</p><pre>' + 'long-text-'.repeat(40) + '</pre>', attachments: [{ id: 77, name: 'Очень длинное имя документа.pdf', attachment_file_name: 'Документ.pdf', url: '/file.pdf' }] };
                window.fetch = async url => {
                    const pathname = new URL(url, location.origin).pathname;
                    const data = pathname.endsWith('/login_link') ? { user_id: 1, user_type: 'employee' } : pathname.endsWith('/comments') ? [{ id: 1, author: { name: 'Длинное имя сотрудника службы поддержки', type: 'employee' }, published_at: '2026-10-12T12:00:00Z', content: '<p>' + 'длинныйтекст'.repeat(20) + '</p>' }] : pathname.endsWith('/list') ? [window.__testIssue] : window.__testIssue;
                    return new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } });
                };
            });
            await page.addScriptTag({ path: 'app/src/main/assets/okdesk.user.js' });
            await page.addStyleTag({ path: 'app/src/main/assets/mobile-theme.css' });
            violations += await measure(page, 'mobile-list');
            await page.locator('.m-tabs-add').click();
            violations += await measure(page, 'mobile-filters');
            await page.locator('.m-sheet-close').first().click();
            await page.locator('.m-row').first().click();
            await page.locator('.m-issue-title').waitFor();
            violations += await measure(page, 'mobile-issue');
            await page.locator('.m-send-actions button').last().click();
            violations += await measure(page, 'mobile-send-menu');

            await page.goto('https://optposcenter.ru/catalog/');
            await page.evaluate(() => {
                document.body.innerHTML = '<main><h1>Каталог</h1></main>';
                document.documentElement.dataset.okdeskAppTheme = 'dark';
                window.__commands = {};
                window.GM_getValue = (key, fallback) => key.endsWith('_index_meta') ? { updatedAt: Date.now() } : fallback;
                window.GM_setValue = () => {};
                window.GM_registerMenuCommand = (name, fn) => window.__commands[name] = fn;
                window.GM_xmlhttpRequest = () => {};
            });
            const injection = fs.readFileSync('app/src/main/java/ru/poscenter/okdesk/mobile/OptposInjectedScript.java', 'utf8');
            await page.addStyleTag({ content: injection.match(/mobileStyle\.textContent=`([^`]+)`/)[1] });
            await page.addStyleTag({ content: injection.match(/controlsStyle\.textContent='([^']+)'/)[1] });
            await page.addScriptTag({ path: 'app/src/main/assets/optposcenter.user.js' });
            violations += await measure(page, 'catalog');
            for (const command of ['Настройки быстрого каталога', 'Добавить локальную позицию', 'Импортировать локальные позиции']) {
                await page.evaluate(name => window.__commands[name](), command);
                violations += await measure(page, `catalog-${Object.keys({ 'Настройки быстрого каталога': 0, 'Добавить локальную позицию': 1, 'Импортировать локальные позиции': 2 }).indexOf(command)}`);
                await page.evaluate(() => document.querySelector('.opc-overlay')?.remove());
            }
            await page.unroute('**/*');
            await page.route('**/*', route => {
                if (route.request().url().includes('storage.yandexcloud.net')) return route.fulfill({ contentType: 'application/xml', body: '<ListBucketResult><IsTruncated>false</IsTruncated><CommonPrefixes><Prefix>01.Сканеры с длинным названием/</Prefix></CommonPrefixes><Contents><Key>long-filename.pdf</Key><Size>123</Size></Contents></ListBucketResult>' });
                return route.fulfill({ contentType: 'text/html', body: fsHtml });
            });
            await page.goto('https://fs.poscenter.org/');
            await page.addStyleTag({ content: fsCss });
            await page.addScriptTag({ path: 'app/src/main/assets/poscenter-files.user.js' });
            await page.locator('.poscenter-copy-folder-link').waitFor();
            violations += await measure(page, 'files');
            await page.close();
        }
    } finally { await browser.close(); }
    console.log(`Total width violations: ${violations}`);
    process.exitCode = violations ? 1 : 0;
})().catch(error => { console.error(error); process.exitCode = 1; });
