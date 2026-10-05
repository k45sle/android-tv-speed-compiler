import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdir, rm } from 'node:fs/promises';
import path from 'node:path';

const packagePath = process.env.PLAYWRIGHT_MODULE;
if (!packagePath) throw new Error('Set PLAYWRIGHT_MODULE to the installed Playwright package path.');
const require = createRequire(import.meta.url);
const { chromium } = require(packagePath);
const baseURL = process.env.SMOKE_URL || 'http://127.0.0.1:8765';
const password = 'fake browser smoke password only';
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 390, height: 844 }, acceptDownloads: true, colorScheme: 'dark' });
const pageErrors = [];
const watchRequests = [];
let monitoringRequests = 0;
let pairRequests = 0;
page.setDefaultTimeout(10000);
page.on('pageerror', error => pageErrors.push(error.message));
page.on('request', request => {
  if (request.method() === 'POST' && request.url().endsWith('/api/pair')) pairRequests += 1;
  if (request.method() === 'PUT' && request.url().endsWith('/api/monitoring')) monitoringRequests += 1;
  if (request.method() === 'POST' && /\/api\/devices\/[^/]+\/watch$/.test(new URL(request.url()).pathname)) watchRequests.push(request.postDataJSON());
});
const work = path.resolve('../../work');
const capture = name => page.screenshot({ path: path.join(work, name), fullPage: true });
const wizardStep = number => page.locator(`.wizard-step[data-step="${number}"]`);
const status = async () => page.evaluate(async () => (await (await fetch('/api/status')).json()));

