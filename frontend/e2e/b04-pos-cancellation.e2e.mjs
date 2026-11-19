import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { chromium } from 'playwright-core';

const local = Object.fromEntries(readFileSync('../.env.local', 'utf8').split(/\r?\n/).flatMap((line) => {
  const separator = line.indexOf('=');
  return separator > 0 && !line.startsWith('#') ? [[line.slice(0, separator), line.slice(separator + 1)]] : [];
}));
for (const name of ['AV_DEMO_CASHIER_AURORA_EMAIL', 'AV_DEMO_CASHIER_AURORA_PASSWORD']) {
  if (!local[name]) throw new Error(`Falta la variable local ${name}.`);
}

const baseUrl = (process.env.AV_E2E_BASE_URL ?? 'http://127.0.0.1:4200').replace(/\/$/, '');
const chromePath = process.env.AV_E2E_CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const productSku = 'B04-E2E-STOCK-001';
const browser = await chromium.launch({ executablePath: chromePath, headless: true });

try {
  const page = await browser.newPage();
  await loginCashier(page);
  await page.goto(`${baseUrl}/pos`, { waitUntil: 'networkidle' });
  const balanceBefore = await availableQuantity(page);
  assert.ok(balanceBefore >= 1, 'El producto sintético requiere una unidad para comprobar la anulación.');

  const sale = await createConfirmedSale(page);
  assert.equal(await availableQuantity(page), balanceBefore - 1, 'La venta confirmada debe descontar una unidad.');
  const row = page.locator(`[data-pos-sale-id="${sale.id}"]`);
  await row.getByRole('button', { name: 'Corregir' }).click();
  await page.locator('#correction-reason').fill('Anulación sintética B04 sin salida física');
  const cancelled = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith('/cancellations'));
  await page.getByRole('button', { name: 'Anular venta' }).click();
  assert.equal((await cancelled).status(), 200, 'La anulación de una venta no despachada debe persistir.');
  await page.getByText('La anulación quedó auditada y el stock se recompuso porque no hubo salida física.').waitFor();
  assert.equal(await availableQuantity(page), balanceBefore, 'La anulación sin salida física debe compensar exactamente una unidad.');

  await page.reload({ waitUntil: 'networkidle' });
  const reloaded = page.locator(`[data-pos-sale-id="${sale.id}"]`);
  assert.match(await reloaded.innerText(), /ANULADA/, 'La venta anulada debe conservar su estado después de recargar.');
  assert.equal(await reloaded.getByRole('button', { name: 'Corregir' }).isDisabled(), true,
    'Una venta anulada no debe permitir una segunda corrección desde la interfaz.');
  process.stdout.write('B04_POS_CANCELLATION_BROWSER_E2E=PASS\n');
} finally {
  await browser.close();
}

async function loginCashier(page) {
  await page.goto(`${baseUrl}/login/veterinaria-aurora`, { waitUntil: 'networkidle' });
  await page.getByLabel('Correo institucional').fill(local.AV_DEMO_CASHIER_AURORA_EMAIL);
  await page.getByRole('textbox', { name: /Contrase/ }).fill(local.AV_DEMO_CASHIER_AURORA_PASSWORD);
  await page.getByRole('button', { name: 'Iniciar sesión' }).click();
  await page.waitForURL(`${baseUrl}/workspace`);
}

async function availableQuantity(page) {
  const response = await page.evaluate(async () => {
    const request = await fetch('/api/v1/pos/products');
    return { status: request.status, body: await request.json() };
  });
  assert.equal(response.status, 200, 'El catálogo POS debe responder para la sesión CAJERO.');
  const product = response.body.data.find((item) => item.sku === productSku);
  assert.ok(product, `Debe existir el producto controlado ${productSku}.`);
  return product.availableQuantity;
}

async function createConfirmedSale(page) {
  const product = page.locator('.product-row', { hasText: productSku });
  await product.getByRole('button', { name: 'Agregar' }).click();
  const confirmed = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.includes('/confirmations'));
  await page.getByRole('button', { name: 'Confirmar venta' }).click();
  const response = await confirmed;
  assert.equal(response.status(), 200, 'La venta POS debe confirmarse con pago manual válido.');
  const body = await response.json();
  await page.getByText('La venta fue confirmada una sola vez y el comprobante interno ya está disponible.').waitFor();
  return body.data;
}
