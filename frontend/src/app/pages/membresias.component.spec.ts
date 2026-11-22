import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SessionService } from '../core/session.service';
import { MembresiasComponent } from './membresias.component';

const base = {
  memberId: 7,
  memberName: 'Ana Pérez',
  planId: 3,
  planName: 'Mensual',
  startDate: '2026-11-01',
  endDate: '2026-11-30',
  status: 'active',
  statusReason: null,
  statusChangedAt: null,
  createdAt: '2026-11-01T10:00:00Z'
};
const vigente = { ...base, id: 1, effectiveStatus: 'current' };
const vencida = { ...base, id: 2, memberName: 'Luis Gómez', effectiveStatus: 'expired' };
const suspendida = { ...base, id: 3, memberName: 'Eva Ruiz', status: 'suspended', effectiveStatus: 'suspended', statusReason: 'Viaje' };

describe('MembresiasComponent', () => {
  let fixture: ComponentFixture<MembresiasComponent>;
  let http: HttpTestingController;

  function montar(roles: string[]) {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.inject(SessionService).roles.set(roles);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(MembresiasComponent);
    fixture.detectChanges();
  }
  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const botones = () => Array.from(el().querySelectorAll<HTMLButtonElement>('button'));
  const boton = (texto: string) => botones().find((b) => b.textContent?.trim() === texto);

  async function estabilizar() {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function cargar(datos: object[] = [vigente, vencida, suspendida]) {
    http.expectOne({ method: 'GET', url: '/api/memberships' }).flush(datos);
    await estabilizar();
  }

  it('lista las membresías con su estado efectivo en español', async () => {
    montar(['staff']);
    await cargar();
    const filas = el().querySelectorAll('tbody tr');
    expect(filas).toHaveLength(3);
    expect(filas[0].textContent).toContain('Vigente');
    expect(filas[1].textContent).toContain('Vencida');
    expect(filas[2].textContent).toContain('Suspendida');
    expect(filas[2].textContent).toContain('Viaje');
  });

  it('el Staff puede renovar una vigente; el Administrador no ve renovar ni contratar', async () => {
    montar(['staff']);
    await cargar([vigente, vencida]);
    const filas = el().querySelectorAll('tbody tr');
    expect(filas[0].textContent).toContain('Renovar');
    expect(filas[1].textContent).not.toContain('Renovar');
    expect(boton('Contratar membresía')).toBeDefined();
    TestBed.resetTestingModule();

    montar(['admin']);
    await cargar([vigente]);
    expect(el().textContent).not.toContain('Renovar');
    expect(boton('Contratar membresía')).toBeUndefined();
    expect(el().textContent).toContain('Suspender');
  });

  it('«Por vencer» consulta con los días elegidos', async () => {
    montar(['staff']);
    await cargar([]);
    const select = el().querySelector<HTMLSelectElement>('select[name="vista"]')!;
    select.value = 'por-vencer';
    select.dispatchEvent(new Event('change'));
    http.expectOne({ method: 'GET', url: '/api/memberships?expiringInDays=7' }).flush([vigente]);
    await estabilizar();
    expect(el().querySelectorAll('tbody tr')).toHaveLength(1);
  });

  it('filtra por estado efectivo', async () => {
    montar(['staff']);
    await cargar([]);
    const select = el().querySelector<HTMLSelectElement>('select[name="vista"]')!;
    select.value = 'expired';
    select.dispatchEvent(new Event('change'));
    http.expectOne({ method: 'GET', url: '/api/memberships?status=expired' }).flush([vencida]);
    await estabilizar();
    expect(el().textContent).toContain('Luis Gómez');
  });

  it('cancelar exige motivo: el error del servidor se muestra y no se cierra el panel', async () => {
    montar(['admin']);
    await cargar([vigente]);
    boton('Cancelar')!.click();
    await estabilizar();
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    http
      .expectOne({ method: 'PATCH', url: '/api/memberships/1/cancel' })
      .flush({ code: 'VALIDATION_ERROR', field: 'reason', message: 'El motivo de la cancelación es obligatorio' }, { status: 400, statusText: 'Bad Request' });
    await estabilizar();
    expect(el().textContent).toContain('El motivo de la cancelación es obligatorio');
  });

  it('suspender envía el motivo y recarga el listado', async () => {
    montar(['staff']);
    await cargar([vigente]);
    boton('Suspender')!.click();
    await estabilizar();
    const motivo = el().querySelector<HTMLTextAreaElement>('textarea[name="motivo"]')!;
    motivo.value = 'Viaje largo';
    motivo.dispatchEvent(new Event('input'));
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    const req = http.expectOne({ method: 'PATCH', url: '/api/memberships/1/suspend' });
    expect(req.request.body).toEqual({ reason: 'Viaje largo' });
    req.flush({ ...vigente, status: 'suspended', effectiveStatus: 'suspended' });
    http.expectOne({ method: 'GET', url: '/api/memberships' }).flush([suspendida]);
    await estabilizar();
    expect(el().textContent).toContain('Suspendida');
  });

  it('contrata con pago: envía cliente, plan y pago, y muestra el resumen', async () => {
    montar(['staff']);
    await cargar([]);
    boton('Contratar membresía')!.click();
    http
      .expectOne((r) => r.url === '/api/clients')
      .flush({ content: [{ memberId: 7, firstName: 'Ana', lastName: 'Pérez', documentNumber: '123' }], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    http
      .expectOne({ method: 'GET', url: '/api/membership-plans' })
      .flush([{ id: 3, name: 'Mensual', price: 90000, durationDays: 30, description: null, benefits: null, active: true }]);
    await estabilizar();

    const cliente = el().querySelector<HTMLSelectElement>('select[name="memberId"]')!;
    cliente.selectedIndex = 1;
    cliente.dispatchEvent(new Event('change'));
    const plan = el().querySelector<HTMLSelectElement>('select[name="planId"]')!;
    plan.selectedIndex = 1;
    plan.dispatchEvent(new Event('change'));
    await estabilizar();
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));

    const req = http.expectOne({ method: 'POST', url: '/api/memberships' });
    expect(req.request.body).toEqual({ memberId: 7, planId: 3, payment: { method: 'cash', status: 'completed' } });
    req.flush({ membership: { ...vigente, id: 9 }, payment: { id: 1, status: 'completed' } }, { status: 201, statusText: 'Created' });
    http.expectOne({ method: 'GET', url: '/api/memberships' }).flush([vigente]);
    await estabilizar();
    expect(el().textContent).toContain('Membresía contratada: Ana Pérez, Mensual · pago completado');
  });

  it('una contratación sin cliente ni plan no llama al servidor', async () => {
    montar(['staff']);
    await cargar([]);
    boton('Contratar membresía')!.click();
    http.expectOne((r) => r.url === '/api/clients').flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    http.expectOne({ method: 'GET', url: '/api/membership-plans' }).flush([]);
    await estabilizar();
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await estabilizar();
    expect(el().textContent).toContain('Selecciona un cliente');
    expect(el().textContent).toContain('Selecciona un plan');
    http.verify();
  });

  it('muestra el conflicto de solapamiento que devuelve el servidor', async () => {
    montar(['staff']);
    await cargar([]);
    boton('Contratar membresía')!.click();
    http
      .expectOne((r) => r.url === '/api/clients')
      .flush({ content: [{ memberId: 7, firstName: 'Ana', lastName: 'Pérez', documentNumber: '123' }], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    http
      .expectOne({ method: 'GET', url: '/api/membership-plans' })
      .flush([{ id: 3, name: 'Mensual', price: 90000, durationDays: 30, description: null, benefits: null, active: true }]);
    await estabilizar();
    for (const nombre of ['memberId', 'planId']) {
      const s = el().querySelector<HTMLSelectElement>(`select[name="${nombre}"]`)!;
      s.selectedIndex = 1;
      s.dispatchEvent(new Event('change'));
    }
    await estabilizar();
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    http
      .expectOne({ method: 'POST', url: '/api/memberships' })
      .flush({ code: 'MEMBERSHIP_OVERLAP', field: 'startDate', message: 'El cliente ya tiene una membresía que se solapa con ese período' }, { status: 409, statusText: 'Conflict' });
    await estabilizar();
    expect(el().textContent).toContain('se solapa con ese período');
  });
});
