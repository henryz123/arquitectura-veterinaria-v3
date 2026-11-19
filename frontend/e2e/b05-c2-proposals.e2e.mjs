import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';

for (const key of ['AV_DEMO_AURORA_PASSWORD', 'AV_DEMO_CLIENT_AURORA_EMAIL', 'AV_DEMO_CLIENT_AURORA_PASSWORD', 'AV_DEMO_CLIENT_BOSQUE_EMAIL', 'AV_DEMO_CLIENT_BOSQUE_PASSWORD', 'AV_E2E_CHROME_PATH']) {
  if (!process.env[key]) throw new Error(`Missing local E2E variable: ${key}`);
}

const baseUrl = (process.env.AV_E2E_BASE_URL ?? 'http://127.0.0.1:4200').replace(/\/$/, '');
const staffEmail = process.env.AV_E2E_AURORA_EMAIL ?? 'administracion@demo.invalid';
const artifactDirectory = join(dirname(fileURLToPath(import.meta.url)), '..', 'artifacts', 'b05-c2');
const suffix = `${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;
const fixtureSku = `B05-C2-E2E-${suffix}`;
const fixtureTitle = `Producto sintético B05-C2 ${suffix}`;
const rejectionSku = `${fixtureSku}-REJECT`;
const rejectionTitle = `Producto sintético B05-C2 rechazo ${suffix}`;
const browser = await chromium.launch({ executablePath: process.env.AV_E2E_CHROME_PATH, headless: true });

try {
  await mkdir(artifactDirectory, { recursive: true });
  const staff = await browser.newContext({ viewport: { width: 1440, height: 920 } });
  const staffPage = await staff.newPage();
  await loginStaff(staffPage, 'veterinaria-aurora', staffEmail, process.env.AV_DEMO_AURORA_PASSWORD);
  await staffPage.goto(`${baseUrl}/inventory`, { waitUntil: 'domcontentloaded' });
  await staffPage.getByRole('heading', { name: 'Productos de la veterinaria' }).waitFor();
  await staffPage.locator('#product-sku').fill(fixtureSku);
  await staffPage.locator('#product-title-input').fill(fixtureTitle);
  await staffPage.locator('#product-price').fill('18.00');
  await staffPage.locator('#product-status').selectOption('ACTIVO_PUBLICADO');
  await staffPage.locator('#inventory-managed').check();
  const productCreated = staffPage.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/v1/catalog/products');
  await staffPage.getByRole('button', { name: 'Registrar producto' }).click();
  assert.equal((await productCreated).status(), 201, 'the fixture product must be persisted through the visible B04 catalog form');
  const catalog = (await browserFetch(staffPage, '/api/v1/catalog/products')).body?.data ?? [];
  const fixture = catalog.find((product) => product.sku === fixtureSku);
  assert.ok(fixture?.id, 'the B05-C2 fixture product must be readable after creation');
  await staffPage.locator('#stock-product').selectOption(fixture.id);
  await staffPage.locator('#stock-quantity').fill('1');
  await staffPage.locator('#stock-reason').fill('Saldo sintético controlado B05-C2');
  const stockAdded = staffPage.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith(`/inventory/stock/products/${fixture.id}/adjustments`));
  await staffPage.getByRole('button', { name: 'Registrar movimiento' }).click();
  assert.equal((await stockAdded).status(), 201, 'the one-unit stock fixture must persist through the visible B04 stock form');
  const stockBefore = stockFor((await browserFetch(staffPage, '/api/v1/inventory/stock')).body?.data ?? [], fixture.id);
  assert.equal(stockBefore, 1);

  const client = await browser.newContext({ viewport: { width: 1280, height: 860 } });
  const clientPage = await client.newPage();
  await clientPage.goto(`${baseUrl}/tienda/veterinaria-aurora`, { waitUntil: 'domcontentloaded' });
  const card = clientPage.locator(`[data-storefront-product-id="${fixture.id}"]`);
  await card.getByRole('button', { name: 'Añadir al carrito' }).click();
  const quantity = clientPage.getByLabel(`Cantidad de ${fixtureTitle}`);
  await quantity.fill('2');
  await quantity.press('Tab');
  await clientPage.getByRole('link', { name: 'Continuar con el pedido' }).click();
  await clientPage.waitForURL(`${baseUrl}/client/login/veterinaria-aurora?returnTo=%2Ftienda%2Fveterinaria-aurora%2Fcheckout`);
  await clientPage.getByLabel(/Correo/).fill(process.env.AV_DEMO_CLIENT_AURORA_EMAIL);
  await clientPage.locator('#password').fill(process.env.AV_DEMO_CLIENT_AURORA_PASSWORD);
  await clientPage.getByRole('button', { name: 'Iniciar sesión' }).click();
  await clientPage.waitForURL(`${baseUrl}/tienda/veterinaria-aurora/checkout`);
  await clientPage.getByLabel('Teléfono de coordinación').fill('999 030 030');
  const pendingCreated = clientPage.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/v1/online-orders');
  await clientPage.getByRole('button', { name: 'Registrar pedido pendiente' }).click();
  const pendingResponse = await pendingCreated;
  assert.equal(pendingResponse.status(), 201, await pendingResponse.text());
  const original = (await pendingResponse.json()).data;
  assert.equal(original.status, 'PENDIENTE');
  assert.equal(original.lines[0].quantity, 2);
  assert.equal(stockFor((await browserFetch(staffPage, '/api/v1/inventory/stock')).body?.data ?? [], fixture.id), stockBefore, 'the pending order must not reserve stock');

  await staffPage.goto(`${baseUrl}/online-orders/management`, { waitUntil: 'domcontentloaded' });
  const row = staffPage.locator('tr', { hasText: `#${original.orderNumber}` });
  await row.getByRole('button', { name: 'Ver detalle' }).click();
  await staffPage.getByRole('heading', { name: 'Detalle y condiciones' }).waitFor();
  const insufficient = staffPage.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith(`/online-orders/management/${original.id}/confirmation`));
  await staffPage.getByRole('button', { name: 'Confirmar pedido y descontar existencias' }).click();
  const insufficientResponse = await insufficient;
  assert.equal(insufficientResponse.status(), 200, await insufficientResponse.text());
  assert.equal((await insufficientResponse.json()).data.status, 'EN_REVISION');
  assert.equal(stockFor((await browserFetch(staffPage, '/api/v1/inventory/stock')).body?.data ?? [], fixture.id), stockBefore, 'an insufficient original request must not make a partial movement');

  await staffPage.getByRole('button', { name: 'Preparar propuesta' }).last().click();
  await staffPage.getByRole('heading', { name: 'Nueva propuesta para el cliente' }).waitFor();
  await staffPage.locator('#proposal-reason').fill('Se propone una unidad disponible para mantener la atención del pedido.');
  await staffPage.locator('#proposal-quantity-0').fill('1');
  const proposalCreated = staffPage.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith(`/online-orders/management/${original.id}/proposals`));
  await staffPage.getByRole('button', { name: 'Enviar propuesta al cliente' }).click();
  const proposalResponse = await proposalCreated;
  assert.equal(proposalResponse.status(), 201, await proposalResponse.text());
  const proposed = (await proposalResponse.json()).data;
  assert.equal(proposed.status, 'CAMBIO_PROPUESTO');
  assert.equal(proposed.proposals[0].previousTerms.lines[0].quantity, 2);
  assert.equal(proposed.proposals[0].proposedTerms.lines[0].quantity, 1);
  await staffPage.screenshot({ path: join(artifactDirectory, 'staff-proposal.png'), fullPage: true });

  await clientPage.goto(`${baseUrl}/client-space`, { waitUntil: 'domcontentloaded' });
  await clientPage.getByRole('button', { name: 'Mis pedidos' }).click();
  await clientPage.getByRole('heading', { name: 'Mis pedidos' }).waitFor();
  const clientOrder = clientPage.locator('.schedule-list > article', { hasText: `Pedido #${original.orderNumber}` });
  const proposalTitle = clientOrder.getByRole('heading', { name: /Propuesta #1/ });
  await proposalTitle.waitFor();
  await clientOrder.getByText('Tu solicitud original').waitFor();
  await clientOrder.getByText('Nueva propuesta').waitFor();
  const accepted = clientPage.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.includes(`/online-orders/proposals/${proposed.proposals[0].id}/decision`));
  await clientOrder.getByRole('button', { name: 'Aceptar propuesta' }).click();
  const acceptedResponse = await accepted;
  assert.equal(acceptedResponse.status(), 200, await acceptedResponse.text());
  assert.equal((await acceptedResponse.json()).data.status, 'EN_REVISION');
  await clientOrder.getByText(/Propuesta #1 · Aceptada/).waitFor();
  assert.equal(stockFor((await browserFetch(staffPage, '/api/v1/inventory/stock')).body?.data ?? [], fixture.id), stockBefore, 'accepting a proposal must not reserve stock');
  await clientPage.screenshot({ path: join(artifactDirectory, 'client-accepted-proposal.png'), fullPage: true });

  await staffPage.reload({ waitUntil: 'domcontentloaded' });
  const reviewedRow = staffPage.locator('tr', { hasText: `#${original.orderNumber}` });
  await reviewedRow.getByRole('button', { name: 'Confirmar' }).click();
  await staffPage.getByText('Pedido confirmado. Las existencias se descontaron una sola vez.').waitFor();
  assert.equal(stockFor((await browserFetch(staffPage, '/api/v1/inventory/stock')).body?.data ?? [], fixture.id), 0, 'confirmation must consume exactly the accepted quantity');
  const movements = (await browserFetch(staffPage, `/api/v1/inventory/stock/movements?productId=${fixture.id}`)).body?.data ?? [];
  assert.equal(movements.filter((movement) => movement.movementType === 'CONFIRMACION_ONLINE').length, 1, 'the accepted version creates exactly one online stock movement');
  await staffPage.screenshot({ path: join(artifactDirectory, 'staff-confirmed-accepted-proposal.png'), fullPage: true });

  await clientPage.reload({ waitUntil: 'domcontentloaded' });
  await clientPage.getByRole('button', { name: 'Mis pedidos' }).click();
  await clientPage.getByText(`Pedido #${original.orderNumber} · Confirmado`).waitFor();
  await clientPage.screenshot({ path: join(artifactDirectory, 'client-confirmed-accepted-proposal.png'), fullPage: true });

  await staffPage.goto(`${baseUrl}/inventory`, { waitUntil: 'domcontentloaded' });
  await staffPage.locator('#product-sku').fill(rejectionSku);
  await staffPage.locator('#product-title-input').fill(rejectionTitle);
  await staffPage.locator('#product-price').fill('16.00');
  await staffPage.locator('#product-status').selectOption('ACTIVO_PUBLICADO');
  await staffPage.locator('#inventory-managed').check();
  const rejectionProductCreated = staffPage.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/v1/catalog/products');
  await staffPage.getByRole('button', { name: 'Registrar producto' }).click();
  assert.equal((await rejectionProductCreated).status(), 201, 'the rejection fixture must be created through the B04 catalog form');
  const catalogAfterConfirmation = (await browserFetch(staffPage, '/api/v1/catalog/products')).body?.data ?? [];
  const rejectionFixture = catalogAfterConfirmation.find((product) => product.sku === rejectionSku);
  assert.ok(rejectionFixture?.id, 'the rejection fixture must be persisted');
  await staffPage.locator('#stock-product').selectOption(rejectionFixture.id);
  await staffPage.locator('#stock-quantity').fill('1');
  await staffPage.locator('#stock-reason').fill('Saldo sintético controlado B05-C2 para rechazo');
  const rejectionStockAdded = staffPage.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith(`/inventory/stock/products/${rejectionFixture.id}/adjustments`));
  await staffPage.getByRole('button', { name: 'Registrar movimiento' }).click();
  assert.equal((await rejectionStockAdded).status(), 201);
  const rejectionStockBefore = stockFor((await browserFetch(staffPage, '/api/v1/inventory/stock')).body?.data ?? [], rejectionFixture.id);
  assert.equal(rejectionStockBefore, 1);

  await clientPage.goto(`${baseUrl}/tienda/veterinaria-aurora`, { waitUntil: 'domcontentloaded' });
  const rejectionCard = clientPage.locator(`[data-storefront-product-id="${rejectionFixture.id}"]`);
  await rejectionCard.getByRole('button', { name: 'Añadir al carrito' }).click();
  const rejectionQuantity = clientPage.getByLabel(`Cantidad de ${rejectionTitle}`);
  await rejectionQuantity.fill('2');
  await rejectionQuantity.press('Tab');
  await clientPage.getByRole('link', { name: 'Continuar con el pedido' }).click();
  await clientPage.waitForURL(`${baseUrl}/tienda/veterinaria-aurora/checkout`);
  await clientPage.getByLabel('Teléfono de coordinación').fill('999 030 031');
  const rejectionPendingCreated = clientPage.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/v1/online-orders');
  await clientPage.getByRole('button', { name: 'Registrar pedido pendiente' }).click();
  const rejectionPendingResponse = await rejectionPendingCreated;
  assert.equal(rejectionPendingResponse.status(), 201, await rejectionPendingResponse.text());
  const rejectionOrder = (await rejectionPendingResponse.json()).data;
  assert.equal(rejectionOrder.status, 'PENDIENTE');

  await staffPage.goto(`${baseUrl}/online-orders/management`, { waitUntil: 'domcontentloaded' });
  const rejectionRow = staffPage.locator('tr', { hasText: `#${rejectionOrder.orderNumber}` });
  await rejectionRow.getByRole('button', { name: 'Ver detalle' }).click();
  const rejectionInsufficient = staffPage.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith(`/online-orders/management/${rejectionOrder.id}/confirmation`));
  await staffPage.getByRole('button', { name: 'Confirmar pedido y descontar existencias' }).click();
  assert.equal((await rejectionInsufficient).status(), 200);
  assert.equal(stockFor((await browserFetch(staffPage, '/api/v1/inventory/stock')).body?.data ?? [], rejectionFixture.id), rejectionStockBefore);
  await staffPage.getByRole('button', { name: 'Preparar propuesta' }).last().click();
  await staffPage.locator('#proposal-reason').fill('Se propone una unidad, sujeta a tu aceptación expresa.');
  await staffPage.locator('#proposal-quantity-0').fill('1');
  const rejectionProposed = staffPage.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith(`/online-orders/management/${rejectionOrder.id}/proposals`));
  await staffPage.getByRole('button', { name: 'Enviar propuesta al cliente' }).click();
  const rejectionProposalResponse = await rejectionProposed;
  assert.equal(rejectionProposalResponse.status(), 201, await rejectionProposalResponse.text());
  const rejectionProposedOrder = (await rejectionProposalResponse.json()).data;

  await clientPage.goto(`${baseUrl}/client-space`, { waitUntil: 'domcontentloaded' });
  await clientPage.getByRole('button', { name: 'Mis pedidos' }).click();
  const rejectionClientOrder = clientPage.locator('.schedule-list > article', { hasText: `Pedido #${rejectionOrder.orderNumber}` });
  await rejectionClientOrder.getByRole('button', { name: 'Rechazar propuesta' }).waitFor();
  const rejected = clientPage.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.includes(`/online-orders/proposals/${rejectionProposedOrder.proposals[0].id}/decision`));
  await rejectionClientOrder.getByRole('button', { name: 'Rechazar propuesta' }).click();
  const rejectedResponse = await rejected;
  assert.equal(rejectedResponse.status(), 200, await rejectedResponse.text());
  assert.equal((await rejectedResponse.json()).data.status, 'EN_REVISION');
  assert.equal(stockFor((await browserFetch(staffPage, '/api/v1/inventory/stock')).body?.data ?? [], rejectionFixture.id), rejectionStockBefore, 'a rejected proposal must not alter stock');
  await rejectionClientOrder.getByText(/Propuesta #1 · Rechazada/).waitFor();
  await clientPage.screenshot({ path: join(artifactDirectory, 'client-rejected-proposal.png'), fullPage: true });

  const bosque = await browser.newContext();
  const bosquePage = await bosque.newPage();
  await loginClient(bosquePage, 'veterinaria-bosque', process.env.AV_DEMO_CLIENT_BOSQUE_EMAIL, process.env.AV_DEMO_CLIENT_BOSQUE_PASSWORD);
  assert.equal((await browserFetch(bosquePage, `/api/v1/online-orders/${original.id}`)).status, 404, 'Bosque must not read Aurora orders');
  await bosque.close();
  await client.close();
  await staff.close();
  process.stdout.write('B05_C2_PROPOSAL_BROWSER_E2E=PASS\n');
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

async function loginClient(page, slug, email, password) {
  await page.goto(`${baseUrl}/client/login/${slug}`, { waitUntil: 'domcontentloaded' });
  await page.getByLabel(/Correo/).fill(email);
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

function stockFor(rows, productId) {
  return rows.find((row) => row.productId === productId)?.availableQuantity ?? 0;
}
