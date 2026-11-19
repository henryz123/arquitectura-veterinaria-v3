import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { chromium } from 'playwright-core';

const required = [
  'AV_DEMO_AURORA_PASSWORD', 'AV_DEMO_BOSQUE_PASSWORD',
  'AV_DEMO_CLIENT_AURORA_EMAIL', 'AV_DEMO_CLIENT_AURORA_PASSWORD', 'AV_E2E_CHROME_PATH',
];
for (const key of required) {
  if (!process.env[key]) throw new Error(`Missing required local E2E environment variable: ${key}`);
}

const baseUrl = (process.env.AV_E2E_BASE_URL ?? 'http://127.0.0.1:4200').replace(/\/$/, '');
const staffEmail = process.env.AV_E2E_AURORA_EMAIL ?? 'administracion@demo.invalid';
const auroraFixture = {
  sku: 'B05-A-AURORA-001',
  title: 'Alimento sintético Aurora B05',
  unavailableSku: 'B05-A-AURORA-002',
  unavailableTitle: 'Arena sintética Aurora B05',
  inactiveSku: 'B05-A-AURORA-003',
  inactiveTitle: 'Producto inactivo Aurora B05',
};
const bosqueFixture = { sku: 'B05-A-BOSQUE-001', title: 'Alimento sintético Bosque B05' };
const desktopScreenshot = process.env.AV_B05_A_DESKTOP_SCREENSHOT
  ? resolve(process.env.AV_B05_A_DESKTOP_SCREENSHOT) : null;
const mobileScreenshot = process.env.AV_B05_A_MOBILE_SCREENSHOT
  ? resolve(process.env.AV_B05_A_MOBILE_SCREENSHOT) : null;
const browser = await chromium.launch({ executablePath: process.env.AV_E2E_CHROME_PATH, headless: true });

