import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { chromium } from 'playwright-core';

const required = ['AV_DEMO_CASHIER_AURORA_EMAIL', 'AV_DEMO_CASHIER_AURORA_PASSWORD'];
const local = Object.fromEntries(readFileSync('../.env.local', 'utf8').split(/\r?\n/).flatMap((line) => {
  const separator = line.indexOf('=');
  return separator > 0 && !line.startsWith('#') ? [[line.slice(0, separator), line.slice(separator + 1)]] : [];
}));
for (const name of required) {
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
  await page.getByRole('heading', { name: 'Caja y correcciones comerciales' }).waitFor();

  const beforeReturn = await availableQuantity(page);
  assert.ok(beforeReturn >= 1, 'El producto sintético requiere una unidad para comprobar la devolución.');
  const sale = await createConfirmedSale(page);
  const afterSale = beforeReturn - 1;
  assert.equal(await availableQuantity(page), afterSale, 'La venta confirmada debe descontar una unidad exactamente una vez.');
  const completedSale = page.locator(`[data-pos-sale-id="${sale.id}"]`);
  await completedSale.getByRole('button', { name: 'Corregir' }).click();
  await page.locator('#correction-reason').fill('Devolución física sintética B04 apta');
  await page.locator('.return-line input[type=number]').first().fill('1');

  const requested = page.waitForResponse((response) => response.request().method() === 'POST'
    && /\/api\/v1\/pos\/sales\/[^/]+\/returns$/.test(new URL(response.url()).pathname));
  await page.getByRole('button', { name: 'Solicitar devolución' }).click();
  assert.equal((await requested).status(), 201, 'La devolución debe quedar solicitada una sola vez.');
  await page.getByText('La devolución quedó solicitada; el stock no cambia hasta la recepción e inspección apta.').waitFor();
  assert.equal(await availableQuantity(page), afterSale, 'Una devolución solicitada no debe restituir stock.');

  const received = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith('/receipts'));
  await page.locator('.workflow-lines input[type=number]').first().fill('1');
  await page.getByRole('button', { name: 'Registrar recepción' }).click();
  assert.equal((await received).status(), 200, 'La recepción física debe persistir.');
  assert.equal(await availableQuantity(page), afterSale, 'La recepción física no debe restituir stock antes de inspección.');

  const inspected = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith('/inspections'));
  await page.locator('.workflow-lines input[type=number]').first().fill('1');
  await page.getByRole('button', { name: 'Inspeccionar y reponer aptas' }).click();
  assert.equal((await inspected).status(), 200, 'La inspección apta debe persistir.');
  await page.getByText('La inspección quedó auditada; solo las unidades aptas se restituyeron una vez al stock.').waitFor();
  assert.equal(await availableQuantity(page), beforeReturn, 'La inspección apta debe restituir exactamente una unidad.');

  const closed = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith('/closures'));
  await page.getByRole('button', { name: 'Cerrar devolución' }).click();
  assert.equal((await closed).status(), 200, 'El cierre de devolución debe persistir.');
  await page.reload({ waitUntil: 'networkidle' });
  assert.match(await page.locator(`[data-pos-sale-id="${sale.id}"]`).innerText(), /DEVUELTA/,
    'La venta devuelta debe conservar su estado después de recargar.');
  assert.equal(await page.getByRole('button', { name: 'Inspeccionar y reponer aptas' }).count(), 0,
    'Una devolución cerrada no puede inspeccionarse dos veces desde la interfaz.');
  process.stdout.write('B04_POS_RETURN_BROWSER_E2E=PASS\n');
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
