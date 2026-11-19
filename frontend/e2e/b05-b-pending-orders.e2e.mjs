import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { chromium } from 'playwright-core';

for (const key of ['AV_DEMO_AURORA_PASSWORD','AV_DEMO_BOSQUE_PASSWORD','AV_DEMO_CLIENT_AURORA_EMAIL','AV_DEMO_CLIENT_AURORA_PASSWORD','AV_DEMO_CLIENT_BOSQUE_EMAIL','AV_DEMO_CLIENT_BOSQUE_PASSWORD','AV_E2E_CHROME_PATH']) if (!process.env[key]) throw new Error(`Missing local E2E variable: ${key}`);
const baseUrl=(process.env.AV_E2E_BASE_URL ?? 'http://[::1]:4200').replace(/\/$/,'');
const staffEmail=process.env.AV_E2E_AURORA_EMAIL ?? 'administracion@demo.invalid';
const browser=await chromium.launch({executablePath:process.env.AV_E2E_CHROME_PATH,headless:true});
try {
  const admin=await browser.newContext(); const adminPage=await admin.newPage();
  await loginStaff(adminPage,'veterinaria-aurora',staffEmail,process.env.AV_DEMO_AURORA_PASSWORD);
  await adminPage.goto(`${baseUrl}/online-orders/settings`,{waitUntil:'domcontentloaded'});
  await adminPage.getByLabel('Punto de recojo').fill('Sede Aurora - punto de recojo');
  if (!await adminPage.getByLabel('Habilitar delivery').isChecked()) await adminPage.getByLabel('Habilitar delivery').check();
  await adminPage.getByLabel('Tarifa fija (PEN)').fill('9.50'); await adminPage.getByLabel('Áreas de cobertura').fill('Distritos sintéticos de cobertura Aurora');
  await adminPage.getByRole('button',{name:'Guardar configuración'}).click();
  await adminPage.getByText('Configuración de delivery actualizada.').waitFor();
  await adminPage.goto(`${baseUrl}/inventory`,{waitUntil:'domcontentloaded'});
  const products=(await browserFetch(adminPage,'/api/v1/catalog/products')).body?.data??[];
  const balances=(await browserFetch(adminPage,'/api/v1/inventory/stock')).body?.data??[];
  const product=products.find(p=>p.status==='ACTIVO_PUBLICADO'&&p.inventoryManaged&&stockFor(balances,p.id)>0);
  assert.ok(product?.id,'Aurora needs an existing published physical product with positive stock');
  const stockBefore=stockFor(balances,product.id);
  const emptyClient=await browser.newContext(); const emptyPage=await emptyClient.newPage();
  await loginClient(emptyPage,'veterinaria-aurora',process.env.AV_DEMO_CLIENT_AURORA_EMAIL,process.env.AV_DEMO_CLIENT_AURORA_PASSWORD);
  await emptyPage.goto(`${baseUrl}/tienda/veterinaria-aurora/checkout`,{waitUntil:'domcontentloaded'});
  await emptyPage.getByText('Tu carrito está vacío.').waitFor();
  assert.equal(await emptyPage.getByRole('button',{name:'Registrar pedido pendiente'}).isDisabled(),true,'an empty cart must not submit');
  await emptyClient.close();
  const client=await browser.newContext({viewport:{width:1280,height:840}}); const page=await client.newPage();
  await page.goto(`${baseUrl}/tienda/veterinaria-aurora`,{waitUntil:'domcontentloaded'});
  await page.locator(`[data-storefront-product-id="${product.id}"]`).getByRole('button',{name:'Añadir al carrito'}).click();
  await page.getByRole('link',{name:'Continuar con el pedido'}).click();
  await page.waitForURL(`${baseUrl}/client/login/veterinaria-aurora?returnTo=%2Ftienda%2Fveterinaria-aurora%2Fcheckout`);
  await page.getByLabel(/Correo/).fill(process.env.AV_DEMO_CLIENT_AURORA_EMAIL); await page.locator('#password').fill(process.env.AV_DEMO_CLIENT_AURORA_PASSWORD); await page.getByRole('button',{name:'Iniciar sesión'}).click();
  await page.waitForURL(`${baseUrl}/tienda/veterinaria-aurora/checkout`); await page.getByLabel('Teléfono de coordinación').fill('999 000 111');
  const created=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname==='/api/v1/online-orders'); await page.getByRole('button',{name:'Registrar pedido pendiente'}).click(); const createdResponse=await created; assert.equal(createdResponse.status(),201,await createdResponse.text());
  await page.getByRole('heading',{name:/Pedido #\d+ registrado/}).waitFor(); const shot='artifacts/b05b/checkout-aurora.png'; await mkdir('artifacts/b05b',{recursive:true}); await page.screenshot({path:shot,fullPage:true});
  await page.getByRole('link',{name:'Ver mis pedidos'}).click(); await page.getByRole('button',{name:'Mis pedidos'}).click(); await page.getByText(/Pedido #\d+ · Pendiente/).first().waitFor(); await page.reload({waitUntil:'domcontentloaded'}); await page.getByRole('button',{name:'Mis pedidos'}).click();
  const own=(await browserFetch(page,'/api/v1/online-orders')).body?.data??[]; const order=own[0]; assert.ok(order?.id,'the client must recover the persisted own order'); assert.equal(order.status,'PENDIENTE'); assert.equal(order.deliveryFeePen,0); assert.equal(order.lines.length,1);
  await page.goto(`${baseUrl}/tienda/veterinaria-aurora`,{waitUntil:'domcontentloaded'});
  await page.locator(`[data-storefront-product-id="${product.id}"]`).getByRole('button',{name:'Añadir al carrito'}).click();
  await page.getByRole('link',{name:'Continuar con el pedido'}).click(); await page.getByLabel('Delivery').check();
  await page.getByLabel('Teléfono de coordinación').fill('999 000 222');
  await page.getByRole('button',{name:'Registrar pedido pendiente'}).click();
  await page.getByText('Completa los campos requeridos para registrar el pedido.').waitFor();
  await page.getByText('Ingresa una dirección de entrega válida.').waitFor();
  await page.getByText(product.title).waitFor();
  await page.getByLabel('Dirección de entrega').fill('Dirección sintética Aurora 123');
  const deliveryCreated=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname==='/api/v1/online-orders'); await page.getByRole('button',{name:'Registrar pedido pendiente'}).click(); const deliveryResponse=await deliveryCreated; assert.equal(deliveryResponse.status(),201,await deliveryResponse.text());
  await page.getByRole('heading',{name:/Pedido #\d+ registrado/}).waitFor(); const deliveryOrders=(await browserFetch(page,'/api/v1/online-orders')).body?.data??[]; const deliveryOrder=deliveryOrders.find(item=>item.deliveryMode==='DELIVERY'); assert.equal(deliveryOrder?.deliveryFeePen,9.5,'the server must snapshot the configured fixed delivery fee'); assert.equal(deliveryOrder?.totalPen,deliveryOrder.subtotalPen+deliveryOrder.deliveryFeePen);
  const stockAfter=stockFor((await browserFetch(adminPage,'/api/v1/inventory/stock')).body?.data??[],product.id); assert.equal(stockAfter,stockBefore,'creating a pending online order must not reserve or decrement stock');
  const bosque=await browser.newContext(); const bosquePage=await bosque.newPage(); await loginClient(bosquePage,'veterinaria-bosque',process.env.AV_DEMO_CLIENT_BOSQUE_EMAIL,process.env.AV_DEMO_CLIENT_BOSQUE_PASSWORD); const foreign=await browserFetch(bosquePage,`/api/v1/online-orders/${order.id}`); assert.equal(foreign.status,404,'a different tenant must not read an Aurora order');
  await bosque.close(); await client.close(); await admin.close(); process.stdout.write('B05_B_PENDING_ORDER_BROWSER_E2E=PASS\n');
} finally { await browser.close(); }
async function loginStaff(page,slug,email,password){await page.goto(`${baseUrl}/login/${slug}`,{waitUntil:'domcontentloaded'});await page.getByLabel('Correo institucional').fill(email);await page.getByLabel(/Contrase/).fill(password);await page.getByRole('button',{name:'Iniciar sesión'}).click();await page.waitForURL(`${baseUrl}/workspace`);}
async function loginClient(page,slug,email,password){await page.goto(`${baseUrl}/client/login/${slug}`,{waitUntil:'domcontentloaded'});await page.getByLabel(/Correo/).fill(email);await page.locator('#password').fill(password);await page.getByRole('button',{name:'Iniciar sesión'}).click();await page.waitForURL(`${baseUrl}/client-space`);}
async function browserFetch(page,path){return page.evaluate(async p=>{const r=await fetch(p);const t=await r.text();return {status:r.status,body:t?JSON.parse(t):null};},path);}
function stockFor(rows,id){return rows.find(x=>x.productId===id)?.availableQuantity??0;}
