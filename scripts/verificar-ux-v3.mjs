/**
 * Verificación estructural V3, sin base de datos ni dependencias npm.
 * No sustituye ng build ni los E2E con PostgreSQL.
 */
import { readFileSync, existsSync, readdirSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const read=(name)=>readFileSync(join(root,name),'utf8');
const checks=[];
function verify(name,ok){checks.push({name,ok:!!ok});console.log(`${ok?'PASS':'FAIL'} | ${name}`);}
const cfg=JSON.parse(read('frontend/angular.json'));
const routes=read('frontend/src/app/app.routes.ts');
const selector=read('frontend/src/app/features/access/tenant-selector-page.component.html');
const login=read('frontend/src/app/features/auth/login-page.component.html');
const workspace=read('frontend/src/app/features/workspace/workspace-page.component.html');
const client=read('frontend/src/app/features/client/client-space-page.component.html');
const shop=read('frontend/src/app/features/storefront/storefront-page.component.html');
const shopTs=read('frontend/src/app/features/storefront/storefront-page.component.ts');
const css=read('frontend/src/styles-v3.scss');
const ext=(n)=>existsSync(join(root,n));
verify('Estilo V3 cargado después de las hojas anteriores',cfg.projects.frontend.architect.build.options.styles.at(-1)==='src/styles-v3.scss');
verify('Página de inicio muestra selector', routes.includes("redirectTo: 'seleccionar-veterinaria'"));
verify('Selector con dos rutas y tiendas separadas',selector.includes('selection-layout')&&selector.includes('tenant-option-actions')&&selector.includes("['/login', tenant.slug]")&&selector.includes("['/tienda', tenant.slug]"));
verify('Inicio de sesión con recuperación y alta visibles',login.includes('access-create-account')&&login.includes('access-recover-account'));
verify('Panel STAFF con navegación agrupada y acceso por rol',workspace.includes('sidebar-category')&&workspace.includes('operationalLinks()')&&workspace.includes('canManageStaff()'));
verify('Portal CLIENT con acciones y navegación propias',client.includes('client-welcome')&&client.includes('client-shop-cta'));
verify('Tienda con búsqueda, filtros, orden y estado vacío',shop.includes('storefront-filters')&&shop.includes('store-no-results')&&shopTs.includes('clearCatalogFilters()')&&shopTs.includes('catalogSort'));
verify('Productos, POS y pedidos siguen accesibles', ['/inventory','/pos','/online-orders/management'].every(x=>routes.includes("'"+x.slice(1)+"'")));
verify('Dialogo interno integrado',ext('frontend/src/app/shared/dialog/action-dialog.component.ts'));
verify('Estilos responsive y foco visible',css.includes('max-width: 520px')&&css.includes('focus-visible')&&css.includes('prefers-reduced-motion'));
verify('Icono y fotografías disponibles',['frontend/public/brand-veterinaria.svg','frontend/public/access/aurora-retriever.webp','frontend/public/access/bosque-cat.webp'].every(ext));
const dir=join(root,'database/migration');const migrations=readdirSync(dir).filter(x=>/^V\d+__/i.test(x)&&x.endsWith('.sql'));
verify('34 migraciones presentes, sin V035',migrations.length===34&&migrations.some(x=>x.startsWith('V034__'))&&!migrations.some(x=>x.startsWith('V035__')));
function walk(dir){const entries=[];for(const file of readdirSync(dir,{withFileTypes:true})){const full=join(dir,file.name);if(file.isDirectory())entries.push(...walk(full));else if(/\.ts$/.test(file.name)&&!file.name.endsWith('.spec.ts'))entries.push(full);}return entries;}
const offenders=walk(join(root,'frontend/src/app')).filter(file=>/\b(?:window|globalThis)\.(?:prompt|alert|confirm)\s*\(/.test(readFileSync(file,'utf8')));
verify('Sin alertas, confirmaciones ni prompts nativos en los componentes',offenders.length===0);
verify('Sin secretos .env incluidos',!['.env','.env.local','backend/.env'].some(ext));
console.log(`\n${checks.filter(x=>x.ok).length}/${checks.length} comprobaciones estructurales PASS.`);
if(checks.some(x=>!x.ok))process.exitCode=1;
