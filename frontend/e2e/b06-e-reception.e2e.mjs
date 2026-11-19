import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { chromium } from 'playwright-core';

const required = [
  'AV_DEMO_RECEPTION_AURORA_EMAIL',
  'AV_DEMO_RECEPTION_AURORA_PASSWORD',
  'AV_E2E_BASE_URL',
  'AV_E2E_CHROME_PATH',
];
for (const key of required) {
  if (!process.env[key]) throw new Error(`Falta la variable local B06-E: ${key}.`);
}

const baseUrl = process.env.AV_E2E_BASE_URL.replace(/\/$/, '');
const browser = await chromium.launch({ executablePath: process.env.AV_E2E_CHROME_PATH, headless: true });

try {
  await mkdir('artifacts/b06-e', { recursive: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error' && !/status of 403 \(Forbidden\)/.test(message.text())) {
      pageErrors.push(message.text());
    }
  });

  await page.goto(`${baseUrl}/login/veterinaria-aurora`, { waitUntil: 'domcontentloaded' });
  await page.getByLabel('Correo institucional').fill(process.env.AV_DEMO_RECEPTION_AURORA_EMAIL);
  await page.getByLabel(/Contrase/).fill(process.env.AV_DEMO_RECEPTION_AURORA_PASSWORD);
  await page.getByRole('button', { name: 'Iniciar sesión' }).click();
  await page.waitForURL(`${baseUrl}/workspace`);
  await page.getByRole('heading', { name: /Funciones autorizadas para Recepción/ }).waitFor();

  await assertLink(page, 'Clientes y mascotas');
  await assertLink(page, 'Citas y agenda');
  assert.equal(await page.getByRole('link', { name: 'Productos e inventario', exact: true }).count(), 0,
    'RECEPCION no debe recibir navegación de inventario.');
  assert.equal(await page.getByRole('link', { name: 'Pedidos online', exact: true }).count(), 0,
    'RECEPCION no debe recibir navegación comercial.');

  assert.equal((await browserFetch(page, '/api/v1/schedule/requests')).status, 200,
    'RECEPCION debe consultar la agenda tenant-local autorizada.');
  assert.equal((await browserFetch(page, '/api/v1/owners')).status, 200,
    'RECEPCION debe consultar el registro de clientes y mascotas autorizado.');
  assert.equal((await browserFetch(page, '/api/v1/catalog/products')).status, 403,
    'RECEPCION no debe consultar el catálogo interno.');
  assert.equal((await browserFetch(page, '/api/v1/online-orders/management')).status, 403,
    'RECEPCION no debe administrar pedidos online.');
  assert.equal((await browserFetch(page, '/api/v1/audit-trail?from=2026-01-01&to=2030-12-31&module=ALL&page=0&size=10')).status, 403,
    'RECEPCION no debe consultar la auditoría administrativa.');

  await page.getByRole('link', { name: 'Citas y agenda', exact: true }).first().click();
  await page.waitForURL(`${baseUrl}/schedule`);
  await page.getByRole('heading', { name: 'Preparar horarios reales' }).waitFor();
  await page.goto(`${baseUrl}/workspace`, { waitUntil: 'domcontentloaded' });
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: /Funciones autorizadas para Recepción/ }).waitFor();

  await page.setViewportSize({ width: 390, height: 844 });
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: /Funciones autorizadas para Recepción/ }).waitFor();
  const layout = await page.evaluate(() => ({ scrollWidth: document.documentElement.scrollWidth, width: window.innerWidth }));
  assert.ok(layout.scrollWidth <= layout.width, 'El panel RECEPCION no debe desbordar horizontalmente en móvil.');
  await page.screenshot({ path: 'artifacts/b06-e/reception-panel-mobile.png', fullPage: true });
  assert.deepEqual(pageErrors, [], `Errores Angular inesperados: ${pageErrors.join(' | ')}`);

  await page.getByRole('button', { name: 'Cerrar sesión' }).click();
  await page.waitForURL(`${baseUrl}/login/veterinaria-aurora`);
  assert.equal((await browserFetch(page, '/api/v1/session-context')).status, 401,
    'Cerrar sesión debe revocar la sesión de RECEPCION.');
  await context.close();
  process.stdout.write('B06_E_RECEPTION_BROWSER_E2E=PASS\n');
} finally {
  await browser.close();
}

async function assertLink(page, name) {
  await page.getByRole('link', { name, exact: true }).first().waitFor();
}

async function browserFetch(page, path) {
  return page.evaluate(async (requestPath) => ({ status: (await fetch(requestPath)).status }), path);
}
