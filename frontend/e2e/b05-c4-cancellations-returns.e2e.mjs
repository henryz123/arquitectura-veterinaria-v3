import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';

for (const key of ['AV_DEMO_AURORA_PASSWORD', 'AV_DEMO_CLIENT_AURORA_EMAIL', 'AV_DEMO_CLIENT_AURORA_PASSWORD', 'AV_DEMO_CLIENT_BOSQUE_EMAIL', 'AV_DEMO_CLIENT_BOSQUE_PASSWORD', 'AV_E2E_CHROME_PATH']) {
  if (!process.env[key]) throw new Error(`Missing local E2E variable: ${key}`);
}

const baseUrl = (process.env.AV_E2E_BASE_URL ?? 'http://127.0.0.1:4200').replace(/\/$/, '');
const artifacts = join(dirname(fileURLToPath(import.meta.url)), '..', 'artifacts', 'b05-c4');
const suffix = `${Date.now()}-${Math.random().toString(16).slice(2, 7)}`;
const browser = await chromium.launch({ executablePath: process.env.AV_E2E_CHROME_PATH, headless: true });

try {
  await mkdir(artifacts, { recursive: true });
  const staff = await browser.newContext({ viewport: { width: 1440, height: 920 } });
  const staffPage = await staff.newPage();
  staffPage.setDefaultTimeout(10000);
  await loginStaff(staffPage);
  const product = (await apiPost(staffPage, '/api/v1/catalog/products', {
    categoryId: null, sku: `B05-C4-${suffix}`, title: `Producto sintético B05-C4 ${suffix}`,
    description: null, salePricePen: 19, status: 'ACTIVO_PUBLICADO', inventoryManaged: true, imageUrl: null,
  })).body.data;
  await apiPost(staffPage, `/api/v1/inventory/stock/products/${product.id}/adjustments`, {
    quantityDelta: 5, reason: 'Saldo sintético B05-C4', operationId: crypto.randomUUID(),
  });
  const initialStock = await stock(staffPage, product.id);
  assert.equal(initialStock, 5, 'the C4 fixture must have a controlled stock balance');

  const client = await browser.newContext({ viewport: { width: 1280, height: 860 } });
  const clientPage = await client.newPage();
  clientPage.setDefaultTimeout(10000);
  await loginClient(clientPage, 'veterinaria-aurora', process.env.AV_DEMO_CLIENT_AURORA_EMAIL, process.env.AV_DEMO_CLIENT_AURORA_PASSWORD);

  const pending = (await apiPost(clientPage, '/api/v1/online-orders', orderDraft(product))).body.data;
  await clientPage.goto(`${baseUrl}/client-space`, { waitUntil: 'domcontentloaded' });
  await clientPage.getByRole('button', { name: 'Mis pedidos' }).click();
  const pendingCard = await clientOrderCard(clientPage, pending.orderNumber, 'Pendiente de confirmación');
  const pendingCancelled = clientPage.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith(`/online-orders/${pending.id}/cancellation`));
  const clientCancelPrompt = acceptNextPrompt(clientPage, 'El pedido pendiente se cancela en esta demostración.');
  await pendingCard.getByRole('button', { name: 'Cancelar pedido' }).click();
  await clientCancelPrompt;
  assert.equal((await pendingCancelled).status(), 200, 'CLIENT must cancel its own pending order');
  assert.equal(await stock(staffPage, product.id), initialStock, 'pending cancellation must not move stock');

  const preDispatch = (await apiPost(clientPage, '/api/v1/online-orders', orderDraft(product))).body.data;
  assert.equal((await apiPost(staffPage, `/api/v1/online-orders/management/${preDispatch.id}/confirmation`, {})).body.data.status, 'CONFIRMADO');
  assert.equal(await stock(staffPage, product.id), initialStock - 1, 'confirmation consumes the fixture unit once');
  await openManagementOrder(staffPage, preDispatch.orderNumber);
  const staffCancelled = staffPage.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith(`/online-orders/management/${preDispatch.id}/cancellation`));
  const staffCancelPrompt = acceptNextPrompt(staffPage, 'Producto aún disponible para restitución.');
  await staffPage.getByRole('button', { name: 'Cancelar pedido' }).click();
  await staffCancelPrompt;
  assert.equal((await staffCancelled).status(), 200, 'STAFF must cancel a confirmed pre-dispatch order');
  assert.equal(await stock(staffPage, product.id), initialStock, 'pre-dispatch cancellation must restore stock exactly once');

  const delivered = (await apiPost(clientPage, '/api/v1/online-orders', orderDraft(product))).body.data;
  assert.equal((await apiPost(staffPage, `/api/v1/online-orders/management/${delivered.id}/confirmation`, {})).body.data.status, 'CONFIRMADO');
  await apiPost(staffPage, `/api/v1/online-orders/management/${delivered.id}/preparation`, { operationId: crypto.randomUUID() });
  await apiPost(staffPage, `/api/v1/online-orders/management/${delivered.id}/ready-for-pickup`, { operationId: crypto.randomUUID() });
  await apiPost(staffPage, `/api/v1/online-orders/management/${delivered.id}/delivery`, { operationId: crypto.randomUUID(), receiver: 'Cliente Aurora sintético', proofNote: 'Constancia C4.' });
  assert.equal(await stock(staffPage, product.id), initialStock - 1, 'delivery must not create another stock movement');

  await clientPage.goto(`${baseUrl}/client-space`, { waitUntil: 'domcontentloaded' });
  await clientPage.getByRole('button', { name: 'Mis pedidos' }).click();
  const deliveredCard = await clientOrderCard(clientPage, delivered.orderNumber, 'Entregado');
  await deliveredCard.getByRole('button', { name: 'Solicitar devolución parcial' }).click();
  await clientPage.getByRole('heading', { name: `Solicitar devolución del pedido #${delivered.orderNumber}` }).waitFor();
  await clientPage.locator('textarea[name="returnReason"]').fill('Una unidad sintética se devuelve para inspección física.');
  await clientPage.locator('.online-return-form input[type="number"]').fill('1');
  const returnRequested = clientPage.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith(`/online-orders/${delivered.id}/returns`));
  await clientPage.getByRole('button', { name: 'Enviar solicitud' }).click();
  assert.equal((await returnRequested).status(), 201, 'CLIENT must submit its own partial return request');
  assert.equal(await stock(staffPage, product.id), initialStock - 1, 'requesting a return must not restock before inspection');
  await clientPage.screenshot({ path: join(artifacts, 'client-return-requested.png'), fullPage: true });

  await openManagementOrder(staffPage, delivered.orderNumber);
  const returnAuthorized = staffPage.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.includes('/online-orders/management/returns/') && new URL(response.url()).pathname.endsWith('/decision'));
  await staffPage.getByRole('button', { name: 'Autorizar' }).click();
  assert.equal((await returnAuthorized).status(), 200, 'STAFF must authorize the physical return');
  const returnReceived = staffPage.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith('/receipt'));
  const receiptPrompt = acceptNextPrompt(staffPage, '1');
  await staffPage.getByRole('button', { name: 'Registrar recepción' }).click();
  await receiptPrompt;
  assert.equal((await returnReceived).status(), 200, 'STAFF must persist physical receipt');
  assert.equal(await stock(staffPage, product.id), initialStock - 1, 'physical receipt alone must not restock');
  const returnInspected = staffPage.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith('/inspection'));
  const inspectionPrompt = acceptNextPrompt(staffPage, '1');
  await staffPage.getByRole('button', { name: 'Inspeccionar unidades' }).click();
  await inspectionPrompt;
  assert.equal((await returnInspected).status(), 200, 'STAFF must inspect returned units');
  assert.equal(await stock(staffPage, product.id), initialStock, 'only an apt physical inspection restores the returned unit once');
  const returnClosed = staffPage.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith('/closure'));
  await staffPage.getByRole('button', { name: 'Cerrar devolución' }).click();
  assert.equal((await returnClosed).status(), 200, 'STAFF must close the inspected return');
  await staffPage.screenshot({ path: join(artifacts, 'staff-return-closed.png'), fullPage: true });

  await clientPage.goto(`${baseUrl}/client-space`, { waitUntil: 'domcontentloaded' });
  await clientPage.getByRole('button', { name: 'Mis pedidos' }).click();
  const returnedCard = await clientOrderCard(clientPage, delivered.orderNumber, 'Entregado');
  assert.match(await returnedCard.innerText(), /Devolución · Cerrada/);
  await clientPage.reload({ waitUntil: 'domcontentloaded' });
  await clientPage.getByRole('button', { name: 'Mis pedidos' }).click();
  assert.match(await clientOrderCard(clientPage, delivered.orderNumber, 'Entregado').then((card) => card.innerText()), /aptas 1/);

  const bosque = await browser.newContext();
  const bosquePage = await bosque.newPage();
  await loginClient(bosquePage, 'veterinaria-bosque', process.env.AV_DEMO_CLIENT_BOSQUE_EMAIL, process.env.AV_DEMO_CLIENT_BOSQUE_PASSWORD);
  assert.equal((await browserFetch(bosquePage, `/api/v1/online-orders/${delivered.id}`)).status, 404, 'Bosque must not see an Aurora order');
  await bosque.close(); await client.close(); await staff.close();
  process.stdout.write('B05_C4_CANCELLATION_RETURN_BROWSER_E2E=PASS\n');
} finally {
  await browser.close();
}

