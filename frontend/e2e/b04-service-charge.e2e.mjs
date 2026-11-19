import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { chromium } from 'playwright-core';

const local = Object.fromEntries(readFileSync('../.env.local', 'utf8').split(/\r?\n/).flatMap((line) => {
  const separator = line.indexOf('=');
  return separator > 0 && !line.startsWith('#') ? [[line.slice(0, separator), line.slice(separator + 1)]] : [];
}));
for (const name of ['AV_DEMO_AURORA_PASSWORD', 'AV_DEMO_BOSQUE_PASSWORD', 'AV_DEMO_CASHIER_AURORA_EMAIL', 'AV_DEMO_CASHIER_AURORA_PASSWORD']) {
  if (!local[name]) throw new Error(`Falta la variable local ${name}.`);
}

const baseUrl = (process.env.AV_E2E_BASE_URL ?? 'http://127.0.0.1:4200').replace(/\/$/, '');
const chromePath = process.env.AV_E2E_CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const serviceSku = 'B04-E2E-RF015-SERVICE-001';
const stockSku = 'B04-E2E-RF015-PRODUCT-001';
const browser = await chromium.launch({ executablePath: chromePath, headless: true });

try {
  const adminContext = await browser.newContext();
  const admin = await adminContext.newPage();
  await loginStaff(admin, 'veterinaria-aurora', 'administracion@demo.invalid', local.AV_DEMO_AURORA_PASSWORD);
  const service = await ensureProduct(admin, {
    sku: serviceSku, title: 'Servicio sintético cobro de prestación B04', price: '18.00', inventoryManaged: false,
  });
  const stockProduct = await ensureProduct(admin, {
    sku: stockSku, title: 'Producto sintético venta normal B04', price: '7.00', inventoryManaged: true,
  });
  await ensureStock(admin, stockProduct, 2);
  await adminContext.close();

  const cashierContext = await browser.newContext();
  const cashier = await cashierContext.newPage();
  await loginStaff(cashier, 'veterinaria-aurora', local.AV_DEMO_CASHIER_AURORA_EMAIL, local.AV_DEMO_CASHIER_AURORA_PASSWORD);
  await cashier.goto(`${baseUrl}/pos`, { waitUntil: 'networkidle' });
  await cashier.getByRole('heading', { name: 'Caja y correcciones comerciales' }).waitFor();
  const eligible = await api(cashier, '/api/v1/pos/service-charges/eligible');
  assert.equal(eligible.status, 200, 'La caja debe obtener prestaciones elegibles del tenant actual.');
  assert.ok(eligible.body.data.length > 0, 'La prueba requiere una prestación finalizada sin cobro vigente.');
  const prestation = eligible.body.data[0];

  const serviceRow = cashier.locator('.product-row', { hasText: serviceSku });
  await serviceRow.getByRole('button', { name: 'Agregar' }).click();
  const cartLine = cashier.locator('.cart-line', { hasText: serviceSku });
  await cartLine.getByRole('checkbox').first().check();
  const confirmedCharge = cashier.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.includes('/confirmations'));
  await cashier.getByRole('button', { name: 'Confirmar venta' }).click();
  const chargedResponse = await confirmedCharge;
  assert.equal(chargedResponse.status(), 200, 'El cobro de una prestación completada debe confirmarse una sola vez.');
  const chargedSale = (await chargedResponse.json()).data;
  assert.equal(chargedSale.serviceCharges.length, 1, 'El comprobante debe contener la relación persistida.');
  assert.equal(chargedSale.serviceCharges[0].prestationId, prestation.prestationId);
  await cashier.getByRole('heading', { name: 'Prestaciones vinculadas' }).waitFor();
  await cashier.reload({ waitUntil: 'networkidle' });
  await cashier.locator(`[data-pos-sale-id="${chargedSale.id}"]`).getByRole('button', { name: 'Ver comprobante' }).click();
  await cashier.getByRole('heading', { name: 'Prestaciones vinculadas' }).waitFor();

  const afterCharge = await api(cashier, '/api/v1/pos/service-charges/eligible');
  assert.equal(afterCharge.status, 200);
  assert.equal(afterCharge.body.data.some((candidate) => candidate.prestationType === prestation.prestationType
    && candidate.prestationId === prestation.prestationId), false,
  'La misma prestación no debe volver a estar disponible para un segundo cobro.');

  const physicalRow = cashier.locator('.product-row', { hasText: stockSku });
  await physicalRow.getByRole('button', { name: 'Agregar' }).click();
  const confirmedNormal = cashier.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.includes('/confirmations'));
  await cashier.getByRole('button', { name: 'Confirmar venta' }).click();
  const normalResponse = await confirmedNormal;
  assert.equal(normalResponse.status(), 200, 'Una venta habitual de producto debe conservarse sin prestación vinculada.');
  const normalSale = (await normalResponse.json()).data;
  assert.equal(normalSale.serviceCharges.length, 0, 'La referencia a prestación sigue siendo opcional.');
  await cashier.reload({ waitUntil: 'networkidle' });
  await cashier.locator(`[data-pos-sale-id="${normalSale.id}"]`).getByRole('button', { name: 'Ver comprobante' }).click();
  assert.equal(await cashier.getByRole('heading', { name: 'Prestaciones vinculadas' }).count(), 0,
    'Un comprobante de producto no debe presentar una prestación inexistente.');

  await cashier.locator(`[data-pos-sale-id="${chargedSale.id}"]`).getByRole('button', { name: 'Corregir' }).click();
  await cashier.locator('#correction-reason').fill('Anulación sintética controlada de cobro RF-B04-015');
  const cancelled = cashier.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith('/cancellations'));
  await cashier.getByRole('button', { name: 'Anular venta' }).click();
  assert.equal((await cancelled).status(), 200, 'La anulación documentada debe conservar la trazabilidad del vínculo.');
  await cashier.reload({ waitUntil: 'networkidle' });
  const cancelledRow = cashier.locator(`[data-pos-sale-id="${chargedSale.id}"]`);
  assert.match(await cancelledRow.innerText(), /ANULADA/, 'La corrección comercial debe persistir después de recargar.');
  await cancelledRow.getByRole('button', { name: 'Ver comprobante' }).click();
  await cashier.getByText('Cobro anulado').waitFor();
  const afterCancellation = await api(cashier, '/api/v1/pos/service-charges/eligible');
  assert.equal(afterCancellation.body.data.some((candidate) => candidate.prestationType === prestation.prestationType
    && candidate.prestationId === prestation.prestationId), true,
  'Solo una anulación comercial auditada vuelve a habilitar la prestación para una corrección posterior.');
  await cashierContext.close();

  const bosqueContext = await browser.newContext();
  const bosque = await bosqueContext.newPage();
  await loginStaff(bosque, 'veterinaria-bosque', 'administracion@demo.invalid', local.AV_DEMO_BOSQUE_PASSWORD);
  const foreignCandidates = await api(bosque, '/api/v1/pos/service-charges/eligible');
  assert.equal(foreignCandidates.status, 403, 'Una cuenta Bosque sin rol de caja no puede enumerar prestaciones de Aurora.');
  const foreignSale = await api(bosque, `/api/v1/pos/sales/${chargedSale.id}`);
  assert.ok([403, 404].includes(foreignSale.status), 'Bosque no puede consultar una venta Aurora vinculada.');
  await bosqueContext.close();
  process.stdout.write('B04_SERVICE_CHARGE_BROWSER_E2E=PASS\n');
} finally {
  await browser.close();
}

