import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SessionService } from '../core/session.service';
import { PagosComponent } from './pagos.component';

const pago = {
  id: 1,
  memberId: 7,
  memberName: 'Ana Pérez',
  membershipId: null,
  concept: 'Mensualidad',
  amount: 90000,
  paidAt: '2026-11-05T15:00:00Z',
  method: 'cash',
  reference: null,
  status: 'completed',
  voidReason: null,
  completedAt: '2026-11-05T15:00:00Z'
};
const pendiente = { ...pago, id: 2, memberName: 'Luis Gómez', status: 'pending', method: 'transfer', completedAt: null };
const anulado = { ...pago, id: 3, status: 'voided', voidReason: 'Error de digitación' };

describe('PagosComponent', () => {
  let fixture: ComponentFixture<PagosComponent>;
  let http: HttpTestingController;

  function montar(roles: string[]) {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.inject(SessionService).roles.set(roles);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PagosComponent);
    fixture.detectChanges();
  }
  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const boton = (texto: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent?.trim() === texto);

  async function estabilizar() {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function cargar(items: object[] = [pago, pendiente, anulado], total = items.length) {
    http
      .expectOne((r) => r.url === '/api/payments' && r.params.get('page') === '0')
      .flush({ items, total, completedTotal: 90000, page: 0, size: 20 });
    await estabilizar();
  }

  it('lista los pagos con método, estado y el total completado', async () => {
    montar(['staff']);
    await cargar();
    const filas = el().querySelectorAll('tbody tr');
    expect(filas).toHaveLength(3);
    expect(filas[0].textContent).toContain('Efectivo');
    expect(filas[0].textContent).toContain('Completado');
    expect(filas[1].textContent).toContain('Transferencia');
    expect(filas[1].textContent).toContain('Pendiente');
    expect(filas[2].textContent).toContain('Anulado');
    expect(filas[2].textContent).toContain('Error de digitación');
    expect(el().textContent).toContain('3 pagos');
    expect(el().textContent).toMatch(/90[.,]000/);
  });

  it('solo los pendientes ofrecen completar y anular', async () => {
    montar(['admin']);
    await cargar();
    const filas = el().querySelectorAll('tbody tr');
    expect(filas[0].textContent).not.toContain('Completar');
    expect(filas[1].textContent).toContain('Completar');
    expect(filas[1].textContent).toContain('Anular');
    expect(filas[2].textContent).not.toContain('Anular');
    expect(boton('Registrar pago')).toBeUndefined();
  });

  it('el Cliente ve «Mis pagos», sin columna de cliente ni acciones', async () => {
    montar(['client']);
    await cargar([pendiente]);
    expect(el().querySelector('h1')!.textContent).toContain('Mis pagos');
    expect(el().querySelector('thead')!.textContent).not.toContain('Cliente');
    expect(el().textContent).not.toContain('Completar');
    expect(boton('Registrar pago')).toBeUndefined();
  });

  it('completar un pendiente llama al servidor y recarga', async () => {
    montar(['staff']);
    await cargar([pendiente]);
    boton('Completar')!.click();
    http.expectOne({ method: 'PATCH', url: '/api/payments/2/complete' }).flush({ ...pendiente, status: 'completed' });
    await cargar([{ ...pendiente, status: 'completed' }]);
    expect(el().textContent).not.toContain('Completar');
  });

  it('anular envía el motivo; sin motivo muestra el error del servidor', async () => {
    montar(['staff']);
    await cargar([pendiente]);
    boton('Anular')!.click();
    await estabilizar();
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    http
      .expectOne({ method: 'PATCH', url: '/api/payments/2/void' })
      .flush({ code: 'VALIDATION_ERROR', field: 'reason', message: 'El motivo de la anulación es obligatorio' }, { status: 400, statusText: 'Bad Request' });
    await estabilizar();
    expect(el().textContent).toContain('El motivo de la anulación es obligatorio');

    const motivo = el().querySelector<HTMLTextAreaElement>('textarea[name="motivoAnulacion"]')!;
    motivo.value = 'Duplicado';
    motivo.dispatchEvent(new Event('input'));
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    const req = http.expectOne({ method: 'PATCH', url: '/api/payments/2/void' });
    expect(req.request.body).toEqual({ reason: 'Duplicado' });
    req.flush({ ...pendiente, status: 'voided' });
    await cargar([{ ...pendiente, status: 'voided' }]);
  });

  it('filtrar por estado y fechas vuelve a la primera página', async () => {
    montar(['staff']);
    await cargar([]);
    const estado = el().querySelector<HTMLSelectElement>('select[name="filtroEstado"]')!;
    estado.value = 'pending';
    estado.dispatchEvent(new Event('change'));
    const req = http.expectOne((r) => r.url === '/api/payments');
    expect(req.request.params.get('status')).toBe('pending');
    expect(req.request.params.get('page')).toBe('0');
    req.flush({ items: [pendiente], total: 1, completedTotal: 0, page: 0, size: 20 });
    await estabilizar();
    expect(el().querySelectorAll('tbody tr')).toHaveLength(1);
  });

  it('pagina cuando hay más de 20 pagos', async () => {
    montar(['staff']);
    await cargar([pago], 45);
    expect(el().textContent).toContain('Página 1 de 3');
    boton('Siguiente')!.click();
    const req = http.expectOne((r) => r.url === '/api/payments' && r.params.get('page') === '1');
    req.flush({ items: [pago], total: 45, completedTotal: 90000, page: 1, size: 20 });
    await estabilizar();
    expect(el().textContent).toContain('Página 2 de 3');
  });

  it('registra un pago y muestra el error de validación por campo', async () => {
    montar(['staff']);
    await cargar([]);
    boton('Registrar pago')!.click();
    http
      .expectOne((r) => r.url === '/api/clients')
      .flush({ content: [{ memberId: 7, firstName: 'Ana', lastName: 'Pérez', documentNumber: '123' }], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    await estabilizar();
    const cliente = el().querySelector<HTMLSelectElement>('select[name="memberId"]')!;
    cliente.selectedIndex = 1;
    cliente.dispatchEvent(new Event('change'));
    const concepto = el().querySelector<HTMLInputElement>('input[name="concept"]')!;
    concepto.value = 'Mensualidad';
    concepto.dispatchEvent(new Event('input'));
    const valor = el().querySelector<HTMLInputElement>('input[name="amount"]')!;
    valor.value = '0';
    valor.dispatchEvent(new Event('input'));
    await estabilizar();
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    http
      .expectOne({ method: 'POST', url: '/api/payments' })
      .flush({ code: 'VALIDATION_ERROR', field: 'amount', message: 'El valor debe ser mayor que 0' }, { status: 400, statusText: 'Bad Request' });
    await estabilizar();
    expect(el().textContent).toContain('El valor debe ser mayor que 0');

    valor.value = '50000';
    valor.dispatchEvent(new Event('input'));
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    const req = http.expectOne({ method: 'POST', url: '/api/payments' });
    expect(req.request.body).toEqual({ memberId: 7, concept: 'Mensualidad', amount: 50000, method: 'cash', status: 'completed' });
    req.flush({ ...pago, id: 10 }, { status: 201, statusText: 'Created' });
    await cargar([pago]);
    expect(el().querySelector('form')).toBeNull();
  });
});
