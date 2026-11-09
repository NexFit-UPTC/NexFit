// Auditoría de desbordamiento horizontal (NEX-65, RNF-12): recorre las pantallas de un rol con la API simulada y
// avisa si la página hace scroll horizontal al ancho indicado.
// Uso (con `npm run build` hecho en frontend/ y Playwright instalado):  node auditoria-360px.js <admin|staff|client|trainer|master> [ancho=360]
// Ajusta `root` si tu carpeta del build es otra. Las tablas largas se desplazan dentro de su envoltorio, no la página.
const { chromium } = require('playwright');
const http = require('http'), fs = require('fs'), path = require('path');
const root = path.join(__dirname, '../../frontend/dist/nexfit/browser');
const types = { '.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.ico': 'image/x-icon', '.json': 'application/json' };
const srv = http.createServer((req, res) => {
  let p = path.join(root, decodeURIComponent(req.url.split('?')[0]));
  if (!fs.existsSync(p) || fs.statSync(p).isDirectory()) p = path.join(root, 'index.html');
  res.writeHead(200, { 'content-type': types[path.extname(p)] || 'application/octet-stream' });
  fs.createReadStream(p).pipe(res);
});
const role = process.argv[2] || 'admin';
const W = Number(process.argv[3] || 360);
const plans = [['Pase diario', 8000, 1, true], ['Mensual', 90000, 30, true], ['Trimestral', 240000, 90, true], ['Anual', 800000, 365, true], ['Promo verano', 60000, 30, false]]
  .map(([name, price, d, active], i) => ({ id: i + 1, name, description: null, price, durationDays: d, benefits: 'Acceso a todas las sedes', active }));
const branches = [{ id: 1, name: 'Sede Centro', phone: '3100000000', schedule: 'L-V 5am-9pm', addressLine1: 'Carrera 5 # 10-20', addressLine2: null, city: 'Tunja', department: 'Boyacá', postalCode: null, country: 'Colombia', active: true },
  { id: 2, name: 'Sede Norte', phone: null, schedule: null, addressLine1: 'Carrera 6 # 10-21', addressLine2: null, city: 'Tunja', department: 'Boyacá', postalCode: null, country: 'Colombia', active: false }];
const staff = [['Paola', 'Mendoza', ['staff'], null, true], ['Mariana', 'Ospina', ['trainer'], 'Fuerza e hipertrofia', true], ['Esteban', 'Quiroga', ['trainer', 'staff'], 'Funcional y cardio', false]]
  .map(([f, l, roles, sp, active], i) => ({ memberId: i + 1, userId: i + 1, documentType: 'CC', documentNumber: 'DEMO-' + i, firstName: f, lastName: l, birthDate: '1990-01-01', email: f.toLowerCase() + '@nexfit.test', active, roles, trainerProfile: sp ? { specialty: sp, certification: null, hireDate: null } : null }));
const equipment = ['Caminadora Pro X1:Cardio:available', 'Prensa de piernas 45°:Fuerza:maintenance', 'Mancuernas 2–30 kg:Pesas libres:available', 'Suspensión TRX:Funcional:out_of_service', 'Remo Concept:Cardio:available', 'Polea alta y baja:Fuerza:available']
  .map((s, i) => { const [name, category, status] = s.split(':'); return { id: i + 1, branchId: 1, branchName: 'Sede Centro', name, category, status, acquisitionDate: '2024-03-0' + (i + 1), imageUrl: null }; });
const gym = { date: '2026-11-07', timezone: 'America/Bogota', activeClients: 12, currentMemberships: 9, expiringMemberships: 2, expiringInDays: 7, monthRevenue: 1250000, todayCheckIns: 14, upcomingOccupancy: { classes: 8, confirmedBookings: 31, capacity: 120, percent: 25.8 } };
const api = { '/api/me': { identity: { firstName: 'Andrea', lastName: 'Castellanos', email: 'a@nexfit.test' }, affiliation: { organizationId: 1, organizationName: 'Iron Peak Fitness', roles: [role] } },
  '/api/membership-plans': plans, '/api/branches': branches, '/api/staff': staff, '/api/equipment': equipment, '/api/dashboard/gym': gym };

const routes = { admin: ['/panel','/planes','/sedes','/personal','/equipos','/clientes','/clases','/membresias','/pagos','/ejercicios','/auditoria','/apariencia','/mi-perfil'],
  staff: ['/clientes','/membresias','/pagos','/clases','/asistencia','/reservas','/apariencia'],
  client: ['/inicio','/progreso','/peso','/rutinas','/clases','/reservas','/mi-perfil','/apariencia'],
  trainer: ['/mis-alumnos','/rutinas','/clases','/reservas','/ejercicios'],
  master: ['/panel','/establecimientos','/eps','/auditoria'] };
(async () => {
  await new Promise((r) => srv.listen(4399, r));
  const b = await chromium.launch();
  const pg = await b.newPage({ viewport: { width: W, height: 740 } });
  await pg.route('**/api/**', (route) => { const u = new URL(route.request().url()); const body = api[u.pathname] ?? (u.pathname.endsWith('s') ? [] : {}); route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) }); });
  for (const url of routes[role]) {
    await pg.goto('http://localhost:4399' + url); await pg.waitForTimeout(600);
    const r = await pg.evaluate((w) => { const over = document.documentElement.scrollWidth - window.innerWidth; const bad = [...document.querySelectorAll('body *')].filter((e) => e.getBoundingClientRect().right > w + 1 && getComputedStyle(e).position !== 'fixed').slice(0, 3).map((e) => e.tagName.toLowerCase() + '.' + String(e.className).split(' ')[0]); return { over, bad }; }, W);
    const h = await pg.evaluate(() => (document.querySelector('h1')?.textContent || '').trim().slice(0,24) + ' | tabla:' + document.querySelectorAll('table').length); console.log(role.padEnd(7), url.padEnd(18), new URL(pg.url()).pathname.padEnd(14), h, '|', r.over > 0 ? 'DESBORDA +' + r.over + 'px ' + r.bad.join(' ') : 'ok');
  }
  await b.close(); srv.close();
})();
