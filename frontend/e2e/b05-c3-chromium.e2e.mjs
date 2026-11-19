import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';

for (const key of ['AV_DEMO_AURORA_PASSWORD', 'AV_DEMO_CLIENT_AURORA_EMAIL', 'AV_DEMO_CLIENT_AURORA_PASSWORD', 'AV_DEMO_CLIENT_BOSQUE_EMAIL', 'AV_DEMO_CLIENT_BOSQUE_PASSWORD', 'AV_E2E_CHROME_PATH']) if (!process.env[key]) throw new Error(`Missing local E2E variable: ${key}`);
const baseUrl = (process.env.AV_E2E_BASE_URL ?? 'http://127.0.0.1:4200').replace(/\/$/, '');
const artifacts = join(dirname(fileURLToPath(import.meta.url)), '..', 'artifacts', 'b05-c3');
const suffix = `${Date.now()}-${Math.random().toString(16).slice(2, 7)}`;
const pickupOnly = process.env.AV_E2E_B05_C3_MODE === 'pickup';
const browser = await chromium.launch({ executablePath: process.env.AV_E2E_CHROME_PATH, headless: true });

try {
  await mkdir(artifacts, { recursive: true });
  const staff = await browser.newContext({ viewport: { width: 1440, height: 920 } });
  const staffPage = await staff.newPage();
  staffPage.setDefaultTimeout(5000);
  await loginStaff(staffPage);
  await apiPost(staffPage, '/api/v1/online-orders/delivery-configuration', { pickupLocation: 'Sede Aurora sintética', deliveryEnabled: true, fixedDeliveryFeePen: 9.5, coverageDescription: 'Zona sintética Aurora' }, 'PUT');
  const product = (await apiPost(staffPage, '/api/v1/catalog/products', { categoryId: null, sku: `B05-C3-${suffix}`, title: `Producto sintético B05-C3 ${suffix}`, description: null, salePricePen: 17, status: 'ACTIVO_PUBLICADO', inventoryManaged: true, imageUrl: null })).body.data;
  await apiPost(staffPage, `/api/v1/inventory/stock/products/${product.id}/adjustments`, { quantityDelta: 2, reason: 'Saldo sintético B05-C3', operationId: crypto.randomUUID() });
  const before = await stock(staffPage, product.id);

  const client = await browser.newContext({ viewport: { width: 1280, height: 860 } });
  const clientPage = await client.newPage();
  clientPage.setDefaultTimeout(5000);
  await loginClient(clientPage, 'veterinaria-aurora', process.env.AV_DEMO_CLIENT_AURORA_EMAIL, process.env.AV_DEMO_CLIENT_AURORA_PASSWORD);
  const pickupResponse = await apiPost(clientPage, '/api/v1/online-orders', orderDraft(product, 'RECOJO'));
  assert.equal(pickupResponse.status, 201, 'the pickup order must be created before STAFF can manage it');
  const pickup = pickupResponse.body.data;
  const deliveryResponse = await apiPost(clientPage, '/api/v1/online-orders', orderDraft(product, 'DELIVERY'));
  assert.equal(deliveryResponse.status, 201, 'the delivery order must be created before STAFF can manage it');
  const delivery = deliveryResponse.body.data;
  assert.equal(await stock(staffPage, product.id), before, 'pending orders must not change stock');

  await openAndConfirm(staffPage, pickup);
  assert.equal(await stock(staffPage, product.id), before - 1, 'pickup confirmation must consume one unit');
  await staffPage.getByRole('button', { name: 'Registrar pago' }).click();
  await staffPage.getByRole('heading', { name: 'Registrar pago manual' }).waitFor();
  await staffPage.getByLabel('Referencia o constancia').fill('Constancia sintética C3');
  await clickExpectPost(staffPage, 'Registrar pago manual', `/online-orders/management/${pickup.id}/payments`, 201);
  await clickExpectPost(staffPage, 'Validar', `/online-orders/management/${pickup.id}/payments/`, 200, '/decision');
  await clickExpectPost(staffPage, 'Iniciar preparación', `/online-orders/management/${pickup.id}/preparation`, 200);
  await clickExpectPost(staffPage, 'Marcar listo para recojo', `/online-orders/management/${pickup.id}/ready-for-pickup`, 200);
  await staffPage.getByRole('button', { name: 'Registrar entrega' }).click();
  await staffPage.getByLabel('Recibido por').fill('Cliente sintético Aurora');
  await clickExpectPost(staffPage, 'Confirmar entrega', `/online-orders/management/${pickup.id}/delivery`, 200);
  assert.equal(await stock(staffPage, product.id), before - 1, 'payment and pickup handover must not change stock');
  await staffPage.screenshot({ path: join(artifacts, 'staff-pickup-entregado.png'), fullPage: true });
  if (pickupOnly) {
    await clientPage.goto(`${baseUrl}/client-space`, { waitUntil: 'domcontentloaded' });
    await clientPage.getByRole('button', { name: 'Mis pedidos' }).click();
    const pickupCard = await clientOrderCard(clientPage, pickup.orderNumber);
    assert.match(await pickupCard.innerText(), /Pago:\s*Pago validado/);
    await clientPage.screenshot({ path: join(artifacts, 'client-pickup-entregado.png'), fullPage: true });
    await client.close(); await staff.close(); await browser.close();
    process.stdout.write('B05_C3_PICKUP_PAYMENT_BROWSER_E2E=PASS\n');
    process.exit(0);
  }

  await openAndConfirm(staffPage, delivery);
  assert.equal(await stock(staffPage, product.id), before - 2, 'delivery confirmation must use the same balance once');
  await clickExpectPost(staffPage, 'Iniciar preparación', `/online-orders/management/${delivery.id}/preparation`, 200);
  await clickExpectPost(staffPage, 'Marcar en reparto', `/online-orders/management/${delivery.id}/dispatch`, 200);
  await staffPage.getByRole('button', { name: 'Registrar entrega' }).click();
  await staffPage.getByLabel('Recibido por').fill('Receptor sintético delivery');
  await clickExpectPost(staffPage, 'Confirmar entrega', `/online-orders/management/${delivery.id}/delivery`, 200);
  assert.equal(await stock(staffPage, product.id), before - 2, 'delivery state changes must not create inventory movements');
  await staffPage.screenshot({ path: join(artifacts, 'staff-delivery-entregado.png'), fullPage: true });

  await clientPage.goto(`${baseUrl}/client-space`, { waitUntil: 'domcontentloaded' });
  await clientPage.getByRole('button', { name: 'Mis pedidos' }).click();
  const pickupCard = await clientOrderCard(clientPage, pickup.orderNumber);
  assert.match(await pickupCard.innerText(), /Pago:\s*Pago validado/);
  await clientOrderCard(clientPage, delivery.orderNumber);
  await clientPage.reload({ waitUntil: 'domcontentloaded' });
  await clientPage.getByRole('button', { name: 'Mis pedidos' }).click();
  await clientOrderCard(clientPage, delivery.orderNumber);
  await clientPage.screenshot({ path: join(artifacts, 'client-payment-and-delivery.png'), fullPage: true });

  const bosque = await browser.newContext(); const bosquePage = await bosque.newPage();
  await loginClient(bosquePage, 'veterinaria-bosque', process.env.AV_DEMO_CLIENT_BOSQUE_EMAIL, process.env.AV_DEMO_CLIENT_BOSQUE_PASSWORD);
  assert.equal((await browserFetch(bosquePage, `/api/v1/online-orders/${pickup.id}`)).status, 404, 'Bosque cannot access Aurora orders');
  assert.equal((await browserFetch(bosquePage, `/api/v1/online-orders/management/${pickup.id}`)).status, 403, 'CLIENT cannot manage payment or fulfillment');
  await bosque.close(); await client.close(); await staff.close();
  process.stdout.write('B05_C3_PAYMENT_FULFILLMENT_BROWSER_E2E=PASS\n');
} finally { await browser.close(); }