function orderDraft(product) { return { operationId: crypto.randomUUID(), deliveryMode: 'RECOJO', coordinationPhone: '999 041 041', deliveryAddress: null, expectedDeliveryFeePen: null, lines: [{ productId: product.id, quantity: 1, expectedUnitPricePen: product.salePricePen }] }; }
async function openManagementOrder(page, orderNumber) { await page.goto(`${baseUrl}/online-orders/management`, { waitUntil: 'domcontentloaded' }); const row = page.locator('tbody tr').filter({ has: page.getByText(`#${orderNumber}`, { exact: true }) }); await row.waitFor(); await row.getByRole('button', { name: 'Ver detalle' }).click(); await page.getByRole('heading', { name: 'Detalle y condiciones' }).waitFor(); }
async function clientOrderCard(page, orderNumber, state) { const card = page.locator('article').filter({ has: page.getByText(`Pedido #${orderNumber} · ${state}`, { exact: true }) }); await card.waitFor(); return card; }
function acceptNextPrompt(page, value) { return page.waitForEvent('dialog').then((dialog) => dialog.accept(value)); }
async function loginStaff(page) { await page.goto(`${baseUrl}/login/veterinaria-aurora`, { waitUntil: 'domcontentloaded' }); await page.getByLabel('Correo institucional').fill(process.env.AV_E2E_AURORA_EMAIL ?? 'administracion@demo.invalid'); await page.getByLabel(/Contrase/).fill(process.env.AV_DEMO_AURORA_PASSWORD); await page.getByRole('button', { name: 'Iniciar sesión' }).click(); await page.waitForURL(`${baseUrl}/workspace`); }
async function loginClient(page, tenant, email, password) { await page.goto(`${baseUrl}/client/login/${tenant}`, { waitUntil: 'domcontentloaded' }); await page.getByLabel(/Correo/).fill(email); await page.locator('#password').fill(password); await page.getByRole('button', { name: 'Iniciar sesión' }).click(); await page.waitForURL(`${baseUrl}/client-space`); }
async function browserFetch(page, path) { return page.evaluate(async (requestPath) => { const response = await fetch(requestPath); const text = await response.text(); return { status: response.status, body: text ? JSON.parse(text) : null }; }, path); }
async function apiPost(page, path, body, method = 'POST') { return page.evaluate(async ({ requestPath, payload, requestMethod }) => { const csrf = await fetch('/api/v1/auth/session-csrf-token', { method: 'POST', headers: { 'X-AV-CSRF-Bootstrap': '1' } }).then((response) => response.json()).then((result) => result.data.csrfToken); const response = await fetch(requestPath, { method: requestMethod, headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf }, body: JSON.stringify(payload) }); const text = await response.text(); return { status: response.status, body: text ? JSON.parse(text) : null }; }, { requestPath: path, payload: body, requestMethod: method }); }
async function stock(page, productId) { const rows = (await browserFetch(page, '/api/v1/inventory/stock')).body?.data ?? []; return rows.find((item) => item.productId === productId)?.availableQuantity ?? 0; }
