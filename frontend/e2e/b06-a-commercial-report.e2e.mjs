import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { chromium } from 'playwright-core';

for (const key of ['AV_DEMO_AURORA_PASSWORD', 'AV_E2E_CHROME_PATH']) {
  if (!process.env[key]) throw new Error(`Missing local B06-A variable: ${key}`);
}

const baseUrl = (process.env.AV_E2E_BASE_URL ?? 'http://127.0.0.1:4200').replace(/\/$/, '');
const browser = await chromium.launch({ executablePath: process.env.AV_E2E_CHROME_PATH, headless: true });

try {
  await mkdir('artifacts/b06-a', { recursive: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 920 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));
  page.setDefaultTimeout(15000);

  await page.goto(`${baseUrl}/login/veterinaria-aurora`, { waitUntil: 'domcontentloaded' });
  await page.getByLabel('Correo institucional').fill(process.env.AV_E2E_AURORA_EMAIL ?? 'administracion@demo.invalid');
  await page.getByLabel(/Contrase/).fill(process.env.AV_DEMO_AURORA_PASSWORD);
  await page.getByRole('button', { name: 'Iniciar sesión' }).click();
  await page.waitForURL(`${baseUrl}/workspace`);

  const responsePromise = page.waitForResponse((response) => response.request().method() === 'GET'
    && new URL(response.url()).pathname === '/api/v1/inventory/reports/commercial');
  await page.getByRole('link', { name: 'Informe comercial' }).click();
  await page.waitForURL(`${baseUrl}/reports/commercial`);
  const response = await responsePromise;
  assert.equal(response.status(), 200, 'the protected report endpoint must respond successfully for tenant administration');
  const report = (await response.json()).data;
  for (const field of [
    'onlineConfirmedOrdersCount',
    'onlineConfirmedOrdersGrossAmountPen',
    'onlineValidatedPaymentsCount',
    'onlineValidatedPaymentsAmountPen',
  ]) {
    assert.equal(typeof report[field], 'number', `${field} must be a numeric field in the real response`);
  }

  await page.getByRole('heading', { name: 'Ventas, pedidos y existencias' }).waitFor();
  await page.getByText('Pedidos web confirmados').waitFor();
  await page.getByText('Pagos web validados').waitFor();
  assert.equal(await page.locator('.metric-card').count(), 6, 'the report must render separate POS, online, payment and stock facts');
  await page.screenshot({ path: 'artifacts/b06-a/commercial-report-desktop.png', fullPage: true });

  await page.setViewportSize({ width: 768, height: 920 });
  await page.screenshot({ path: 'artifacts/b06-a/commercial-report-tablet.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: 'artifacts/b06-a/commercial-report-mobile.png', fullPage: true });
  assert.deepEqual(pageErrors, [], 'the report route must not produce browser page errors');

  await context.close();
  process.stdout.write('B06_A_COMMERCIAL_REPORT_BROWSER_E2E=PASS\n');
} finally {
  await browser.close();
}
