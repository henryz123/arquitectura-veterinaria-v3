import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { chromium } from 'playwright-core';

const local = Object.fromEntries(readFileSync('../.env.local', 'utf8').split(/\r?\n/).flatMap((line) => {
  const separator = line.indexOf('=');
  return separator > 0 && !line.startsWith('#') ? [[line.slice(0, separator), line.slice(separator + 1)]] : [];
}));
for (const name of ['AV_DEMO_AURORA_PASSWORD', 'AV_DEMO_BOSQUE_PASSWORD', 'AV_DEMO_CASHIER_AURORA_EMAIL', 'AV_DEMO_CASHIER_AURORA_PASSWORD']) {
  if (!local[name]) throw new Error(`Falta la variable local ${name}.`);
}

const baseUrl = (process.env.AV_E2E_BASE_URL ?? 'http://127.0.0.1:4200').replace(/\/$/, '');
const chromePath = process.env.AV_E2E_CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const reportPath = '/api/v1/inventory/reports/commercial?from=2026-01-01&to=2026-12-31';
const browser = await chromium.launch({ executablePath: chromePath, headless: true });

try {
  const auroraContext = await browser.newContext();
  const aurora = await auroraContext.newPage();
  const consoleErrors = [];
  aurora.on('console', (message) => { if (message.type() === 'error') consoleErrors.push(message.text()); });
  await loginStaff(aurora, 'veterinaria-aurora', 'administracion@demo.invalid', local.AV_DEMO_AURORA_PASSWORD);
  const reportResponse = aurora.waitForResponse((response) => response.url().includes('/api/v1/inventory/reports/commercial'));
  await aurora.getByRole('link', { name: 'Informe comercial' }).click();
  assert.equal((await reportResponse).status(), 200, 'ADMIN Aurora debe obtener el informe comercial.');
  await aurora.getByRole('heading', { name: 'Ventas, devoluciones y existencias' }).waitFor();
  await aurora.getByLabel('Desde').fill('2026-01-01');
  await aurora.getByLabel('Hasta').fill('2026-12-31');
  const filtered = aurora.waitForResponse((response) => response.url().includes(reportPath));
  await aurora.getByRole('button', { name: 'Consultar informe' }).click();
  const commercial = await responseBody(await filtered);
  assert.equal(Number(await aurora.locator('.metric-card').nth(0).locator('strong').innerText()), commercial.posSalesCount,
    'La métrica visible debe coincidir con las ventas POS reales de la API.');
  assert.equal(Number(await aurora.locator('.metric-card').nth(1).locator('strong').innerText()), commercial.cancellationsCount,
    'Las anulaciones visibles deben coincidir con la API y permanecer separadas.');

  await aurora.getByLabel('Desde').fill('1900-01-01');
  await aurora.getByLabel('Hasta').fill('1900-01-01');
  const emptyPeriod = aurora.waitForResponse((response) => response.url().includes('from=1900-01-01&to=1900-01-01'));
  await aurora.getByRole('button', { name: 'Consultar informe' }).click();
  assert.equal((await responseBody(await emptyPeriod)).posSalesCount, 0, 'El período vacío no debe inventar ventas.');
  await aurora.getByText('No hay ventas POS no anuladas en el período elegido.').waitFor();
  await aurora.getByText('No hay movimientos de inventario en el período elegido.').waitFor();

  await aurora.getByLabel('Desde').fill('2026-12-31');
  await aurora.getByLabel('Hasta').fill('2026-01-01');
  await aurora.getByRole('button', { name: 'Consultar informe' }).click();
  await aurora.getByText('La fecha final no puede ser anterior a la fecha inicial.').waitFor();
  await aurora.reload({ waitUntil: 'networkidle' });
  await aurora.getByRole('heading', { name: 'Ventas, devoluciones y existencias' }).waitFor();
  assert.equal(consoleErrors.length, 0, `No deben existir errores de consola: ${consoleErrors.join(' | ')}`);

  const cashierContext = await browser.newContext();
  const cashier = await cashierContext.newPage();
  await loginStaff(cashier, 'veterinaria-aurora', local.AV_DEMO_CASHIER_AURORA_EMAIL, local.AV_DEMO_CASHIER_AURORA_PASSWORD);
  await cashier.goto(`${baseUrl}/reports/commercial`, { waitUntil: 'networkidle' });
  await cashier.getByText('El informe comercial completo requiere ADMIN_VETERINARIA vigente.').waitFor();
  assert.equal((await api(cashier, reportPath)).status, 403, 'CAJERO no debe consultar el informe administrativo.');

  const bosqueContext = await browser.newContext();
  const bosque = await bosqueContext.newPage();
  await loginStaff(bosque, 'veterinaria-bosque', 'administracion@demo.invalid', local.AV_DEMO_BOSQUE_PASSWORD);
  const bosqueReport = await api(bosque, reportPath);
  assert.equal(bosqueReport.status, 200, 'ADMIN Bosque puede consultar solo su propio informe.');
  assert.equal(bosqueReport.body.data.topProducts.some((product) => product.sku === 'B04-E2E-STOCK-001'), false,
    'Bosque no debe recibir el producto ni las ventas de Aurora.');
  process.stdout.write('B04_COMMERCIAL_REPORT_BROWSER_E2E=PASS\n');
} finally {
  await browser.close();
}

async function loginStaff(page, tenantSlug, email, password) {
  await page.goto(`${baseUrl}/login/${tenantSlug}`, { waitUntil: 'networkidle' });
  await page.getByLabel('Correo institucional').fill(email);
  await page.getByRole('textbox', { name: /Contrase/ }).fill(password);
  await page.getByRole('button', { name: 'Iniciar sesión' }).click();
  await page.waitForURL(`${baseUrl}/workspace`);
}

async function api(page, path) {
  return page.evaluate(async (requestPath) => {
    const response = await fetch(requestPath);
    const text = await response.text();
    return { status: response.status, body: text ? JSON.parse(text) : null };
  }, path);
}

async function responseBody(response) {
  const payload = await response.json();
  return payload.data;
}
