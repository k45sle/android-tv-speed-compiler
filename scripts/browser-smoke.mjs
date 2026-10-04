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
page.setDefaultTimeout(10000);
page.on('pageerror', error => pageErrors.push(error.message));
const screenshot = path.resolve('../../work/dashboard-smoke.png');
try {
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
  await page.getByRole('heading', { name: 'Your TVs' }).waitFor();
  assert.equal(await page.locator('#appearance').inputValue(), 'night');
  await page.locator('#logout').click();
  await page.locator('#auth-title').waitFor();
  assert.equal(await page.locator('#appearance').inputValue(), 'night');
  assert.equal(await page.locator('#auth-title').textContent(), 'Sign in');
  await page.locator('#password').fill(password);
  await page.locator('#auth-submit').click();
  await page.getByRole('heading', { name: 'Your TVs' }).waitFor();
  await page.locator('#appearance').selectOption('light');
  assert.equal(await page.locator('html').evaluate(element => getComputedStyle(element).colorScheme), 'light');
  await page.locator('#appearance').selectOption('system');
  assert.equal(await page.locator('html').evaluate(element => getComputedStyle(element).colorScheme), 'dark');

  await page.getByRole('button', { name: 'Discover TLS services' }).click();
  await page.getByText('pairing: 10.0.0.5:37123').waitFor();
  await page.getByText('connect: 10.0.0.5:41267').waitFor();
  const pairForm = page.locator('#pair-form');
  await pairForm.locator('[name="name"]').fill('Smoke living room');
  await pairForm.locator('[name="pairing_endpoint"]').fill('10.0.0.5:37123');
  await pairForm.locator('[name="pairing_code"]').fill('123456');
  await pairForm.locator('[name="endpoint"]').fill('10.0.0.5:41267');
  await pairForm.locator('button[type="submit"]').click();
  await page.getByText('Smoke living room', { exact: true }).waitFor();
  let card = page.locator('.device').filter({ hasText: 'Smoke living room' });
  await card.getByRole('button', { name: 'Load fresh app inventory' }).click();
  await card.getByText('com.nuvio.tv', { exact: true }).waitFor();
  const appRows = card.locator('.app-row');
  assert.equal(await appRows.count(), 2);
  const mainRow = appRows.nth(0);
  const testRow = appRows.nth(1);
  assert.equal(await mainRow.locator('code.package').textContent(), 'com.nuvio.tv');
  assert.equal(await testRow.locator('code.package').textContent(), 'com.nuvio.tv.test');
  assert.equal(await mainRow.getByRole('checkbox').nth(1).isChecked(), false);
  assert.equal(await mainRow.getByRole('checkbox').nth(2).isChecked(), false);
  const testWatch = testRow.getByRole('checkbox').first();
  await testWatch.check();
  await testWatch.uncheck();
  const watch = mainRow.getByRole('checkbox', { name: 'Watch Nuvio' });
  await watch.check();
  await page.getByText(/Watching enabled\. Current version saved as its baseline/).waitFor();
  await mainRow.getByRole('button', { name: 'Queue compile' }).click();
  await page.getByText('Manual compile queued. The scheduler will report its current wait reason.').waitFor();

  await page.locator('#settings-form [name="poll_interval_seconds"]').fill('120');
  await page.locator('#settings-form [name="max_attempts"]').fill('4');
  await page.locator('#settings-form [name="window_start"]').fill('23:00');
  await page.locator('#settings-form [name="window_end"]').fill('06:00');
  await page.locator('#settings-form [name="timezone"]').fill('America/Chicago');
  await page.locator('#settings-form button[type="submit"]').click();
  await page.getByText('Global scheduling settings saved.').waitFor();
  await page.locator('#monitor-toggle').click();
  await page.getByRole('heading', { name: 'Monitoring active' }).waitFor();
  await page.locator('#monitor-toggle').click();
  await page.getByRole('heading', { name: 'Monitoring paused' }).waitFor();
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.locator('#diagnostics').click(),
  ]);
  assert.match(download.suggestedFilename(), /diagnostics\.json$/);

  await page.locator('summary').filter({ hasText: 'Already paired? Add a TV' }).click();
  const add = page.locator('#add-form');
  await add.locator('[name="name"]').fill('Smoke bedroom');
  await add.locator('[name="endpoint"]').fill('10.0.0.6:41000');
  await add.locator('button[type="submit"]').click();
  await page.getByText('Smoke bedroom', { exact: true }).waitFor();
  card = page.locator('.device').filter({ hasText: 'Smoke living room' });
  await card.getByRole('button', { name: 'Save name and reconnect endpoint' }).click();
  await page.getByText('TV endpoint verified against its pinned identity and saved.').waitFor();
  await page.setViewportSize({ width: 1280, height: 900 });
  await mkdir(path.dirname(screenshot), { recursive: true });
  await page.screenshot({ path: screenshot, fullPage: true });
  await page.setViewportSize({ width: 360, height: 800 });
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
  assert.equal(overflow, false, 'mobile layout should not overflow horizontally');

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
  console.log(`browser smoke passed: appearance system/light/night and persistence/storage fallback, setup/login/logout, TLS discovery/pair/add, inventory/watch/manual default, settings, pause, diagnostics, mobile layout; screenshot ${screenshot}`);
} catch (error) {
  await rm(screenshot, { force: true });
  throw error;
} finally {
  await browser.close();
}
