const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require(path.join(process.env.USERPROFILE, '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core'));

async function setup(page, start = '/issues', theme = 'dark') {
    await page.route('**/*', route => route.fulfill({ contentType: 'text/html', body: '<html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body></body></html>' }));
    await page.goto('https://test.okdesk.ru' + start);
    await page.evaluate(theme => {
        window.__saved = {};
        window.__calls = [];
        window.GM_getValue = (key, fallback) => key in __saved ? __saved[key] : key.startsWith('okdesk_mobile_view_') ? 'on' : key.startsWith('okdesk_script_theme_') ? theme : key.startsWith('okdesk_api_token_') ? 'offline-test' : fallback;
        window.GM_setValue = (key, value) => { __saved[key] = value; };
        window.GM_registerMenuCommand = () => {};
        window.GM_setClipboard = () => {};
        window.unsafeWindow = window;
        window.__issue = id => ({ id, title: 'Принтер POScenter TT-200 | ' + id + ' | ' + 'Длинное описание неисправности оборудования '.repeat(3), status: { code: 'opened', name: 'Открыта' }, assignee: { name: 'Тестовый сотрудник' }, description: '<p>Тестовое описание</p>', attachments: [] });
        window.fetch = async (url, options) => {
            const pathname = new URL(url, location.origin).pathname;
            __calls.push({ pathname, method: options?.method || 'GET' });
            const id = Number(pathname.match(/\/issues\/(\d+)/)?.[1] || 181466);
            if (window.__slowFirst && id === 181466 && !pathname.endsWith('/list')) await new Promise(resolve => setTimeout(resolve, 180));
            const data = pathname.endsWith('/login_link') ? { user_id: 1, user_type: 'employee' }
                : pathname.endsWith('/comments') ? Array.from({ length: 12 }, (_, index) => ({ id: index + 1, author: { name: 'Сотрудник', type: 'employee' }, content: '<p>Текст комментария к заявке</p>', published_at: '2026-11-27T10:00:00Z' }))
                : pathname.endsWith('/list') ? Array.from({ length: 30 }, (_, index) => __issue(181466 + index)) : __issue(id);
            return new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } });
        };
    }, theme);
    await page.addScriptTag({ path: 'app/src/main/assets/okdesk.user.js' });
    await page.addStyleTag({ path: 'app/src/main/assets/mobile-theme.css' });
}

async function setFold(page, orientation, bounds = {}) {
    await page.evaluate(({ orientation, bounds }) => {
        window.__okdeskFold = { orientation, left: 0, top: 0, right: 0, bottom: 0, width: innerWidth, height: innerHeight, ...bounds };
        window.dispatchEvent(new Event('okdesk-fold-change'));
    }, { orientation, bounds });
    await settleLayout(page);
}

async function settleLayout(page) {
    await page.waitForFunction(() => document.getElementById('okdesk-mobile-app').dataset.layoutPending !== 'true');
    await page.waitForFunction(() => document.getAnimations().every(animation => animation.playState !== 'running'));
}

async function assertFits(page) {
    await settleLayout(page);
    const result = await page.evaluate(() => {
        const app = document.getElementById('okdesk-mobile-app');
        return [...app.querySelectorAll('.m-top, .m-workspace, .m-list-pane, .m-detail-pane, .m-issue-title, .m-send-actions')].filter(el => {
            const r = el.getBoundingClientRect();
            return r.width && r.height && (r.left < -1 || r.right > innerWidth + 1 || el.scrollWidth > el.clientWidth + 2);
        }).map(el => ({ className: el.className, client: el.clientWidth, scroll: el.scrollWidth }));
    });
    assert.deepEqual(result, []);
}

