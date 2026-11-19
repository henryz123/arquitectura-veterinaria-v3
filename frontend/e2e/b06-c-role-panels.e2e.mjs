import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { chromium } from 'playwright-core';

const required = [
  'AV_DEMO_AURORA_PASSWORD',
  'AV_DEMO_VETERINARIO_AURORA_EMAIL',
  'AV_DEMO_VETERINARIO_AURORA_PASSWORD',
  'AV_DEMO_GROOMER_AURORA_EMAIL',
  'AV_DEMO_GROOMER_AURORA_PASSWORD',
  'AV_DEMO_CASHIER_AURORA_EMAIL',
  'AV_DEMO_CASHIER_AURORA_PASSWORD',
  'AV_DEMO_CLIENT_AURORA_EMAIL',
  'AV_DEMO_CLIENT_AURORA_PASSWORD',
  'AV_E2E_CHROME_PATH',
];
for (const key of required) {
  if (!process.env[key]) throw new Error(`Missing local B06-C environment variable: ${key}`);
}

const baseUrl = process.env.AV_E2E_BASE_URL.replace(/\/$/, '');
const browser = await chromium.launch({ executablePath: process.env.AV_E2E_CHROME_PATH, headless: true });

try {
  await mkdir('artifacts/b06-c', { recursive: true });
  const pageErrors = [];

  const admin = await createContext(pageErrors, { width: 1440, height: 900 });
  await loginStaff(admin.page, 'administracion@demo.invalid', process.env.AV_DEMO_AURORA_PASSWORD);
  await admin.page.getByRole('heading', { name: /Funciones autorizadas para Administrador/ }).waitFor();
  await assertLink(admin.page, 'Citas y agenda');
  await assertLink(admin.page, 'Productos e inventario');
  await assertLink(admin.page, 'Pedidos online');
  assert.equal((await browserFetch(admin.page, '/api/v1/schedule/my-agenda')).status, 403,
    'an administrator must not read a veterinarian own-agenda projection');
  await admin.page.screenshot({ path: 'artifacts/b06-c/admin-panel.png', fullPage: true });

  const veterinarian = await createContext(pageErrors, { width: 1280, height: 840 });
  await loginStaff(veterinarian.page, process.env.AV_DEMO_VETERINARIO_AURORA_EMAIL, process.env.AV_DEMO_VETERINARIO_AURORA_PASSWORD);
  await veterinarian.page.getByRole('heading', { name: /Funciones autorizadas para Veterinario/ }).waitFor();
  await veterinarian.page.getByRole('heading', { name: 'Mi agenda clínica' }).waitFor();
  assert.equal(await veterinarian.page.getByRole('link', { name: 'Citas y agenda', exact: true }).count(), 0,
    'a veterinarian must not receive the agenda-management link');
  const ownAgenda = await browserFetch(veterinarian.page, '/api/v1/schedule/my-agenda');
  assert.equal(ownAgenda.status, 200, 'the assigned veterinarian must read their own agenda');
  for (const item of ownAgenda.body.data) {
    assert.deepEqual(Object.keys(item).sort(), ['endsAt', 'petName', 'requestId', 'reservationId', 'serviceName', 'startsAt', 'status'],
      'the own-agenda projection must not expose owner contacts or clinical narrative');
  }
  await veterinarian.page.screenshot({ path: 'artifacts/b06-c/veterinarian-panel.png', fullPage: true });

  const grooming = await createContext(pageErrors, { width: 768, height: 1024 });
  await loginStaff(grooming.page, process.env.AV_DEMO_GROOMER_AURORA_EMAIL, process.env.AV_DEMO_GROOMER_AURORA_PASSWORD);
  await grooming.page.getByRole('heading', { name: /Funciones autorizadas para Grooming/ }).waitFor();
  await assertLink(grooming.page, 'Peluquería y baño');
  assert.equal(await grooming.page.getByRole('link', { name: 'Clientes y mascotas', exact: true }).count(), 0,
    'a grooming worker must not receive the clinical client-management link');
  assert.equal((await browserFetch(grooming.page, '/api/v1/grooming/tasks')).status, 200,
    'a grooming worker must keep access to own assigned tasks');
  await grooming.page.screenshot({ path: 'artifacts/b06-c/grooming-panel-tablet.png', fullPage: true });

  const cashier = await createContext(pageErrors, { width: 390, height: 844 });
  await loginStaff(cashier.page, process.env.AV_DEMO_CASHIER_AURORA_EMAIL, process.env.AV_DEMO_CASHIER_AURORA_PASSWORD);
  await cashier.page.getByRole('heading', { name: /Funciones autorizadas para Caja/ }).waitFor();
  await assertLink(cashier.page, 'Punto de venta');
  await assertLink(cashier.page, 'Pedidos online');
  assert.equal(await cashier.page.getByRole('link', { name: 'Clientes y mascotas', exact: true }).count(), 0,
    'a cashier must not receive the clinical client-management link');
  assert.equal((await browserFetch(cashier.page, '/api/v1/online-orders/management')).status, 200,
    'a cashier must keep access to the tenant-local commercial queue');
  await cashier.page.screenshot({ path: 'artifacts/b06-c/cashier-panel-mobile.png', fullPage: true });

  const client = await createContext(pageErrors, { width: 1280, height: 840 });
  await loginClient(client.page);
  await client.page.getByRole('heading', { level: 1 }).waitFor();
  assert.equal((await browserFetch(client.page, '/api/v1/audit-trail?from=2026-01-01&to=2030-12-31&module=ALL&page=0&size=10')).status, 403,
    'a client must not access the administrative audit route');
  assert.equal((await browserFetch(client.page, '/api/v1/online-orders/management')).status, 403,
    'a client must not access the commercial management route');

  assert.deepEqual(pageErrors, [], `Angular runtime errors: ${pageErrors.join(' | ')}`);
  await Promise.all([admin.context.close(), veterinarian.context.close(), grooming.context.close(), cashier.context.close(), client.context.close()]);
  process.stdout.write('B06_C_ROLE_PANELS_BROWSER_E2E=PASS\n');
} finally {
  await browser.close();
}

