const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const { chromium } = require(path.join(process.env.USERPROFILE, '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core'));

const source = fs.readFileSync('app/src/main/assets/okdesk.user.js', 'utf8');
new vm.Script(source);
const ttl = 7 * 86400000;
let saved = [];
const helpers = vm.createContext({ MOBILE_RECENT_SEARCHES_KEY: 'recent', MOBILE_RECENT_SEARCH_TTL: ttl,
    GM_getValue: () => saved, GM_setValue: (_, value) => { saved = value; } });
vm.runInContext(source.slice(source.indexOf('    function mobileReadRecentSearches('), source.indexOf('    function initMobileView(')), helpers);
const now = Date.now();
for (let i = 0; i < 12; i++) helpers.mobileRememberSearch(String(180000 + i), now + i);
assert.equal(saved.length, 10);
helpers.mobileRememberSearch('180005', now + 20);
assert.equal(saved[0].query, '180005');
assert.equal(saved.filter(item => item.query === '180005').length, 1);
helpers.mobileRememberSearch(' Printer ', now + 21);
helpers.mobileRememberSearch('printer', now + 22);
assert.equal(saved.filter(item => item.query.toLowerCase() === 'printer').length, 1);
assert.equal(helpers.mobileReadRecentSearches(now + ttl + 23).length, 0);
saved = [{ query: 'old', at: now - ttl }, null, { query: '', at: now }, { query: 'bad', at: 'today' }];
assert.equal(helpers.mobileReadRecentSearches(now).length, 0);
console.log('History: limit, ordering, deduplication, expiry and invalid data passed.');

(async () => {
    fs.mkdirSync('app/build/recent-search-checks', { recursive: true });
    const browser = await chromium.launch({ headless: true });
    try {
        for (const width of [280, 360, 412]) {
            const page = await browser.newPage({ viewport: { width, height: 740 }, isMobile: true });
            const errors = [];
            page.on('pageerror', error => errors.push(error.message));
            await page.route('**/*', route => route.fulfill({ contentType: 'text/html', body: '<html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body></body></html>' }));
            await page.goto('https://test.okdesk.ru/issues');
            await page.evaluate(() => {
                window.__saved = {};
                window.GM_getValue = (key, fallback) => key in __saved ? __saved[key] : key.startsWith('okdesk_mobile_view_') ? 'on' : key.startsWith('okdesk_script_theme_') ? 'dark' : key.startsWith('okdesk_api_token_') ? 'offline-test' : fallback;
                window.GM_setValue = (key, value) => { __saved[key] = value; };
                window.GM_registerMenuCommand = () => {};
                window.GM_setClipboard = () => {};
                window.unsafeWindow = window;
                window.fetch = async url => {
                    const pathname = new URL(url, location.origin).pathname;
                    const issue = { id: 181466, title: 'Тестовая заявка', status: { code: 'opened', name: 'Открыта' }, description: '', attachments: [] };
                    const data = pathname.endsWith('/login_link') ? { user_id: 1, user_type: 'employee' } : pathname.endsWith('/comments') ? [] : pathname.endsWith('/list') ? [issue] : issue;
                    return new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } });
                };
            });
            await page.addScriptTag({ path: 'app/src/main/assets/okdesk.user.js' });
            await page.addStyleTag({ path: 'app/src/main/assets/mobile-theme.css' });
            const search = page.getByRole('searchbox', { name: 'Поиск заявки', exact: true });
            await search.fill('181466');
            await search.press('Enter');
            await page.locator('.m-issue-title').waitFor();
            await page.locator('[data-action="back"]').click();
            await page.locator('.m-recent-query').filter({ hasText: '181466' }).click();
            await page.locator('.m-issue-title').waitFor();
            await page.locator('[data-action="back"]').click();
            await search.fill('Тестовая заявка');
            await search.press('Enter');
            assert.equal(await page.locator('.m-recent-query').count(), 2);
            await page.reload();
            // Simulate persistent native storage after restarting the page.
            await page.evaluate(() => {
                window.__saved = { ['okdesk_mobile_recent_searches_' + location.host]: [{ query: 'Очень длинный поисковый запрос '.repeat(8), at: Date.now() }] };
                window.GM_getValue = (key, fallback) => key in __saved ? __saved[key] : key.startsWith('okdesk_mobile_view_') ? 'on' : key.startsWith('okdesk_script_theme_') ? 'dark' : fallback;
                window.GM_setValue = (key, value) => { __saved[key] = value; };
                window.GM_registerMenuCommand = () => {};
                window.unsafeWindow = window;
            });
            await page.addScriptTag({ path: 'app/src/main/assets/okdesk.user.js' });
            await page.addStyleTag({ path: 'app/src/main/assets/mobile-theme.css' });
            await page.locator('.m-recent-query').waitFor();
            const fits = await page.evaluate(() => {
                const app = document.getElementById('okdesk-mobile-app');
                const r = app.querySelector('.m-recent-searches').getBoundingClientRect();
                return app.scrollWidth <= innerWidth && r.left >= 0 && r.right <= innerWidth;
            });
            assert(fits, `Overflow at ${width}px`);
            await page.screenshot({ path: `app/build/recent-search-checks/${width}.png` });
            await page.getByRole('button', { name: 'Очистить историю поиска заявок' }).click();
            assert.equal(await page.locator('.m-recent-query').count(), 0);
            assert.deepEqual(errors, []);
            await page.close();
            console.log(`Mobile ${width}px: enter, back, reopen, text search, stored history, clear and width passed.`);
        }
    } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