(async () => {
    fs.mkdirSync('app/build/tablet-checks', { recursive: true });
    const browser = await chromium.launch({ headless: true });
    try {
        for (const width of [280, 360, 720, 800, 840, 1024, 1280]) {
            const page = await browser.newPage({ viewport: { width, height: 900 } });
            const errors = [];
            page.on('pageerror', error => errors.push(error.message));
            await setup(page, '/issues', width === 1280 ? 'light' : 'dark');
            const split = width >= 840;
            const app = page.locator('#okdesk-mobile-app');
            await page.locator('.m-row-wrap').first().waitFor();
            assert.equal(await app.getAttribute('data-layout'), split ? 'split' : 'single');
            await page.locator('.m-row-wrap .m-row').first().click();
            await page.locator('.m-issue-title').waitFor();
            assert.equal(await page.locator('.m-list-pane').isVisible(), split);
            assert.equal(await page.locator('.m-row-wrap.is-selected').count(), 1);
            await assertFits(page);
            const editor = page.locator('textarea').first();
            await editor.fill('Несохранённый черновик');
            await page.locator('input[type="file"]').setInputFiles({ name: 'draft.txt', mimeType: 'text/plain', buffer: Buffer.from('test attachment') });
            if (split) {
                const before = await editor.inputValue();
                await page.getByRole('button', { name: 'Обновить список заявок', exact: true }).click();
                await page.waitForFunction(() => document.querySelectorAll('.m-row-wrap').length === 30);
                assert.equal(await editor.inputValue(), before);
                await page.locator('.m-tabs button').nth(1).click();
                await page.locator('.m-list-pane .m-feedback').waitFor();
                await page.locator('.m-tabs button').first().click();
                await page.waitForFunction(() => document.querySelectorAll('.m-row-wrap').length === 30);
                assert.equal(await editor.inputValue(), before);
                await page.evaluate(() => {
                    const pane = document.querySelector('.m-list-pane'); pane.scrollTop = 100;
                    document.querySelector('.m-detail-pane').scrollTop = 200;
                });
                assert(await page.locator('.m-list-pane').evaluate(el => el.scrollTop) > 0);
                await page.setViewportSize({ width: 360, height: 740 });
                await page.waitForFunction(() => document.getElementById('okdesk-mobile-app').dataset.layout === 'single');
                assert.equal(await editor.inputValue(), before);
                await assertFits(page);
                await page.setViewportSize({ width, height: 900 });
                await page.waitForFunction(() => document.getElementById('okdesk-mobile-app').dataset.layout === 'split');
                assert.equal(await editor.inputValue(), before);
                assert.equal(await page.locator('input[type="file"]').evaluate(el => el.files[0]?.name), 'draft.txt');
                await page.locator('[data-action="layout"]').click();
                await page.getByRole('button', { name: 'Одна панель', exact: true }).click();
                assert.equal(await app.getAttribute('data-layout'), 'single');
                assert.equal(await editor.inputValue(), before);
                await page.locator('[data-action="layout"]').click();
                await page.getByRole('button', { name: 'Автоматически', exact: true }).click();
                assert.equal(await app.getAttribute('data-layout'), 'split');
                await page.locator('.m-list-pane').evaluate(el => { el.scrollTop = 0; });
                await page.locator('.m-detail-pane').evaluate(el => { el.scrollTop = 0; });
            } else if (width >= 720) {
                await page.locator('[data-action="layout"]').click();
                await page.getByRole('button', { name: 'Две панели', exact: true }).click();
                assert.equal(await app.getAttribute('data-layout'), 'split');
                assert.equal(await editor.inputValue(), 'Несохранённый черновик');
                await assertFits(page);
                await page.locator('[data-action="layout"]').click();
                await page.getByRole('button', { name: 'Автоматически', exact: true }).click();
                assert.equal(await app.getAttribute('data-layout'), 'single');
            }
            await page.screenshot({ path: `app/build/tablet-checks/${width}.png` });
            await page.locator('[data-action="back"]').click();
            assert.equal(await page.locator('.m-list-pane').isVisible(), true);
            assert.equal(await page.locator('.m-row-wrap.is-selected').count(), 0);
            if (split) await page.locator('.m-empty-detail').waitFor();
            assert.deepEqual(errors, []);
            assert.deepEqual(await page.evaluate(() => __calls.filter(call => call.method !== 'GET' && !call.pathname.endsWith('/login_link'))), []);
            console.log(`${width}px: layout, selection, navigation, width${split ? ', independent panes, resize/draft preservation, manual mode' : ''} passed.`);
            await page.close();
        }
        const page = await browser.newPage({ viewport: { width: 1024, height: 800 } });
        await setup(page, '/issues/181466');
        await page.locator('.m-issue-title').waitFor();
        await page.locator('.m-row-wrap').first().waitFor();
        await page.evaluate(() => { window.__slowFirst = true; });
        await page.locator('.m-row-wrap .m-row').nth(0).click();
        await page.locator('.m-row-wrap .m-row').nth(1).click();
        await page.waitForFunction(() => document.querySelector('.m-issue-title')?.textContent.includes('181467'));
        await page.waitForTimeout(250);
        assert.match(await page.locator('.m-issue-title').textContent(), /181467/);
        await page.goBack();
        await page.waitForFunction(() => document.querySelector('.m-issue-title')?.textContent.includes('181466'));
        console.log('Direct issue load, rapid switching, stale-response protection and browser Back passed.');
        await page.close();
        const flex = await browser.newPage({ viewport: { width: 900, height: 1000 } });
        const errors = [];
        flex.on('pageerror', error => errors.push(error.message));
        await setup(flex, '/issues/181466');
        await flex.locator('.m-issue-title').waitFor();
        const editor = flex.getByRole('textbox', { name: 'Текст комментария', exact: true });
        await editor.fill('Flex draft');
        await flex.locator('input[type="file"]').setInputFiles({ name: 'flex.txt', mimeType: 'text/plain', buffer: Buffer.from('attachment') });
        await editor.focus();
        await editor.evaluate(el => el.setSelectionRange(3, 3));
        await setFold(flex, 'horizontal', { top: 490, bottom: 510, right: 900 });
        assert.equal(await flex.locator('#okdesk-mobile-app').getAttribute('data-flex'), 'tabletop');
        await flex.locator('.m-flex-read .m-thread-section').waitFor();
        await flex.locator('.m-flex-controls textarea').waitFor();
        const positions = await flex.evaluate(() => {
            const read = document.querySelector('.m-flex-read').getBoundingClientRect();
            const controls = document.querySelector('.m-flex-controls').getBoundingClientRect();
            return { readBottom: read.bottom, controlsTop: controls.top };
        });
        assert(positions.readBottom <= 482 && positions.controlsTop >= 518);
        assert.equal(await editor.inputValue(), 'Flex draft');
        assert(await editor.evaluate(el => document.activeElement === el && el.selectionStart === 3));
        await assertFits(flex);
        await flex.screenshot({ path: 'app/build/tablet-checks/flex-tabletop.png' });
        await flex.locator('[data-action="back"]').click();
        await settleLayout(flex);
        const searchTools = flex.locator('.m-list-tools');
        assert.equal(await searchTools.isVisible(), true);
        await searchTools.getByRole('searchbox', { name: 'Поиск заявки', exact: true }).waitFor();
        await searchTools.getByRole('button', { name: 'Вкладки и фильтры' }).waitFor();
        const toolsTop = await searchTools.evaluate(el => el.getBoundingClientRect().top);
        assert(toolsTop >= 518);
        await assertFits(flex);
        await flex.screenshot({ path: 'app/build/tablet-checks/flex-list.png' });
        await searchTools.getByRole('searchbox', { name: 'Поиск заявки', exact: true }).fill('181467');
        await searchTools.getByRole('searchbox', { name: 'Поиск заявки', exact: true }).press('Enter');
        await flex.locator('.m-issue-title').waitFor();
        await settleLayout(flex);
        assert.equal(await searchTools.isVisible(), false);
        await flex.locator('[data-action="back"]').click();
        await settleLayout(flex);
        await searchTools.locator('.m-recent-query').filter({ hasText: '181467' }).click();
        await flex.locator('.m-issue-title').waitFor();
        await editor.fill('Flex draft');
        await flex.locator('input[type="file"]').setInputFiles({ name: 'flex.txt', mimeType: 'text/plain', buffer: Buffer.from('attachment') });
        await editor.focus();
        await editor.evaluate(el => el.setSelectionRange(3, 3));
        await setFold(flex, 'vertical', { left: 440, right: 460, bottom: 1000 });
        assert.equal(await flex.locator('#okdesk-mobile-app').getAttribute('data-flex'), 'book');
        const sides = await flex.evaluate(() => ({
            left: document.querySelector('.m-list-pane').getBoundingClientRect().right,
            right: document.querySelector('.m-detail-pane').getBoundingClientRect().left,
        }));
        assert(sides.left <= 432 && sides.right >= 468);
        assert.equal(await editor.inputValue(), 'Flex draft');
        assert(await editor.evaluate(el => document.activeElement === el && el.selectionStart === 3));
        assert.equal(await flex.locator('input[type="file"]').evaluate(el => el.files[0]?.name), 'flex.txt');
        await assertFits(flex);
        await flex.screenshot({ path: 'app/build/tablet-checks/flex-book.png' });
        await setFold(flex, 'horizontal', { top: 490, bottom: 510, right: 900 });
        await flex.locator('[data-action="layout"]').click();
        await flex.getByRole('checkbox', { name: 'Учитывать сгиб экрана (Flex)' }).uncheck();
        await flex.locator('.m-sheet-close').click();
        assert.equal(await flex.locator('#okdesk-mobile-app').getAttribute('data-flex'), 'none');
        assert.equal(await editor.inputValue(), 'Flex draft');
        await setFold(flex, 'none');
        assert.equal(await flex.locator('.m-flex-read').count(), 0);
        assert.equal(await flex.locator('[data-mobile-section="compose"]').count(), 1);
        assert.equal(await flex.locator('[data-mobile-section="comments"]').count(), 1);
        assert.deepEqual(errors, []);
        console.log('Flex: horizontal/vertical hinge geometry, UI relocation, opt-out, and draft/files preserved.');
        await flex.locator('[data-action="layout"]').click();
        await flex.getByRole('checkbox', { name: 'Учитывать сгиб экрана (Flex)' }).check();
        await flex.locator('.m-sheet-close').click();
        await flex.setViewportSize({ width: 360, height: 800 });
        await setFold(flex, 'horizontal', { top: 390, bottom: 410, right: 360 });
        await flex.locator('.m-flex-controls textarea').waitFor();
        await assertFits(flex);
        await flex.screenshot({ path: 'app/build/tablet-checks/flex-phone.png' });
        // Native physical pixels map to the same CSS bounds at higher density.
        await setFold(flex, 'horizontal', { width: 720, height: 1600, top: 780, bottom: 820, right: 720 });
        assert.equal(await flex.locator('#okdesk-mobile-app').getAttribute('data-flex'), 'tabletop');
        await setFold(flex, 'horizontal', { top: 10, bottom: 10, right: 360 });
        assert.equal(await flex.locator('#okdesk-mobile-app').getAttribute('data-flex'), 'none');
        assert.equal(await editor.inputValue(), 'Flex draft');
        console.log('Phone Flex, pixel scaling, focus/selection and insufficient-space fallback passed.');
        await flex.emulateMedia({ reducedMotion: 'reduce' });
        await setFold(flex, 'horizontal', { top: 390, bottom: 410, right: 360 });
        assert.equal(await flex.locator('#okdesk-mobile-app').getAttribute('data-motion'), 'off');
        await flex.locator('[data-action="back"]').click();
        await settleLayout(flex);
        await assertFits(flex);
        await flex.screenshot({ path: 'app/build/tablet-checks/flex-list-phone.png' });
        await setFold(flex, 'none');
        assert.equal(await searchTools.isVisible(), false);
        assert.equal(await flex.locator('.m-list-pane [data-role="list-controls"]').count(), 1);
        console.log('Flex list: bottom search/filter/history, recent issue navigation, controls restoration and reduced motion passed.');
        await flex.emulateMedia({ reducedMotion: 'no-preference' });
        await flex.evaluate(() => {
            window.__okdeskFold = { orientation: 'horizontal', width: 360, height: 800, left: 0, top: 390, right: 360, bottom: 410 };
            window.dispatchEvent(new Event('okdesk-fold-change'));
        });
        await flex.waitForFunction(() => document.getAnimations().some(animation => animation.playState === 'running'));
        assert.equal(await flex.locator('#okdesk-mobile-app').getAttribute('data-motion'), 'on');
        await settleLayout(flex);
        await flex.evaluate(() => {
            window.__flexChanges = [];
            new MutationObserver(records => records.forEach(() => __flexChanges.push(document.getElementById('okdesk-mobile-app').dataset.flex)))
                .observe(document.getElementById('okdesk-mobile-app'), { attributes: true, attributeFilter: ['data-flex'] });
            for (let i = 0; i < 20; i++) {
                window.__okdeskFold.orientation = i % 2 ? 'none' : 'horizontal';
                window.dispatchEvent(new Event('okdesk-fold-change'));
            }
        });
        await settleLayout(flex);
        assert.equal(await flex.locator('#okdesk-mobile-app').getAttribute('data-flex'), 'none');
        assert(await flex.evaluate(() => __flexChanges.length <= 2));
        console.log('Smooth transitions run, respect reduced motion, and collapse hinge-event bursts into one reflow.');
        await flex.close();
    } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
