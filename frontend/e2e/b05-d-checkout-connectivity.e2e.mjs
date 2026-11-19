import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { chromium } from 'playwright-core';

for (const key of ['AV_DEMO_CLIENT_AURORA_EMAIL', 'AV_DEMO_CLIENT_AURORA_PASSWORD', 'AV_E2E_CHROME_PATH']) {
  if (!process.env[key]) throw new Error(`Missing local B05-D variable: ${key}`);
}

const baseUrl = (process.env.AV_E2E_BASE_URL ?? 'http://127.0.0.1:4200').replace(/\/$/, '');
const browser = await chromium.launch({ executablePath: process.env.AV_E2E_CHROME_PATH, headless: true });

try {
  const context = await browser.newContext({ viewport: { width: 1280, height: 840 } });
  const page = await context.newPage();
  await page.goto(`${baseUrl}/client/login/veterinaria-aurora`, { waitUntil: 'domcontentloaded' });
  await page.getByLabel('Correo de cliente').fill(process.env.AV_DEMO_CLIENT_AURORA_EMAIL);
  await page.locator('#password').fill(process.env.AV_DEMO_CLIENT_AURORA_PASSWORD);
  await page.getByRole('button', { name: 'Iniciar sesión' }).click();
  await page.waitForURL(`${baseUrl}/client-space`);
  await page.goto(`${baseUrl}/tienda/veterinaria-aurora`, { waitUntil: 'domcontentloaded' });
  const addable = page.locator('[data-storefront-product-id] button:not([disabled])').first();
  await addable.waitFor({ state: 'visible' });
  await addable.click();
  await page.getByRole('link', { name: 'Continuar con el pedido' }).click();
  await page.getByLabel('Teléfono de coordinación').fill('999 050 099');
  const submit = page.getByRole('button', { name: 'Registrar pedido pendiente' });
  assert.equal(await submit.isEnabled(), true, 'checkout submit must be enabled before the controlled outage');

  process.stdout.write('B05_D_CHECKOUT_CONNECTIVITY_READY\n');
  process.stdin.resume();
  await new Promise((resolve) => process.stdin.once('data', resolve));

  await submit.click();
  await page.getByText('No fue posible conectar con el servidor. Comprueba que la aplicación local esté iniciada e inténtalo nuevamente.').waitFor();
  await page.getByText('No fue posible registrar el pedido. Tus productos siguen en el carrito.').waitFor();
  await page.waitForFunction((button) => !button.disabled, await submit.elementHandle());
  assert.ok(await page.locator('.checkout-shell article').count() > 0, 'a failed request must keep the cart lines visible');
  await mkdir('artifacts/b05-d', { recursive: true });
  await page.screenshot({ path: 'artifacts/b05-d/checkout-connectivity-error.png', fullPage: true });
  await context.close();
  process.stdout.write('B05_D_CHECKOUT_CONNECTIVITY_BROWSER_E2E=PASS\n');
} finally {
  await browser.close();
}