async function ensureProduct(page, fixture) {
  await page.goto(`${baseUrl}/inventory`, { waitUntil: 'networkidle' });
  let products = (await api(page, '/api/v1/catalog/products')).body.data;
  let product = products.find((item) => item.sku === fixture.sku);
  if (!product) {
    await page.locator('#product-sku').fill(fixture.sku);
    await page.locator('#product-title-input').fill(fixture.title);
    await page.locator('#product-price').fill(fixture.price);
    await page.locator('#product-status').selectOption('ACTIVO_NO_PUBLICADO');
    const inventoryManaged = page.locator('#inventory-managed');
    if ((await inventoryManaged.isChecked()) !== fixture.inventoryManaged) await inventoryManaged.click();
    const created = page.waitForResponse((response) => response.request().method() === 'POST'
      && new URL(response.url()).pathname === '/api/v1/catalog/products');
    await page.getByRole('button', { name: 'Registrar producto' }).click();
    assert.equal((await created).status(), 201, 'El fixture de B04 debe crearse con el formulario Angular real.');
    products = (await api(page, '/api/v1/catalog/products')).body.data;
    product = products.find((item) => item.sku === fixture.sku);
  }
  assert.ok(product?.id, `Debe persistir el producto sintético ${fixture.sku}.`);
  assert.equal(product.inventoryManaged, fixture.inventoryManaged);
  return product;
}

async function ensureStock(page, product, expectedQuantity) {
  const balances = (await api(page, '/api/v1/inventory/stock')).body.data;
  const current = balances.find((balance) => balance.productId === product.id)?.availableQuantity ?? 0;
  const delta = expectedQuantity - current;
  if (delta === 0) return;
  await page.locator('#stock-product').selectOption(product.id);
  await page.locator('#stock-quantity').fill(String(delta));
  await page.locator('#stock-reason').fill('Saldo sintético RF-B04-015');
  const adjusted = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname.endsWith(`/inventory/stock/products/${product.id}/adjustments`));
  await page.getByRole('button', { name: 'Registrar movimiento' }).click();
  assert.equal((await adjusted).status(), 201, 'El saldo inicial debe persistir mediante el formulario Angular.');
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