try {
  await mkdir(work, { recursive: true });
  await page.goto(baseURL);
  const appearance = page.locator('#appearance');
  assert.equal(await appearance.inputValue(), 'system');
  assert.equal(await page.locator('html').getAttribute('data-theme'), 'system');
  assert.equal(await page.locator('html').evaluate(element => getComputedStyle(element).colorScheme), 'dark');
  await appearance.selectOption('light');
  assert.equal(await page.locator('html').evaluate(element => getComputedStyle(element).colorScheme), 'light');
  await appearance.selectOption('night');
  await page.reload();
  assert.equal(await page.locator('#appearance').inputValue(), 'night');
  assert.equal(await page.locator('html').evaluate(element => getComputedStyle(element).colorScheme), 'dark');

  await page.locator('#token').fill('fake-browser-smoke-bootstrap-token-2026-only');
  await page.locator('#password').fill(password);
  await page.locator('#auth-submit').click();
  await page.locator('#start-setup').waitFor();
  assert.equal(await page.locator('#appearance').inputValue(), 'night');
  assert.equal(await page.locator('#advanced-settings').getAttribute('open'), null);
  assert.equal(await page.locator('#settings-form').isVisible(), false);
  assert.equal(await page.getByRole('button', { name: 'Start setup' }).count(), 1);
  await page.setViewportSize({ width: 1280, height: 900 });
  await capture('wizard-first-screen.png');
  assert.equal((await status()).monitoring_enabled, false);

  await page.locator('#logout').click();
  await page.locator('#auth-title').waitFor();
  assert.equal(await page.locator('#appearance').inputValue(), 'night');
  assert.equal(await page.locator('#auth-title').textContent(), 'Sign in');
  await page.locator('#password').fill(password);
  await page.locator('#auth-submit').click();
  await page.locator('#start-setup').waitFor();
  await page.locator('#appearance').selectOption('light');
  assert.equal(await page.locator('html').evaluate(element => getComputedStyle(element).colorScheme), 'light');
  await page.locator('#appearance').selectOption('system');
  assert.equal(await page.locator('html').evaluate(element => getComputedStyle(element).colorScheme), 'dark');

  await page.locator('#start-setup').click();
  assert.equal(await wizardStep(0).isVisible(), true);
  assert.equal(await wizardStep(1).isVisible(), false);
  await capture('wizard-name-step.png');
  await page.locator('#wizard-next').click();
  assert.equal(await page.locator('#wizard-name').evaluate(input => input.validity.valid), false);
  await page.locator('#wizard-name').fill('Smoke living room');
  await page.locator('#wizard-next').click();
  assert.equal(await wizardStep(1).isVisible(), true);
  assert.equal(await wizardStep(2).isVisible(), false);
  await page.locator('#wizard-next').click();
  assert.equal(await page.locator('#wizard-endpoint').evaluate(input => input.validity.valid), false);
  await page.locator('#wizard-discover').click();
  await page.getByText('pairing: 10.0.0.5:37123').waitFor();
  await page.getByText('connect: 10.0.0.5:41267').waitFor();
  assert.equal(await page.locator('#wizard-endpoint').inputValue(), '');
  await page.getByRole('button', { name: 'Use connection endpoint' }).click();
  assert.equal(await page.locator('#wizard-endpoint').inputValue(), '10.0.0.5:41267');
  await capture('wizard-connection-step.png');
  await page.locator('#wizard-next').click();
  assert.equal(await wizardStep(2).isVisible(), true);
  await capture('wizard-pair-step.png');
  await page.locator('#wizard-next').click();
  assert.equal(await page.locator('#wizard-pair-endpoint').evaluate(input => input.validity.valid), false);
  await page.locator('#wizard-pair-endpoint').fill('10.0.0.5:37123');
  await page.locator('#wizard-code').fill('123456');
  const submitWizardPair = page.locator('#wizard-next');
  await submitWizardPair.click();
  await page.waitForFunction(() => document.querySelector('#wizard-next').disabled);
  const pairingRun = await page.locator('#wizard').getAttribute('data-run');
  assert.equal(await page.locator('#setup-entry').isVisible(), false);
  assert.equal(await page.locator('#devices-section').isVisible(), false);
  assert.equal(await page.locator('#recent-work').isVisible(), false);
  assert.equal(await page.locator('#advanced-settings').isVisible(), false);
  assert.equal(await page.locator('#dashboard-footer').isVisible(), false);
  assert.equal(await page.locator('#appearance').isVisible(), true);
  await page.locator('#start-setup').dispatchEvent('click');
  assert.equal(await page.locator('#wizard').getAttribute('data-run'), pairingRun, 'activation during pairing must not reset the run');
  await submitWizardPair.dispatchEvent('click');
  await wizardStep(3).waitFor({ state: 'visible' });
  assert.equal(pairRequests, 1, 'pair submission is protected against repeated clicks');
  assert.equal(await page.locator('#wizard-name').inputValue(), 'Smoke living room', 'reopening during pairing must preserve the active run');
  assert.equal(await page.locator('#wizard-code').inputValue(), '');
  await page.locator('#wizard-apps .wizard-app').first().waitFor();
  await capture('wizard-app-step.png');
  const appChecks = page.locator('#wizard-apps input[type="checkbox"]');
  assert.equal(await appChecks.count(), 2);
  await appChecks.nth(0).check();
  await appChecks.nth(1).check();
  await page.locator('#wizard-app-save').click();
  await page.waitForFunction(() => document.querySelector('#wizard-app-save').disabled);
  assert.equal(await appChecks.nth(0).isDisabled(), true, 'app selections lock while a save is pending');
  assert.equal(await page.locator('#wizard-app-skip').isDisabled(), true);
  assert.equal(await page.locator('#wizard-app-retry').isDisabled(), true);
  assert.equal(await appChecks.nth(0).isChecked(), true);
  assert.equal(await appChecks.nth(1).isChecked(), true);
  await page.locator('#wizard-app-save').dispatchEvent('click');
  await page.locator('#wizard-app-skip').dispatchEvent('click');
  await page.locator('#wizard-app-retry').dispatchEvent('click');
  await page.getByText(/Could not save com\.nuvio\.tv\.test/).waitFor();
  assert.equal(await appChecks.nth(0).isDisabled(), true);
  assert.equal(await appChecks.nth(1).isChecked(), true);
  const mainRequests = watchRequests.filter(request => request.package_id === 'com.nuvio.tv');
  const testRequests = watchRequests.filter(request => request.package_id === 'com.nuvio.tv.test');
  assert.equal(mainRequests.length, 1);
  assert.equal(mainRequests[0].initial_compile, false);
  assert.equal(testRequests.length, 1);
  assert.equal(testRequests[0].initial_compile, false);
  assert.deepEqual((await status()).jobs, [], 'guided baseline saves must not queue compiles');
  assert.equal((await status()).monitoring_enabled, false, 'setup must preserve paused monitoring');
  await page.locator('#wizard-app-save').click();
  await wizardStep(4).waitFor({ state: 'visible' });
  assert.equal(watchRequests.filter(request => request.package_id === 'com.nuvio.tv').length, 1, 'retry must not resave successful apps');
  assert.equal(watchRequests.filter(request => request.package_id === 'com.nuvio.tv.test').length, 2, 'retry only resaves the app whose response failed');
  await capture('wizard-review-step.png');
  assert.match(await page.locator('#wizard-review').textContent(), /Nuvio.*Nuvio Test/);
  assert.equal(await page.getByRole('button', { name: 'Resume monitoring' }).count(), 1);
  assert.equal(await page.locator('#wizard-back').isVisible(), true);
  await page.locator('#wizard-back').click();
  await wizardStep(3).waitFor({ state: 'visible' });
  assert.equal(await page.locator('#wizard-back').isVisible(), false, 'a saved TV cannot navigate back into completed connection or pairing fields');
  assert.equal(await wizardStep(1).isVisible(), false);
  assert.equal(await wizardStep(2).isVisible(), false);
  await page.locator('#wizard-app-skip').click();
  await wizardStep(4).waitFor({ state: 'visible' });
  await page.locator('#wizard-next').click();
  await page.locator('.device').filter({ hasText: 'Smoke living room' }).waitFor();
  assert.equal((await status()).monitoring_enabled, false);
  assert.deepEqual((await status()).jobs, []);

  // Cancelled setup clears a transient code and reopening begins with clean fields.
  await page.getByRole('button', { name: 'Add another TV' }).click();
  await page.locator('#wizard-name').fill('Cancelled TV');
  await page.locator('#wizard-next').click();
  await page.locator('#wizard-endpoint').fill('10.0.0.8:41267');
  await page.locator('#wizard-next').click();
  await page.locator('#wizard-pair-endpoint').fill('10.0.0.8:37123');
  await page.locator('#wizard-code').fill('654321');
  await page.locator('#wizard-cancel').click();
  assert.equal(await page.locator('#wizard-code').inputValue(), '');
  await page.getByRole('button', { name: 'Add another TV' }).click();
  assert.equal(await page.locator('#wizard-code').inputValue(), '');
  assert.equal(await page.locator('#wizard-endpoint').inputValue(), '');
  await page.locator('#wizard-cancel').click();

  // A failed pairing exposes a manual retry without automatically replaying the mutation.
  const priorPairAttempts = pairRequests;
  await page.getByRole('button', { name: 'Add another TV' }).click();
  await page.locator('#wizard-name').fill('Smoke pair failure');
  await page.locator('#wizard-next').click();
  await page.locator('#wizard-endpoint').fill('10.0.0.10:41267');
  await page.locator('#wizard-next').click();
  await page.locator('#wizard-pair-endpoint').fill('10.0.0.10:37123');
  await page.locator('#wizard-code').fill('333333');
  await page.locator('#wizard-next').click();
  await page.getByText(/Injected one-time pairing failure/).waitFor();
  await page.waitForTimeout(900);
  assert.equal(pairRequests, priorPairAttempts + 1, 'failed pairing is not automatically retried');
  await page.locator('#wizard-cancel').click();

  // Pairing through this service's ADB keys skips the pairing code and /api/pair.
  const priorPairRequests = pairRequests;
  await page.getByRole('button', { name: 'Add another TV' }).click();
  await page.locator('#wizard-name').fill('Smoke bedroom');
  await page.locator('input[name="pairing-mode"][value="paired"]').check();
  await page.locator('#wizard-next').click();
  await page.locator('#wizard-endpoint').fill('10.0.0.6:41000');
  await page.locator('#wizard-next').click();
  await wizardStep(3).waitFor({ state: 'visible' });
  assert.equal(pairRequests, priorPairRequests);
  assert.equal(await page.locator('#wizard-code').inputValue(), '');
  await page.locator('#wizard-app-skip').click();
  await page.locator('#wizard-next').click();
  await page.locator('.device').filter({ hasText: 'Smoke bedroom' }).waitFor();
  assert.equal((await status()).monitoring_enabled, false);

  // Existing device operations remain accessible but start collapsed.
  let card = page.locator('.device').filter({ hasText: 'Smoke bedroom' });
  assert.equal(await card.locator('.watched-summary').textContent(), 'No apps selected');
  const manageDetails = card.locator('details.device-manage');
  assert.equal(await manageDetails.getAttribute('open'), null);
  await manageDetails.locator('summary').click();
  await card.getByRole('textbox', { name: 'TV name' }).waitFor();
  await card.getByText(/Pinned TV serial:/).waitFor();
  const appsDetails = card.locator('details.app-management');
  assert.equal(await appsDetails.getAttribute('open'), null);
  await appsDetails.locator('summary').click();
  const loadApps = appsDetails.getByRole('button', { name: 'Load fresh app inventory' });
  assert.equal(await loadApps.isVisible(), true, 'fresh inventory action is available from app management');
  await loadApps.click();
  await card.getByText('com.nuvio.tv', { exact: true }).waitFor();
  const appRows = card.locator('.app-row');
  assert.equal(await appRows.count(), 2);
  const mainRow = appRows.nth(0);
  assert.equal(await mainRow.locator('code.package').textContent(), 'com.nuvio.tv');
  assert.equal(await mainRow.getByRole('checkbox').nth(1).isChecked(), false);
  assert.equal(await mainRow.getByRole('checkbox').nth(2).isChecked(), false);
  const watch = mainRow.getByRole('checkbox', { name: 'Watch Nuvio' });
  await watch.uncheck();
  await watch.check();
  await page.getByText(/Watching enabled\. Current version saved as its baseline/).waitFor();
  assert.match(await card.locator('.watched-summary').textContent(), /Watching: Nuvio/,
    'watching a previously-unwatched app updates the cached TV summary');
  await mainRow.getByRole('button', { name: 'Queue compile' }).click();
  await page.getByText('Manual compile queued. The scheduler will report its current wait reason.').waitFor();

  await page.locator('#advanced-settings > summary').click();
  await page.locator('#advanced-settings details').filter({ hasText: 'Global scheduling' }).locator('summary').click();
  await page.locator('#settings-form [name="poll_interval_seconds"]').fill('120');
  await page.locator('#settings-form [name="max_attempts"]').fill('4');
  await page.locator('#settings-form [name="window_start"]').fill('23:00');
  await page.locator('#settings-form [name="window_end"]').fill('06:00');
  await page.locator('#settings-form [name="timezone"]').fill('America/Chicago');
  await page.locator('#settings-form button[type="submit"]').click();
  await page.getByText('Global scheduling settings saved.').waitFor();
  await page.locator('#advanced-settings details').filter({ hasText: 'Monitoring control' }).locator('summary').click();
  await page.locator('#monitor-toggle').click();
  await page.getByText('Monitoring is on. Automatic checks follow the saved schedule.').waitFor();
  assert.equal((await status()).monitoring_enabled, true);
  await page.locator('#monitor-toggle').click();
  await page.getByText('Monitoring is paused. Setup and app selection do not change this setting.').waitFor();
  assert.equal((await status()).monitoring_enabled, false);
  const monitoringBeforeWizardResume = monitoringRequests;
  await page.getByRole('button', { name: 'Add another TV' }).click();
  await page.locator('#wizard-name').fill('Smoke active');
  await page.locator('input[name="pairing-mode"][value="paired"]').check();
  await page.locator('#wizard-next').click();
  await page.locator('#wizard-endpoint').fill('10.0.0.7:41000');
  await page.locator('#wizard-next').click();
  await wizardStep(3).waitFor({ state: 'visible' });
  await page.locator('#wizard-app-skip').click();
  await wizardStep(4).waitFor({ state: 'visible' });
  await page.getByRole('button', { name: 'Resume monitoring' }).click();
  await page.getByText('Monitoring is currently on. Finish keeps it on.').waitFor();
  assert.equal(monitoringRequests, monitoringBeforeWizardResume + 1, 'wizard resume sends exactly one monitoring update');
  assert.equal((await status()).monitoring_enabled, true);
  await page.locator('#wizard-next').click();
  assert.equal((await status()).monitoring_enabled, true, 'Finish preserves the active monitoring setting');
  const [download] = await Promise.all([page.waitForEvent('download'),page.locator('#diagnostics').click()]);
  assert.match(download.suggestedFilename(), /diagnostics\.json$/);

  // A delayed inventory response must not write into a new signed-out session.
  await page.getByRole('button', { name: 'Add another TV' }).click();
  await page.locator('#wizard-name').fill('Smoke stale');
  await page.locator('#wizard-next').click();
  await page.locator('#wizard-endpoint').fill('10.0.0.9:41267');
  await page.locator('#wizard-next').click();
  await page.locator('#wizard-pair-endpoint').fill('10.0.0.9:37123');
  await page.locator('#wizard-code').fill('222222');
  await page.locator('#wizard-next').click();
  await page.getByText('Loading installed apps…').waitFor();
  await page.locator('#logout').click();
  await page.getByRole('heading', { name: 'Sign in' }).waitFor();
  await page.waitForTimeout(950);
  assert.equal(await page.locator('#auth-title').textContent(), 'Sign in', 'stale inventory must not replace the signed-out view');
  await page.locator('#password').fill(password);
  await page.locator('#auth-submit').click();
  await page.locator('.device').filter({ hasText: 'Smoke stale' }).waitFor();
  assert.equal(await page.locator('#wizard').isVisible(), false);

  await page.setViewportSize({ width: 1280, height: 900 });
  await page.locator('#appearance').selectOption('light');
  await capture('dashboard-light-desktop.png');
  await page.locator('#appearance').selectOption('night');
  await capture('dashboard-night-desktop.png');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.locator('#appearance').selectOption('light');
  await capture('dashboard-light-mobile.png');
  await page.locator('#appearance').selectOption('night');
  await capture('dashboard-night-mobile.png');
  await page.getByRole('button', { name: 'Add another TV' }).click();
  assert.equal(await wizardStep(0).isVisible(), true);
  assert.equal(await page.locator('#wizard-code').inputValue(), '');
  assert.equal(await page.locator('#devices-section').isVisible(), false);
  await page.setViewportSize({ width: 1280, height: 900 });
  await capture('wizard-night-desktop.png');
  const contrast = await page.evaluate(() => {
    const channels = color => color.match(/[\d.]+/g)?.slice(0, 3).map(Number);
    const luminance = color => {
      const values=channels(color).map(value=>{const c=value/255;return c<=0.04045?c/12.92:((c+0.055)/1.055)**2.4;});
      return values[0]*0.2126+values[1]*0.7152+values[2]*0.0722;
    };
    const ratio = (foreground,background) => {const a=luminance(foreground),b=luminance(background);return (Math.max(a,b)+0.05)/(Math.min(a,b)+0.05);};
    const background = element => {
      for(let node=element;node;node=node.parentElement){const value=getComputedStyle(node).backgroundColor;if(!value.includes('0, 0, 0, 0')&&!value.includes('rgba(0, 0, 0, 0)'))return value;}
      return getComputedStyle(document.body).backgroundColor;
    };
    const muted=document.querySelector('#wizard .muted');
    const placeholder=document.querySelector('#wizard-name');
    return {
      body:ratio(getComputedStyle(document.body).color,background(document.body)),
      muted:ratio(getComputedStyle(muted).color,background(muted)),
      placeholder:ratio(getComputedStyle(placeholder,'::placeholder').color,background(placeholder)),
    };
  });
  for (const [surface, value] of Object.entries(contrast)) assert.ok(value >= 4.5, `${surface} contrast must meet 4.5:1, got ${value.toFixed(2)}:1`);
  await page.locator('#appearance').selectOption('light');
  await page.setViewportSize({ width: 390, height: 844 });
  await capture('wizard-light-mobile.png');
  await page.locator('#wizard-cancel').click();
  await page.setViewportSize({ width: 360, height: 800 });
  const overflow = await page.evaluate(() => ({
    width: document.documentElement.scrollWidth,
    viewport: window.innerWidth,
    offenders: Array.from(document.querySelectorAll('body *')).map(element => ({tag:element.tagName,id:element.id,className:typeof element.className==='string'?element.className:'',right:Math.round(element.getBoundingClientRect().right),text:(element.textContent||'').trim().slice(0,70)})).filter(item=>item.right>window.innerWidth+1).slice(0,8),
  }));
  assert.equal(overflow.width>overflow.viewport, false, `mobile layout should not overflow horizontally: ${JSON.stringify(overflow)}`);

  const blockedStoragePage = await browser.newPage({ colorScheme: 'dark' });
  blockedStoragePage.on('pageerror', error => pageErrors.push(error.message));
  await blockedStoragePage.addInitScript(() => Object.defineProperty(window, 'localStorage', {
    configurable: true,
    get() { throw new Error('Storage is disabled.'); },
  }));
  await blockedStoragePage.goto(baseURL);
  assert.equal(await blockedStoragePage.locator('#appearance').inputValue(), 'system');
  await blockedStoragePage.locator('#appearance').selectOption('light');
  assert.equal(await blockedStoragePage.locator('html').evaluate(element => getComputedStyle(element).colorScheme), 'light');
  await blockedStoragePage.close();

  assert.deepEqual(pageErrors, [], 'browser must not report JavaScript errors');
  console.log('browser smoke passed: wizard pairing branches, endpoint selection, validation, code clearing, duplicate protection, partial-save retry, compile/monitoring defaults, TV/app management, settings/diagnostics, Appearance, mobile overflow; screenshots saved under ../../work');
} catch (error) {
  for (const filename of ['wizard-first-screen.png','wizard-name-step.png','wizard-connection-step.png','wizard-pair-step.png','wizard-app-step.png','wizard-review-step.png','wizard-night-desktop.png','dashboard-light-desktop.png','dashboard-night-desktop.png','dashboard-light-mobile.png','dashboard-night-mobile.png','wizard-light-mobile.png']) await rm(path.join(work, filename), { force: true });
  throw error;
} finally {
  await browser.close();
}
