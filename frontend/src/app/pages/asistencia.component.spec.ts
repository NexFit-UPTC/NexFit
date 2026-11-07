import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SessionService } from '../core/session.service';
import { AsistenciaComponent } from './asistencia.component';

const sede = {
  id: 3, name: 'Sede Norte', phone: null, schedule: null, addressLine1: 'Calle 1', addressLine2: null,
  city: 'Tunja', department: 'Boyacá', postalCode: null, country: 'Colombia', active: true
};
const dentro = {
  id: 1, memberId: 7, memberName: 'Ana Pérez', branchId: 3, branchName: 'Sede Norte',
  checkInAt: '2026-11-07T13:00:00Z', checkOutAt: null, autoClosed: false,
  registeredByMemberId: null, registeredByName: null
};
const cerrado = {
  ...dentro, id: 2, checkOutAt: '2026-11-06T23:59:59Z', autoClosed: true,
  registeredByMemberId: 9, registeredByName: 'Luis Gómez'
};

describe('AsistenciaComponent', () => {
  let fixture: ComponentFixture<AsistenciaComponent>;
  let http: HttpTestingController;

  async function montar(roles: string[], datos: object[] = [dentro, cerrado], sedes: object[] = [sede]) {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.inject(SessionService).roles.set(roles);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AsistenciaComponent);
    fixture.detectChanges();
    const puedeRegistrar = roles.includes('client') || roles.includes('staff');
    if (puedeRegistrar) http.expectOne({ method: 'GET', url: '/api/branches' }).flush(sedes);
    if (roles.includes('staff')) {
      http.expectOne((r) => r.url === '/api/clients').flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    }
    http.expectOne({ method: 'GET', url: '/api/check-ins' }).flush(datos);
    await estabilizar();
  }
  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  const el = () => fixture.nativeElement as HTMLElement;

  async function estabilizar() {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('el Cliente ve «Mi asistencia», sin columnas de cliente ni de quién registró', async () => {
    await montar(['client']);
    expect(el().querySelector('h1')!.textContent).toContain('Mi asistencia');
    const cabecera = el().querySelector('thead')!.textContent!;
    expect(cabecera).not.toContain('Cliente');
    expect(cabecera).not.toContain('Registró');
    const filas = el().querySelectorAll('tbody tr');
    expect(filas).toHaveLength(2);
    expect(filas[0].textContent).toContain('Dentro');
    expect(filas[1].textContent).toContain('Cierre automático');
  });

  it('con una sola sede activa el Cliente registra su ingreso sin elegirla', async () => {
    await montar(['client'], []);
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    const req = http.expectOne({ method: 'POST', url: '/api/check-ins' });
    expect(req.request.body).toEqual({ branchId: 3 });
    req.flush(dentro);
    http.expectOne({ method: 'GET', url: '/api/check-ins' }).flush([dentro]);
    await estabilizar();
    expect(el().textContent).toContain('Ingreso registrado: Ana Pérez');
    expect(el().querySelectorAll('tbody tr')).toHaveLength(1);
  });

  it('sin sede elegida no llama al servidor', async () => {
    await montar(['client'], [], [sede, { ...sede, id: 4, name: 'Sede Sur' }]);
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await estabilizar();
    http.expectNone({ method: 'POST', url: '/api/check-ins' });
    expect(el().querySelector('[role="alert"]')!.textContent).toContain('Selecciona la sede');
  });

  it('muestra el motivo cuando el servidor rechaza el ingreso', async () => {
    await montar(['client'], []);
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    http.expectOne({ method: 'POST', url: '/api/check-ins' }).flush(
      { code: 'NO_VALID_MEMBERSHIP', field: 'status', message: 'La membresía del cliente está vencida' },
      { status: 409, statusText: 'Conflict' }
    );
    await estabilizar();
    expect(el().querySelector('[role="alert"]')!.textContent).toContain('vencida');
  });

  it('solo los ingresos abiertos ofrecen registrar la salida y luego se recarga', async () => {
    await montar(['client']);
    const botones = el().querySelectorAll<HTMLButtonElement>('tbody button');
    expect(botones).toHaveLength(1);
    botones[0].click();
    http.expectOne({ method: 'PATCH', url: '/api/check-ins/1/check-out' }).flush({ ...dentro, checkOutAt: '2026-11-07T15:00:00Z' });
    http.expectOne({ method: 'GET', url: '/api/check-ins' }).flush([{ ...dentro, checkOutAt: '2026-11-07T15:00:00Z' }, cerrado]);
    await estabilizar();
    expect(el().querySelectorAll('tbody button')).toHaveLength(0);
  });

  it('el Administrador consulta el historial con cliente y quién registró, sin formulario ni salida', async () => {
    await montar(['admin']);
    expect(el().querySelector('form')).toBeNull();
    const cabecera = el().querySelector('thead')!.textContent!;
    expect(cabecera).toContain('Cliente');
    expect(cabecera).toContain('Registró');
    expect(el().querySelectorAll('tbody button')).toHaveLength(0);
    expect(el().querySelectorAll('tbody tr')[0].textContent).toContain('El propio cliente');
    expect(el().querySelectorAll('tbody tr')[1].textContent).toContain('Luis Gómez');
  });

  it('el Staff exige elegir el cliente y envía su memberId', async () => {
    await montar(['staff'], []);
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await estabilizar();
    http.expectNone({ method: 'POST', url: '/api/check-ins' });
    expect(el().querySelector('[role="alert"]')!.textContent).toContain('Selecciona el cliente');
  });

  it('filtra por fechas y por quienes siguen dentro', async () => {
    await montar(['admin'], []);
    const desde = el().querySelector<HTMLInputElement>('input[name="desde"]')!;
    desde.value = '2026-11-01';
    desde.dispatchEvent(new Event('input'));
    http.expectOne({ method: 'GET', url: '/api/check-ins?from=2026-11-01' }).flush([]);
    await estabilizar();
    const casilla = el().querySelector<HTMLInputElement>('input[name="soloDentro"]')!;
    casilla.checked = true;
    casilla.dispatchEvent(new Event('change'));
    http.expectOne({ method: 'GET', url: '/api/check-ins?from=2026-11-01&open=true' }).flush([dentro]);
    await estabilizar();
    expect(el().querySelectorAll('tbody tr')).toHaveLength(1);
  });
});
