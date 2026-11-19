import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { chromium } from 'playwright-core';

const required = [
  'AV_DEMO_AURORA_PASSWORD',
  'AV_DEMO_CLIENT_AURORA_EMAIL',
  'AV_DEMO_CLIENT_AURORA_PASSWORD',
  'AV_B05D_CLIENT_AURORA_B_EMAIL',
  'AV_B05D_CLIENT_AURORA_B_PASSWORD',
  'AV_E2E_CHROME_PATH',
];
for (const key of required) {
  if (!process.env[key]) throw new Error(`Missing local B05-D variable: ${key}`);
}

const baseUrl = (process.env.AV_E2E_BASE_URL ?? 'http://127.0.0.1:4200').replace(/\/$/, '');
const suffix = `${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;
const browser = await chromium.launch({ executablePath: process.env.AV_E2E_CHROME_PATH, headless: true });

try {
  await mkdir('artifacts/b05-d', { recursive: true });
  const staff = await browser.newContext();
  const staffPage = await staff.newPage();
  await loginStaff(staffPage);

  const product = (await apiPost(staffPage, '/api/v1/catalog/products', {
    categoryId: null,
    sku: `B05-D-E2E-${suffix}`,
    title: `Producto sintético B05-D ${suffix}`,
    description: null,
    salePricePen: 23,
    status: 'ACTIVO_PUBLICADO',
    inventoryManaged: true,
    imageUrl: null,
  })).body.data;
  await apiPost(staffPage, `/api/v1/inventory/stock/products/${product.id}/adjustments`, {
    quantityDelta: 4,
    reason: 'Saldo sintético controlado B05-D',
    operationId: crypto.randomUUID(),
  });
  const stockBefore = await stock(staffPage, product.id);
  assert.equal(stockBefore, 4, 'the B05-D product must start with controlled stock');

  const clientA = await browser.newContext({ viewport: { width: 1280, height: 840 } });
  const clientAPage = await clientA.newPage();
  const orderA = await placePendingOrder(clientAPage, process.env.AV_DEMO_CLIENT_AURORA_EMAIL,
    process.env.AV_DEMO_CLIENT_AURORA_PASSWORD, product.id, '999 050 001');

  const clientB = await browser.newContext({ viewport: { width: 1280, height: 840 } });
  const clientBPage = await clientB.newPage();
  const orderB = await placePendingOrder(clientBPage, process.env.AV_B05D_CLIENT_AURORA_B_EMAIL,
    process.env.AV_B05D_CLIENT_AURORA_B_PASSWORD, product.id, '999 050 002');

  const ownA = (await browserFetch(clientAPage, '/api/v1/online-orders')).body?.data ?? [];
  const ownB = (await browserFetch(clientBPage, '/api/v1/online-orders')).body?.data ?? [];
  assert.ok(ownA.some((order) => order.id === orderA.id), 'CLIENT A must recover its own order');
  assert.ok(ownB.some((order) => order.id === orderB.id), 'CLIENT B must recover its own order');
  assert.ok(!ownA.some((order) => order.id === orderB.id), 'CLIENT A list must omit CLIENT B orders');
  assert.ok(!ownB.some((order) => order.id === orderA.id), 'CLIENT B list must omit CLIENT A orders');
  assert.equal((await browserFetch(clientAPage, `/api/v1/online-orders/${orderB.id}`)).status, 404,
    'CLIENT A must not fetch CLIENT B order by identifier');
  assert.equal((await browserFetch(clientBPage, `/api/v1/online-orders/${orderA.id}`)).status, 404,
    'CLIENT B must not fetch CLIENT A order by identifier');
  assert.equal(await stock(staffPage, product.id), stockBefore,
    'two PENDIENTE orders must neither reserve nor decrement inventory');

  await clientBPage.screenshot({ path: 'artifacts/b05-d/client-b-own-order.png', fullPage: true });
  await clientA.close();
  await clientB.close();
  await staff.close();
  process.stdout.write('B05_D_TWO_CLIENT_ISOLATION_BROWSER_E2E=PASS\n');
} finally {
  await browser.close();
}

async function placePendingOrder(page, email, password, productId, phone) {
  await loginClient(page, email, password);
  await page.goto(`${baseUrl}/tienda/veterinaria-aurora`, { waitUntil: 'domcontentloaded' });
  await page.locator(`[data-storefront-product-id="${productId}"]`).getByRole('button', { name: 'Añadir al carrito' }).click();
  await page.getByRole('link', { name: 'Continuar con el pedido' }).click();
  await page.getByLabel('Teléfono de coordinación').fill(phone);
  const created = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/v1/online-orders');
  await page.getByRole('button', { name: 'Registrar pedido pendiente' }).click();
  const response = await created;
  assert.equal(response.status(), 201, await response.text());
  const order = (await response.json()).data;
  assert.equal(order.status, 'PENDIENTE');
  await page.getByRole('heading', { name: new RegExp(`Pedido #${order.orderNumber} registrado`) }).waitFor();
  await page.getByRole('link', { name: 'Ver mis pedidos' }).click();
  await page.getByRole('button', { name: 'Mis pedidos' }).click();
  await page.getByText(`Pedido #${order.orderNumber} · Pendiente de confirmación`).waitFor();
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.getByRole('button', { name: 'Mis pedidos' }).click();
  await page.getByText(`Pedido #${order.orderNumber} · Pendiente de confirmación`).waitFor();
  return order;
}

async function loginStaff(page) {
  await page.goto(`${baseUrl}/login/veterinaria-aurora`, { waitUntil: 'domcontentloaded' });
  await page.getByLabel('Correo institucional').fill(process.env.AV_E2E_AURORA_EMAIL ?? 'administracion@demo.invalid');
  await page.getByLabel(/Contrase/).fill(process.env.AV_DEMO_AURORA_PASSWORD);
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
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf },
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