try {
  const auroraStaff = await browser.newContext();
  const auroraStaffPage = await auroraStaff.newPage();
  await loginStaff(auroraStaffPage, 'veterinaria-aurora', staffEmail, process.env.AV_DEMO_AURORA_PASSWORD);
  await auroraStaffPage.goto(`${baseUrl}/inventory`, { waitUntil: 'networkidle' });
  await auroraStaffPage.getByRole('heading', { name: 'Stock y movimientos' }).waitFor();
  const auroraProduct = await ensureProduct(auroraStaffPage, auroraFixture.sku, auroraFixture.title, 'ACTIVO_PUBLICADO');
  await ensureStock(auroraStaffPage, auroraProduct, 7);
  const unavailableProduct = await ensureProduct(auroraStaffPage, auroraFixture.unavailableSku, auroraFixture.unavailableTitle, 'ACTIVO_PUBLICADO');
  await ensureStock(auroraStaffPage, unavailableProduct, 0);
  const inactiveProduct = await ensureProduct(auroraStaffPage, auroraFixture.inactiveSku, auroraFixture.inactiveTitle, 'INACTIVO');
  const beforeCartBalance = await stockBalanceFor(auroraStaffPage, auroraProduct.id);

  const bosqueStaff = await browser.newContext();
  const bosqueStaffPage = await bosqueStaff.newPage();
  await loginStaff(bosqueStaffPage, 'veterinaria-bosque', staffEmail, process.env.AV_DEMO_BOSQUE_PASSWORD);
  await bosqueStaffPage.goto(`${baseUrl}/inventory`, { waitUntil: 'networkidle' });
  await bosqueStaffPage.getByRole('heading', { name: 'Stock y movimientos' }).waitFor();
  const bosqueProduct = await ensureProduct(bosqueStaffPage, bosqueFixture.sku, bosqueFixture.title, 'ACTIVO_PUBLICADO');
  await ensureStock(bosqueStaffPage, bosqueProduct, 4);

  const visitor = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await visitor.newPage();
  await page.goto(`${baseUrl}/tienda/veterinaria-aurora`, { waitUntil: 'networkidle' });
  await page.getByRole('heading', { name: 'Veterinaria Aurora' }).waitFor();
  const auroraCard = page.locator(`[data-storefront-product-id="${auroraProduct.id}"]`);
  await auroraCard.waitFor();
  assert.equal(await page.locator(`[data-storefront-product-id="${inactiveProduct.id}"]`).count(), 0,
    'an inactive product must not be public');
  const unavailableCard = page.locator(`[data-storefront-product-id="${unavailableProduct.id}"]`);
  await unavailableCard.waitFor();
  await assertDisabled(unavailableCard.getByRole('button', { name: 'Añadir al carrito' }),
    'a zero-stock product must remain informative but cannot enter the provisional cart');
  await auroraCard.getByRole('button', { name: 'Añadir al carrito' }).click();
  const auroraQuantity = page.getByLabel(`Cantidad de ${auroraFixture.title}`);
  await auroraQuantity.fill('3');
  await auroraQuantity.press('Tab');
  await expectInputValue(auroraQuantity, '3');
  await page.reload({ waitUntil: 'networkidle' });
  await expectInputValue(page.getByLabel(`Cantidad de ${auroraFixture.title}`), '3');
  if (desktopScreenshot) await capture(page, desktopScreenshot);

  await page.goto(`${baseUrl}/tienda/veterinaria-bosque`, { waitUntil: 'networkidle' });
  await page.getByRole('heading', { name: 'Veterinaria Bosque' }).waitFor();
  await page.locator(`[data-storefront-product-id="${bosqueProduct.id}"]`).waitFor();
  assert.equal(await page.locator(`[data-storefront-product-id="${auroraProduct.id}"]`).count(), 0,
    'Bosque must not receive an Aurora product through the anonymous storefront');
  await expectText(page.locator('.cart-heading > span'), '0',
    'the Bosque cart must start empty instead of reusing Aurora lines');
  await page.locator(`[data-storefront-product-id="${bosqueProduct.id}"]`).getByRole('button', { name: 'Añadir al carrito' }).click();
  await page.goto(`${baseUrl}/tienda/veterinaria-aurora`, { waitUntil: 'networkidle' });
  await expectInputValue(page.getByLabel(`Cantidad de ${auroraFixture.title}`), '3');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('heading', { name: 'Tu selección' }).scrollIntoViewIfNeeded();
  await page.getByRole('heading', { name: 'Tu selección' }).waitFor();
  if (mobileScreenshot) await capture(page, mobileScreenshot);
  await page.getByRole('button', { name: 'Retirar' }).click();
  await page.getByText('Añade productos disponibles.').waitFor();
  await visitor.close();

  const client = await browser.newContext();
  const clientPage = await client.newPage();
  await clientPage.goto(`${baseUrl}/client/login/veterinaria-aurora`, { waitUntil: 'networkidle' });
  await clientPage.getByLabel('Correo de cliente').fill(process.env.AV_DEMO_CLIENT_AURORA_EMAIL);
  await clientPage.locator('#password').fill(process.env.AV_DEMO_CLIENT_AURORA_PASSWORD);
  await clientPage.getByRole('button', { name: 'Iniciar sesión' }).click();
  await clientPage.waitForURL(`${baseUrl}/client-space`);
  const clientStorefrontLink = clientPage.getByRole('link', { name: 'Tienda' });
  await clientStorefrontLink.waitFor();
  assert.equal(await clientStorefrontLink.getAttribute('href'), '/tienda/veterinaria-aurora',
    'the CLIENT navigation must retain the tenant slug');
  await clientStorefrontLink.click();
  await clientPage.waitForURL(`${baseUrl}/tienda/veterinaria-aurora`);
  await clientPage.getByRole('heading', { name: 'Veterinaria Aurora' }).waitFor();
  await client.close();

  const afterCartBalance = await stockBalanceFor(auroraStaffPage, auroraProduct.id);
  assert.equal(afterCartBalance, beforeCartBalance, 'adding, changing, and removing cart lines must not alter stock');
  await auroraStaff.close();
  await bosqueStaff.close();
  process.stdout.write('B05_A_STOREFRONT_CART_BROWSER_E2E=PASS\n');
} finally {
  await browser.close();
}