function orderDraft(product, mode) { return { operationId: crypto.randomUUID(), deliveryMode: mode, coordinationPhone: mode === 'RECOJO' ? '999 031 030' : '999 031 031', deliveryAddress: mode === 'DELIVERY' ? 'Calle sintética 031' : null, expectedDeliveryFeePen: mode === 'DELIVERY' ? 9.5 : null, lines: [{ productId: product.id, quantity: 1, expectedUnitPricePen: product.salePricePen }] }; }
async function openAndConfirm(page, order) {
  await page.goto(`${baseUrl}/online-orders/management`, { waitUntil: 'domcontentloaded' });
  const loading = page.getByText('Cargando pedidos autorizados…');
  await loading.waitFor({ state: 'hidden', timeout: 15000 });
  const refresh = page.waitForResponse((candidate) => candidate.request().method() === 'GET' && new URL(candidate.url()).pathname === '/api/v1/online-orders/management');
  await page.getByRole('button', { name: 'Actualizar' }).click();
  const managementResponse = await refresh;
  assert.equal(managementResponse.status(), 200, 'STAFF must receive its tenant-local order list');
  const managementPayload = await managementResponse.json();
  assert.ok(managementPayload.data.some((item) => item.id === order.id), 'STAFF must receive the newly created order from its own tenant');
  await loading.waitFor({ state: 'visible', timeout: 5000 });
  await loading.waitFor({ state: 'hidden', timeout: 30000 });
  const orderLabel = `#${order.orderNumber}`;
  const renderedOrderNumbers = await page.locator('tbody tr strong').allTextContents();
  assert.ok(renderedOrderNumbers.includes(orderLabel), 'STAFF must render the newly created order');
  const row = page.locator('tbody tr').filter({ has: page.getByText(orderLabel, { exact: true }) });
  await row.waitFor({ timeout: 15000 });
  await row.getByRole('button', { name: 'Ver detalle' }).click();
  await page.getByRole('heading', { name: 'Detalle y condiciones' }).waitFor();
  await clickExpectPost(page, 'Confirmar pedido y descontar existencias', `/online-orders/management/${order.id}/confirmation`, 200);
}
async function clientOrderCard(page, orderNumber) {
  const card = page.locator('article').filter({ has: page.getByText(`Pedido #${orderNumber} · Entregado`, { exact: true }) });
  await card.waitFor({ timeout: 15000 });
  return card;
}
async function clickExpectPost(page, label, path, expected, suffix = '') { const response = page.waitForResponse((candidate) => candidate.request().method() === 'POST' && new URL(candidate.url()).pathname.includes(path) && new URL(candidate.url()).pathname.endsWith(suffix || path.split('/').pop())); await page.getByRole('button', { name: label }).click(); assert.equal((await response).status(), expected); }
async function loginStaff(page) { await page.goto(`${baseUrl}/login/veterinaria-aurora`, { waitUntil: 'domcontentloaded' }); await page.getByLabel('Correo institucional').fill(process.env.AV_E2E_AURORA_EMAIL ?? 'administracion@demo.invalid'); await page.getByLabel(/Contrase/).fill(process.env.AV_DEMO_AURORA_PASSWORD); await page.getByRole('button', { name: 'Iniciar sesión' }).click(); await page.waitForURL(`${baseUrl}/workspace`); }
async function loginClient(page, tenant, email, password) { await page.goto(`${baseUrl}/client/login/${tenant}`, { waitUntil: 'domcontentloaded' }); await page.getByLabel(/Correo/).fill(email); await page.locator('#password').fill(password); await page.getByRole('button', { name: 'Iniciar sesión' }).click(); await page.waitForURL(`${baseUrl}/client-space`); }
async function browserFetch(page, path) { return page.evaluate(async (requestPath) => { const response = await fetch(requestPath); const text = await response.text(); return { status: response.status, body: text ? JSON.parse(text) : null }; }, path); }
async function apiPost(page, path, body, method = 'POST') { return page.evaluate(async ({ requestPath, payload, requestMethod }) => { const csrf = await fetch('/api/v1/auth/session-csrf-token', { method: 'POST', headers: { 'X-AV-CSRF-Bootstrap': '1' } }).then((response) => response.json()).then((result) => result.data.csrfToken); const response = await fetch(requestPath, { method: requestMethod, headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf }, body: JSON.stringify(payload) }); const text = await response.text(); return { status: response.status, body: text ? JSON.parse(text) : null }; }, { requestPath: path, payload: body, requestMethod: method }); }
async function stock(page, productId) { const rows = (await browserFetch(page, '/api/v1/inventory/stock')).body?.data ?? []; return rows.find((item) => item.productId === productId)?.availableQuantity ?? 0; }