async function createContext(pageErrors, viewport) {
  const context = await browser.newContext({ viewport });
  const page = await context.newPage();
  page.on('pageerror', (error) => pageErrors.push(error.message));
  page.on('console', (message) => {
    const text = message.text();
    // Las tres denegaciones 403 se provocan intencionalmente con browserFetch
    // para comprobar que el backend, y no solo el menú, aplica las restricciones.
    if (message.type() === 'error' && !/status of 403 \(Forbidden\)/.test(text)) pageErrors.push(text);
  });
  return { context, page };
}

async function loginStaff(page, email, password) {
  await page.goto(`${baseUrl}/login/veterinaria-aurora`, { waitUntil: 'domcontentloaded' });
  await page.getByLabel('Correo institucional').fill(email);
  await page.getByLabel(/Contrase/).fill(password);
  await page.getByRole('button', { name: 'Iniciar sesión' }).click();
  await page.waitForURL(`${baseUrl}/workspace`);
}

async function loginClient(page) {
  await page.goto(`${baseUrl}/client/login/veterinaria-aurora`, { waitUntil: 'domcontentloaded' });
  await page.getByLabel('Correo de cliente').fill(process.env.AV_DEMO_CLIENT_AURORA_EMAIL);
  await page.getByLabel(/Contrase/).fill(process.env.AV_DEMO_CLIENT_AURORA_PASSWORD);
  await page.getByRole('button', { name: 'Iniciar sesión' }).click();
  await page.waitForURL(`${baseUrl}/client-space`);
}

async function assertLink(page, name) {
  await page.getByRole('link', { name, exact: true }).first().waitFor();
}

async function browserFetch(page, path) {
  return page.evaluate(async (requestPath) => {
    const response = await fetch(requestPath);
    const text = await response.text();
    return { status: response.status, body: text ? JSON.parse(text) : null };
  }, path);
}
