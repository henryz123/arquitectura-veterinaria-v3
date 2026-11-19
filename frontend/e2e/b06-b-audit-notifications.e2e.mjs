import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { chromium } from 'playwright-core';

const required = [
  'AV_DEMO_AURORA_PASSWORD',
  'AV_DEMO_BOSQUE_PASSWORD',
  'AV_DEMO_CLIENT_AURORA_EMAIL',
  'AV_DEMO_CLIENT_AURORA_PASSWORD',
  'AV_B05D_CLIENT_AURORA_B_EMAIL',
  'AV_B05D_CLIENT_AURORA_B_PASSWORD',
  'AV_E2E_CHROME_PATH',
];
for (const key of required) if (!process.env[key]) throw new Error(`Missing local B06-B variable: ${key}`);

const baseUrl = (process.env.AV_E2E_BASE_URL ?? 'http://127.0.0.1:4200').replace(/\/$/, '');
const suffix = `${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;
const browser = await chromium.launch({ executablePath: process.env.AV_E2E_CHROME_PATH, headless: true });

try {
  await mkdir('artifacts/b06-b', { recursive: true });
  const staff = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const staffPage = await staff.newPage();
  const clientA = await browser.newContext({ viewport: { width: 1280, height: 840 } });
  const clientAPage = await clientA.newPage();
  const clientB = await browser.newContext({ viewport: { width: 1280, height: 840 } });
  const clientBPage = await clientB.newPage();
  const bosqueStaff = await browser.newContext({ viewport: { width: 1280, height: 840 } });
  const bosqueStaffPage = await bosqueStaff.newPage();
  const pageErrors = [];
  const browserErrors = [];
  [staffPage, clientAPage, clientBPage, bosqueStaffPage].forEach((page) => {
    page.on('pageerror', (error) => pageErrors.push(error.message));
    page.on('console', (message) => { if (message.type() === 'error') browserErrors.push(message.text()); });
  });

  await loginStaff(staffPage);
  const product = (await apiPost(staffPage, '/api/v1/catalog/products', {
    categoryId: null,
    sku: `B06-B-E2E-${suffix}`,
    title: `Producto sintético B06-B ${suffix}`,
    description: null,
    salePricePen: 17,
    status: 'ACTIVO_PUBLICADO',
    inventoryManaged: true,
    imageUrl: null,
  })).body.data;
  await apiPost(staffPage, `/api/v1/inventory/stock/products/${product.id}/adjustments`, {
    quantityDelta: 2,
    reason: 'Saldo sintético B06-B',
    operationId: crypto.randomUUID(),
  });
  const stockBefore = await stock(staffPage, product.id);
  assert.equal(stockBefore, 2, 'the B06-B fixture must start with the controlled balance');

  await loginClient(clientAPage, process.env.AV_DEMO_CLIENT_AURORA_EMAIL, process.env.AV_DEMO_CLIENT_AURORA_PASSWORD);
  await clientAPage.goto(`${baseUrl}/tienda/veterinaria-aurora`, { waitUntil: 'domcontentloaded' });
  await clientAPage.locator(`[data-storefront-product-id="${product.id}"]`).getByRole('button', { name: 'Añadir al carrito' }).click();
  await clientAPage.getByRole('link', { name: 'Continuar con el pedido' }).click();
  await clientAPage.getByLabel('Teléfono de coordinación').fill('999 060 601');
  const created = clientAPage.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/v1/online-orders');
  await clientAPage.getByRole('button', { name: 'Registrar pedido pendiente' }).click();
  const createdResponse = await created;
  assert.equal(createdResponse.status(), 201, await createdResponse.text());
  const order = (await createdResponse.json()).data;
  assert.equal(order.status, 'PENDIENTE');
  assert.equal(await stock(staffPage, product.id), stockBefore, 'a pending order must not alter B04 stock');

  await staffPage.goto(`${baseUrl}/notifications`, { waitUntil: 'domcontentloaded' });
  const staffNotifications = (await browserFetch(staffPage, '/api/v1/notifications')).body?.data ?? [];
  assert.ok(staffNotifications.some((item) => item.sourceResourceId === order.id && item.notificationCode === 'ONLINE_ORDER_CREATED'),
    'active commercial STAFF must receive the created-order notification in the same tenant');

  await clientAPage.goto(`${baseUrl}/notifications`, { waitUntil: 'domcontentloaded' });
  assert.deepEqual(pageErrors, [], `Angular runtime errors: ${pageErrors.join(' | ')}`);
  assert.deepEqual(browserErrors, [], `Browser console errors: ${browserErrors.join(' | ')}`);
  await clientAPage.getByRole('heading', { name: 'Notificaciones' }).waitFor();
  await clientAPage.getByRole('heading', { name: 'Pedido registrado' }).first().waitFor();
  const ownNotifications = (await browserFetch(clientAPage, '/api/v1/notifications')).body?.data ?? [];
  const notification = ownNotifications.find((item) => item.sourceResourceId === order.id && item.notificationCode === 'ONLINE_ORDER_CREATED');
  assert.ok(notification, 'the created order must produce one persisted in-account notification for its purchaser');
  await clientAPage.getByRole('button', { name: 'Marcar como leída' }).first().click();
  await clientAPage.screenshot({ path: 'artifacts/b06-b/client-notifications.png', fullPage: true });

  await loginClient(clientBPage, process.env.AV_B05D_CLIENT_AURORA_B_EMAIL, process.env.AV_B05D_CLIENT_AURORA_B_PASSWORD);
  const otherNotifications = (await browserFetch(clientBPage, '/api/v1/notifications')).body?.data ?? [];
  assert.ok(!otherNotifications.some((item) => item.sourceResourceId === order.id), 'a second CLIENT in Aurora must not read the first CLIENT notification');
  const crossRecipientRead = await apiPatch(clientBPage, `/api/v1/notifications/${notification.id}/read`, {});
  assert.equal(crossRecipientRead.status, 404, 'a second CLIENT must not mark another recipient notification as read');
  const clientAudit = await browserFetch(clientAPage, '/api/v1/audit-trail?from=2026-01-01&to=2030-12-31&module=B05&page=0&size=25');
  assert.equal(clientAudit.status, 403, 'CLIENT must not access the protected audit endpoint');

  await staffPage.goto(`${baseUrl}/audit-trail`, { waitUntil: 'domcontentloaded' });
  await staffPage.getByRole('heading', { name: 'Auditoría transversal' }).waitFor();
  await staffPage.getByLabel('Módulo').selectOption('B05');
  const auditResponse = staffPage.waitForResponse((response) => response.request().method() === 'GET'
    && new URL(response.url()).pathname === '/api/v1/audit-trail' && new URL(response.url()).searchParams.get('module') === 'B05');
  await staffPage.getByRole('button', { name: 'Consultar' }).click();
  const auditedResponse = await auditResponse;
  assert.equal(auditedResponse.status(), 200, 'administrator audit query must be allowed');
  const audit = (await auditedResponse.json()).data;
  assert.ok(audit.events.some((item) => item.resourceId === order.id), 'the protected audit projection must reference the real B05 event');
  assert.equal(JSON.stringify(audit).match(/password|token|cookie|email|reason/iu), null, 'the audit payload must not contain secret or free-text fields');
  const b03Audit = await browserFetch(staffPage, '/api/v1/audit-trail?from=2026-01-01&to=2026-11-16&module=B03&page=0&size=25');
  assert.equal(b03Audit.status, 200, 'administrator audit query must include the B03 source projection');
  assert.ok(b03Audit.body.data.events.length > 0, 'Aurora must expose at least one persisted B03 audit event to its administrator');
  assert.ok(b03Audit.body.data.events.every((item) => item.sourceModule === 'B03'), 'the B03 filter must not mix module sources');

  await loginStaff(bosqueStaffPage, 'veterinaria-bosque', process.env.AV_DEMO_BOSQUE_PASSWORD);
  const bosqueAudit = await browserFetch(bosqueStaffPage, `/api/v1/audit-trail${new URL(auditedResponse.url()).search}`);
  assert.equal(bosqueAudit.status, 200, 'Bosque administration may only query its own protected audit projection');
  assert.ok(!bosqueAudit.body.data.events.some((item) => item.resourceId === order.id), 'Bosque must not read the Aurora B05 audit event');
  await staffPage.screenshot({ path: 'artifacts/b06-b/staff-audit-trail.png', fullPage: true });
  assert.deepEqual(pageErrors, [], 'the B06-B routes must not raise browser page errors');

  await clientB.close();
  await clientA.close();
  await bosqueStaff.close();
  await staff.close();
  process.stdout.write('B06_B_AUDIT_NOTIFICATIONS_BROWSER_E2E=PASS\n');
} finally {
  await browser.close();
}

async function loginStaff(page, tenantSlug = 'veterinaria-aurora', password = process.env.AV_DEMO_AURORA_PASSWORD) {
  await page.goto(`${baseUrl}/login/${tenantSlug}`, { waitUntil: 'domcontentloaded' });
  await page.getByLabel('Correo institucional').fill(process.env.AV_E2E_AURORA_EMAIL ?? 'administracion@demo.invalid');
  await page.getByLabel(/Contrase/).fill(password);
  await page.getByRole('button', { name: 'Iniciar sesión' }).click();
  await page.waitForURL(`${baseUrl}/workspace`);
}

async function loginClient(page, email, password) {
  await page.goto(`${baseUrl}/client/login/veterinaria-aurora`, { waitUntil: 'domcontentloaded' });
  await page.getByLabel('Correo de cliente').fill(email);
  await page.locator('#password').fill(password);
  await page.getByRole('button', { name: 'Iniciar sesión' }).click();
  await page.waitForURL(`${baseUrl}/client-space`);
}

async function browserFetch(page, path) {
  return page.evaluate(async (requestPath) => {
    const response = await fetch(requestPath);
    const text = await response.text();
    return { status: response.status, body: text ? JSON.parse(text) : null };
  }, path);
}

async function apiPost(page, path, body) {
  return page.evaluate(async ({ requestPath, payload }) => {
    const csrf = await fetch('/api/v1/auth/session-csrf-token', {
      method: 'POST', headers: { 'X-AV-CSRF-Bootstrap': '1' },
    }).then((response) => response.json()).then((result) => result.data.csrfToken);
    const response = await fetch(requestPath, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf },
      body: JSON.stringify(payload),
    });
    const text = await response.text();
    return { status: response.status, body: text ? JSON.parse(text) : null };
  }, { requestPath: path, payload: body });
}

async function apiPatch(page, path, body) {
  return page.evaluate(async ({ requestPath, payload }) => {
    const csrf = await fetch('/api/v1/auth/session-csrf-token', {
      method: 'POST', headers: { 'X-AV-CSRF-Bootstrap': '1' },
    }).then((response) => response.json()).then((result) => result.data.csrfToken);
    const response = await fetch(requestPath, {
      method: 'PATCH', headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf },
      body: JSON.stringify(payload),
    });
    const text = await response.text();
    return { status: response.status, body: text ? JSON.parse(text) : null };
  }, { requestPath: path, payload: body });
}

async function stock(page, productId) {
  const rows = (await browserFetch(page, '/api/v1/inventory/stock')).body?.data ?? [];
  return rows.find((item) => item.productId === productId)?.availableQuantity ?? 0;
}
