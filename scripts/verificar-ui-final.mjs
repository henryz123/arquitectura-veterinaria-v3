/**
 * Verificación estática sin dependencias ni acceso a PostgreSQL.
 * No reemplaza ng build, pruebas Java ni Playwright.
 * Uso: node scripts/verificar-ui-final.mjs
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = (relative) => fs.readFileSync(path.join(root, relative), 'utf8');
const angular = JSON.parse(read('frontend/angular.json'));
const styles = angular.projects.frontend.architect.build.options.styles;
assert(styles.includes('src/styles.scss'), 'Se perdió la hoja original de estilos');
assert(styles.includes('src/styles-v2.scss'), 'La hoja del rediseño no está registrada en angular.json');
const design = read('frontend/src/styles-v2.scss');
for (const section of ['.workspace-shell','.client-shell','.login-shell','.storefront-shell','.orders-shell','.notifications-shell','.care-layout']) {
  assert(design.includes(section), `Falta estilo para ${section}`);
}
const orders = read('frontend/src/app/features/storefront/online-order-management-page.component.html');
assert(orders.includes('visibleOrders()') && orders.includes('orderSearch.set('), 'Falta filtro de pedidos');
const owners = read('frontend/src/app/features/care/owner-pet-care-page.component.html');
assert(owners.includes('visibleOwners()') && owners.includes('ownerSearch.set('), 'Falta filtro de propietarios');
const notifications = read('frontend/src/app/features/notifications/notifications-page.component.html');
assert(notifications.includes('visibleItems()') && notifications.includes('onlyUnread.set('), 'Falta filtro de notificaciones');
const saas = read('frontend/src/app/features/saas/saas-login-page.component.html');
assert(saas.includes('saas-brand-rail'), 'La pantalla SaaS no incorpora la nueva composición');
const migrations = fs.readdirSync(path.join(root,'database/migration')).filter(x=>/^V\d+__.+\.sql$/i.test(x));
assert.equal(migrations.length, 34, 'Número inesperado de migraciones: revisar antes de ejecutar');
console.log('PASS: estilos V2 enlazados, rutas visuales, tres filtros, pantalla SaaS y 34 migraciones conservadas.');
console.log('Esta comprobación no sustituye ng build, pruebas E2E o validación de PostgreSQL.');