async function ensureProduct(page, sku, title, status) {
  let products = (await browserFetch(page, '/api/v1/catalog/products')).body?.data ?? [];
  let product = products.find((item) => item.sku === sku);
  if (!product) {
    const created = page.waitForResponse((response) => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/v1/catalog/products');
    await page.locator('#product-sku').fill(sku);
    await page.locator('#product-title-input').fill(title);
    await page.locator('#product-price').fill('24.50');
    await page.locator('#product-status').selectOption(status);
    if (!await page.locator('#inventory-managed').isChecked()) await page.locator('#inventory-managed').check();
    await page.getByRole('button', { name: 'Registrar producto' }).click();
    assert.equal((await created).status(), 201, 'the visible inventory form must create the B05-A fixture');
  }
  products = (await browserFetch(page, '/api/v1/catalog/products')).body?.data ?? [];
  product = products.find((item) => item.sku === sku);
  assert.ok(product?.id, `fixture ${sku} must persist in the current tenant`);
  if (product.title !== title || product.status !== status || !product.inventoryManaged) {
    const row = page.locator(`[data-product-id="${product.id}"]`);
    await row.getByRole('button', { name: 'Editar' }).click();
    await page.locator('#product-title-input').fill(title);
    await page.locator('#product-price').fill('24.50');
    await page.locator('#product-status').selectOption(status);
    if (!await page.locator('#inventory-managed').isChecked()) await page.locator('#inventory-managed').check();
    const updated = page.waitForResponse((response) => response.request().method() === 'PUT'
      && new URL(response.url()).pathname.endsWith(`/catalog/products/${product.id}`));
    await page.getByRole('button', { name: 'Guardar producto' }).click();
    assert.equal((await updated).status(), 200, 'the fixture must be normalized through the visible catalog form');
    products = (await browserFetch(page, '/api/v1/catalog/products')).body?.data ?? [];
    product = products.find((item) => item.sku === sku);
  }
  return product;
}

async function ensureStock(page, product, targetQuantity) {
  const currentQuantity = await stockBalanceFor(page, product.id);
  const delta = targetQuantity - currentQuantity;
  if (delta === 0) return;
  await page.locator('#stock-product option', { hasText: product.sku }).waitFor({ state: 'attached' });
  await page.locator('#stock-product').selectOption(product.id);
  await page.locator('#stock-quantity').fill(String(delta));
  await page.locator('#stock-reason').fill('Saldo sintético controlado B05-A');
  const adjusted = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith(`/inventory/stock/products/${product.id}/adjustments`));
  await page.getByRole('button', { name: 'Registrar movimiento' }).click();
  assert.equal((await adjusted).status(), 201, 'the visible stock form must set the B05-A fixture balance');
}

async function stockBalanceFor(page, productId) {
  const balances = (await browserFetch(page, '/api/v1/inventory/stock')).body?.data ?? [];
  return balances.find((item) => item.productId === productId)?.availableQuantity ?? 0;
}

async function loginStaff(page, tenantSlug, email, password) {
  await page.goto(`${baseUrl}/login/${tenantSlug}`, { waitUntil: 'networkidle' });
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

async function expectInputValue(locator, expected) {
  await locator.waitFor();
  assert.equal(await locator.inputValue(), expected);
}

async function assertDisabled(locator, message) {
  assert.equal(await locator.isDisabled(), true, message);
}

async function expectText(locator, expected, message) {
  await locator.waitFor();
  assert.equal((await locator.textContent())?.trim(), expected, message);
}

async function capture(page, path) {
  await mkdir(dirname(path), { recursive: true });
  await page.screenshot({ path, fullPage: true });
}
