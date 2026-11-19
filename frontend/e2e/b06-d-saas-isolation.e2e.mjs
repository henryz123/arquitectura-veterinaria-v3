import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { chromium } from 'playwright-core';

const required = ['AV_DEMO_SAAS_EMAIL', 'AV_DEMO_SAAS_PASSWORD', 'AV_E2E_BASE_URL', 'AV_E2E_CHROME_PATH'];
for (const key of required) {
  if (!process.env[key]) throw new Error(`Falta la variable local B06-D: ${key}`);
}

const baseUrl = process.env.AV_E2E_BASE_URL.replace(/\/$/, '');
const browser = await chromium.launch({ executablePath: process.env.AV_E2E_CHROME_PATH, headless: true });

try {
  await mkdir('artifacts/b06-d', { recursive: true });
  const context = await browser.newContext({ viewport: { width: 1280, height: 840 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));

  await page.goto(`${baseUrl}/saas/login`, { waitUntil: 'networkidle' });
  await page.getByLabel('Correo administrativo').fill(process.env.AV_DEMO_SAAS_EMAIL);
  await page.getByLabel('Contraseña').fill(process.env.AV_DEMO_SAAS_PASSWORD);
  await page.getByRole('button', { name: 'Ingresar a administración' }).click();
  await page.waitForURL(`${baseUrl}/saas/console`);
  await page.getByRole('heading', { name: 'Organizaciones y revisiones' }).waitFor();
  await page.getByRole('table', { name: 'Organizaciones disponibles para administración SaaS' }).waitFor();

  assert.equal(await page.getByRole('link', { name: 'Citas y agenda', exact: true }).count(), 0,
    'la consola SaaS no debe presentar navegación operativa tenant-local');
  assert.equal((await browserFetch(page, '/api/v1/online-orders/management')).status, 403,
    'una sesión SaaS no debe administrar pedidos tenant-locales');
  assert.equal((await browserFetch(page, '/api/v1/audit-trail?from=2026-01-01&to=2030-12-31&module=ALL&page=0&size=10')).status, 403,
    'una sesión SaaS no debe consultar la auditoría interna tenant-local');

  await page.reload({ waitUntil: 'networkidle' });
  await page.waitForURL(`${baseUrl}/saas/console`);
  await page.getByRole('heading', { name: 'Organizaciones y revisiones' }).waitFor();
  await page.screenshot({ path: 'artifacts/b06-d/saas-console-isolation.png', fullPage: true });
  assert.deepEqual(pageErrors, [], `Errores Angular inesperados: ${pageErrors.join(' | ')}`);

  await page.getByRole('button', { name: 'Cerrar sesión' }).click();
  await page.waitForURL(`${baseUrl}/saas/login`);
  assert.equal((await browserFetch(page, '/api/v1/session-context')).status, 401,
    'el cierre de sesión SaaS debe revocar la sesión');
  await context.close();
  process.stdout.write('B06_D_SAAS_ISOLATION_BROWSER_E2E=PASS\n');
} finally {
  await browser.close();
}

async function browserFetch(page, path) {
  return page.evaluate(async (requestPath) => {
    const response = await fetch(requestPath);
    return { status: response.status };
  }, path);
}
