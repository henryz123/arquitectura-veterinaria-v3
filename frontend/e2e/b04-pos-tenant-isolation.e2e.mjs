import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { chromium } from 'playwright-core';

const local = Object.fromEntries(readFileSync('../.env.local', 'utf8').split(/\r?\n/).flatMap((line) => {
  const separator = line.indexOf('=');
  return separator > 0 && !line.startsWith('#') ? [[line.slice(0, separator), line.slice(separator + 1)]] : [];
}));
for (const name of ['AV_DEMO_CASHIER_AURORA_EMAIL', 'AV_DEMO_CASHIER_AURORA_PASSWORD', 'AV_DEMO_BOSQUE_PASSWORD']) {
  if (!local[name]) throw new Error(`Falta la variable local ${name}.`);
}

const baseUrl = (process.env.AV_E2E_BASE_URL ?? 'http://127.0.0.1:4200').replace(/\/$/, '');
const chromePath = process.env.AV_E2E_CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const controlledSku = 'B04-E2E-STOCK-001';
const browser = await chromium.launch({ executablePath: chromePath, headless: true });

try {
  const auroraContext = await browser.newContext();
  const aurora = await auroraContext.newPage();
  await loginStaff(aurora, 'veterinaria-aurora', local.AV_DEMO_CASHIER_AURORA_EMAIL, local.AV_DEMO_CASHIER_AURORA_PASSWORD);
  const auroraSales = await api(aurora, '/api/v1/pos/sales');
  assert.equal(auroraSales.status, 200, 'CAJERO Aurora debe poder consultar sus ventas.');
  const sale = auroraSales.body.data.find((candidate) => candidate.lines?.some((line) => line.productSkuSnapshot === controlledSku));
  assert.ok(sale, `Debe existir una venta Aurora del producto controlado ${controlledSku}.`);

  const bosqueContext = await browser.newContext();
  const bosque = await bosqueContext.newPage();
  await loginStaff(bosque, 'veterinaria-bosque', 'administracion@demo.invalid', local.AV_DEMO_BOSQUE_PASSWORD);
  const crossTenantSale = await api(bosque, `/api/v1/pos/sales/${sale.id}`);
  assert.ok([403, 404].includes(crossTenantSale.status), 'Bosque no debe consultar una venta perteneciente a Aurora.');
  const bosqueProducts = await api(bosque, '/api/v1/pos/products');
  assert.ok([403, 404].includes(bosqueProducts.status),
    'Una cuenta Bosque sin rol comercial no debe poder consultar el catálogo POS de Aurora.');
  process.stdout.write('B04_POS_TENANT_ISOLATION_BROWSER_E2E=PASS\n');
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
