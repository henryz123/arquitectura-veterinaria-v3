import { resolve, relative, sep } from 'node:path';
import { pathToFileURL } from 'node:url';

const target = process.argv[2];
if (!target) {
  throw new Error('Indica el archivo E2E que debe ejecutarse.');
}

const baseUrl = new URL(process.env.AV_E2E_BASE_URL ?? 'http://127.0.0.1:4200');
const allowedHosts = new Set(['127.0.0.1', 'localhost', '::1', '[::1]']);
if (baseUrl.protocol !== 'http:' || !allowedHosts.has(baseUrl.hostname)
  || baseUrl.port !== '4200' || baseUrl.pathname !== '/' || baseUrl.search || baseUrl.hash) {
  throw new Error('Las pruebas E2E sintéticas solo pueden ejecutarse contra http://127.0.0.1:4200, http://localhost:4200 o http://[::1]:4200.');
}

const e2eRoot = resolve('e2e');
const absoluteTarget = resolve(target);
const relativeTarget = relative(e2eRoot, absoluteTarget);
if (relativeTarget.startsWith('..') || relativeTarget.includes(`..${sep}`) || !absoluteTarget.endsWith('.e2e.mjs')) {
  throw new Error('El lanzador E2E solo acepta archivos de prueba dentro de frontend/e2e.');
}

process.env.AV_E2E_BASE_URL = baseUrl.origin;
await import(pathToFileURL(absoluteTarget).href);
