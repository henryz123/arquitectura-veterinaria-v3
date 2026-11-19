import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';

for (const key of ['AV_DEMO_AURORA_PASSWORD', 'AV_DEMO_CLIENT_AURORA_EMAIL', 'AV_DEMO_CLIENT_AURORA_PASSWORD', 'AV_E2E_CHROME_PATH']) {
  if (!process.env[key]) throw new Error(`Missing local E2E variable: ${key}`);
}

const baseUrl = (process.env.AV_E2E_BASE_URL ?? 'http://127.0.0.1:4200').replace(/\/$/, '');
const staffEmail = process.env.AV_E2E_AURORA_EMAIL ?? 'administracion@demo.invalid';
const artifactDirectory = join(dirname(fileURLToPath(import.meta.url)), '..', 'artifacts', 'b05-c1');
const fixtureSku = 'B05-C1-E2E-SHARED-STOCK';
const fixtureTitle = 'Producto sintético B05-C1 — stock compartido';
const fixtureStock = 4;
const browser = await chromium.launch({ executablePath: process.env.AV_E2E_CHROME_PATH, headless: true });

try {
  const admin = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const adminPage = await admin.newPage();
  await loginStaff(adminPage, 'veterinaria-aurora', staffEmail, process.env.AV_DEMO_AURORA_PASSWORD);
  await adminPage.goto(`${baseUrl}/inventory`, { waitUntil: 'domcontentloaded' });
  await adminPage.getByRole('heading', { name: 'Productos de la veterinaria' }).waitFor();
  let products = (await browserFetch(adminPage, '/api/v1/catalog/products')).body?.data ?? [];
  let fixture = products.find((product) => product.sku === fixtureSku);
  if (!fixture) {
    const createdProduct = adminPage.waitForResponse((response) => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/v1/catalog/products');
    await adminPage.locator('#product-sku').fill(fixtureSku);
    await adminPage.locator('#product-title-input').fill(fixtureTitle);
    await adminPage.locator('#product-price').fill('21.00');
    await adminPage.locator('#product-status').selectOption('ACTIVO_PUBLICADO');
    await adminPage.locator('#inventory-managed').check();
    await adminPage.getByRole('button', { name: 'Registrar producto' }).click();
    assert.equal((await createdProduct).status(), 201, 'the B05-C1 fixture must be created through the visible B04 catalog');
  }
  products = (await browserFetch(adminPage, '/api/v1/catalog/products')).body?.data ?? [];
  fixture = products.find((product) => product.sku === fixtureSku);
  assert.ok(fixture?.id && fixture.inventoryManaged && fixture.status === 'ACTIVO_PUBLICADO', 'the fixture must be a published physical Aurora product');
  let balancesBefore = (await browserFetch(adminPage, '/api/v1/inventory/stock')).body?.data ?? [];
  const delta = fixtureStock - stockFor(balancesBefore, fixture.id);
  if (delta !== 0) {
    await adminPage.locator('#stock-product option', { hasText: fixtureSku }).waitFor({ state: 'attached' });
    await adminPage.locator('#stock-product').selectOption(fixture.id);
    await adminPage.locator('#stock-quantity').fill(String(delta));
    await adminPage.locator('#stock-reason').fill('Saldo sintético controlado B05-C1');
    const adjusted = adminPage.waitForResponse((response) => response.request().method() === 'POST'
      && new URL(response.url()).pathname.endsWith(`/inventory/stock/products/${fixture.id}/adjustments`));
    await adminPage.getByRole('button', { name: 'Registrar movimiento' }).click();
    assert.equal((await adjusted).status(), 201, 'the B05-C1 fixture stock must persist through the existing B04 form');
    balancesBefore = (await browserFetch(adminPage, '/api/v1/inventory/stock')).body?.data ?? [];
  }
  const productsForOrder = [fixture];
  const stockBefore = new Map(productsForOrder.map((product) => [product.id, stockFor(balancesBefore, product.id)]));

  const client = await browser.newContext({ viewport: { width: 1280, height: 840 } });
  const clientPage = await client.newPage();
  await clientPage.goto(`${baseUrl}/tienda/veterinaria-aurora`, { waitUntil: 'domcontentloaded' });
  for (const product of productsForOrder) {
    await clientPage.locator(`[data-storefront-product-id="${product.id}"]`).getByRole('button', { name: 'Añadir al carrito' }).click();
  }
  await clientPage.getByRole('link', { name: 'Continuar con el pedido' }).click();
  await clientPage.waitForURL(`${baseUrl}/client/login/veterinaria-aurora?returnTo=%2Ftienda%2Fveterinaria-aurora%2Fcheckout`);
  await clientPage.getByLabel(/Correo/).fill(process.env.AV_DEMO_CLIENT_AURORA_EMAIL);
  await clientPage.locator('#password').fill(process.env.AV_DEMO_CLIENT_AURORA_PASSWORD);
  await clientPage.getByRole('button', { name: 'Iniciar sesión' }).click();
  await clientPage.waitForURL(`${baseUrl}/tienda/veterinaria-aurora/checkout`);
  await clientPage.getByLabel('Teléfono de coordinación').fill('999 003 029');
  const createdResponse = clientPage.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/v1/online-orders');
  await clientPage.getByRole('button', { name: 'Registrar pedido pendiente' }).click();
  const created = await createdResponse;
  assert.equal(created.status(), 201, await created.text());
  const order = (await created.json()).data;
  assert.equal(order.status, 'PENDIENTE');
  assert.equal(order.lines.length, 1);
  const stockAfterPending = (await browserFetch(adminPage, '/api/v1/inventory/stock')).body?.data ?? [];
  for (const product of productsForOrder) assert.equal(stockFor(stockAfterPending, product.id), stockBefore.get(product.id), 'a pending order must not change stock');

  await adminPage.goto(`${baseUrl}/online-orders/management`, { waitUntil: 'domcontentloaded' });
  await adminPage.getByRole('heading', { name: 'Pedidos web' }).waitFor();
  const orderRow = adminPage.locator('tr', { hasText: `#${order.orderNumber}` });
  await orderRow.getByRole('button', { name: 'Ver detalle' }).click();
  await adminPage.getByRole('heading', { name: 'Detalle y confirmación' }).waitFor();
  const confirmation = adminPage.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === `/api/v1/online-orders/management/${order.id}/confirmation`);
  await adminPage.getByRole('button', { name: 'Confirmar pedido y descontar existencias' }).click();
  const confirmationResponse = await confirmation;
  assert.equal(confirmationResponse.status(), 200, await confirmationResponse.text());
  assert.equal((await confirmationResponse.json()).data.status, 'CONFIRMADO');
  await adminPage.getByText('Pedido confirmado. Las existencias se descontaron una sola vez.').waitFor();
  await mkdir(artifactDirectory, { recursive: true });
  await adminPage.screenshot({ path: join(artifactDirectory, 'staff-confirmed-order.png'), fullPage: true });

  const stockAfterConfirmation = (await browserFetch(adminPage, '/api/v1/inventory/stock')).body?.data ?? [];
  for (const product of productsForOrder) {
    assert.equal(stockFor(stockAfterConfirmation, product.id), stockBefore.get(product.id) - 1, 'confirmation must decrease the shared B04 balance exactly once');
    const movements = (await browserFetch(adminPage, `/api/v1/inventory/stock/movements?productId=${product.id}`)).body?.data ?? [];
    assert.ok(movements.some((movement) => movement.movementType === 'CONFIRMACION_ONLINE' && movement.reason === `Confirmación de pedido online #${order.orderNumber}`), 'the shared ledger must retain a causal online confirmation movement');
  }

  await clientPage.goto(`${baseUrl}/client-space`, { waitUntil: 'domcontentloaded' });
  await clientPage.getByRole('button', { name: 'Mis pedidos' }).click();
  await clientPage.getByText(`Pedido #${order.orderNumber} · Confirmado`).waitFor();
  await clientPage.reload({ waitUntil: 'domcontentloaded' });
  await clientPage.getByRole('button', { name: 'Mis pedidos' }).click();
  await clientPage.getByText(`Pedido #${order.orderNumber} · Confirmado`).waitFor();
  await clientPage.screenshot({ path: join(artifactDirectory, 'client-confirmed-order.png'), fullPage: true });

  await client.close();
  await admin.close();
  process.stdout.write('B05_C1_ORDER_CONFIRMATION_BROWSER_E2E=PASS\n');
} finally {
  await browser.close();
}

async function loginStaff(page, slug, email, password) {
  await page.goto(`${baseUrl}/login/${slug}`, { waitUntil: 'domcontentloaded' });
  await page.getByLabel('Correo institucional').fill(email);
  await page.getByLabel(/Contrase/).fill(password);
  await page.getByRole('button', { name: 'Iniciar sesión' }).click();
  await page.waitForURL(`${baseUrl}/workspace`);
}

async function browserFetch(page, path) {
  return page.evaluate(async (requestPath) => {
    const response = await fetch(requestPath);
    const text = await response.text();
    return { status: response.status, body: text ? JSON.parse(text) : null };
  }, path);
}

function stockFor(rows, productId) {
  return rows.find((row) => row.productId === productId)?.availableQuantity ?? 0;
}
