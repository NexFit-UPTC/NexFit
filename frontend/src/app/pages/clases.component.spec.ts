import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SessionService } from '../core/session.service';
import { ClasesComponent } from './clases.component';

const clase = {
  id: 1,
  name: 'Spinning',
  description: null,
  branchId: 2,
  branchName: 'Sede Norte',
  trainerMemberId: 9,
  trainerName: 'Carlos Rojas',
  startsAt: '2026-11-10T23:00:00Z',
  durationMinutes: 45,
  capacity: 10,
  status: 'scheduled',
  confirmedBookings: 4,
  freeSlots: 6
};
const llena = { ...clase, id: 2, name: 'Yoga', confirmedBookings: 10, freeSlots: 0 };
const pagina = (content: object[]) => ({ content, page: 0, size: 12, totalElements: content.length, totalPages: 1 });
const sede = { id: 2, name: 'Sede Norte', active: true };

describe('ClasesComponent', () => {
  let fixture: ComponentFixture<ClasesComponent>;
  let http: HttpTestingController;

  async function montar(roles: string[], clases: object[] = [clase, llena]) {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.inject(SessionService).roles.set(roles);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ClasesComponent);
    fixture.detectChanges();
    http.expectOne({ method: 'GET', url: '/api/branches' }).flush([sede]);
    http.expectOne((r) => r.url === '/api/classes').flush(pagina(clases));
    await estabilizar();
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

  it('lista las clases con sede, entrenador y cupos ocupados', async () => {
    await montar(['client']);
    const filas = el().querySelectorAll('tbody tr');
    expect(filas).toHaveLength(2);
    expect(filas[0].textContent).toContain('Spinning');
    expect(filas[0].textContent).toContain('Sede Norte');
    expect(filas[0].textContent).toContain('Carlos Rojas');
    expect(filas[0].textContent).toContain('4/10');
  });

  it('pide solo las clases desde hoy', async () => {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.inject(SessionService).roles.set(['client']);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ClasesComponent);
    fixture.detectChanges();
    http.expectOne({ method: 'GET', url: '/api/branches' }).flush([]);
    const req = http.expectOne((r) => r.url === '/api/classes');
    expect(req.request.params.get('from')).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    expect(req.request.params.get('page')).toBe('0');
    req.flush(pagina([]));
    await estabilizar();
    expect(el().textContent).toContain('No hay clases');
  });

  it('el Cliente reserva para sí; una clase llena muestra «Sin cupos» deshabilitado', async () => {
    await montar(['client']);
    expect(boton('Sin cupos')!.disabled).toBe(true);
    expect(boton('Programar clase')).toBeUndefined();
    boton('Reservar')!.click();
    const req = http.expectOne({ method: 'POST', url: '/api/bookings' });
    expect(req.request.body).toEqual({ classId: 1 });
    req.flush({ id: 5 }, { status: 201, statusText: 'Created' });
    http.expectOne((r) => r.url === '/api/classes').flush(pagina([clase, llena]));
    await estabilizar();
    expect(el().textContent).toContain('Reservaste «Spinning»');
  });

  it('muestra el motivo si el servidor rechaza la reserva', async () => {
    await montar(['client']);
    boton('Reservar')!.click();
    http
      .expectOne({ method: 'POST', url: '/api/bookings' })
      .flush({ code: 'NO_VALID_MEMBERSHIP', field: 'status', message: 'El cliente no tiene una membresía vigente el día de la clase' }, { status: 409, statusText: 'Conflict' });
    await estabilizar();
    expect(el().textContent).toContain('no tiene una membresía vigente');
  });

  it('el Staff reserva a nombre de un cliente', async () => {
    await montar(['staff']);
    boton('Reservar a un cliente')!.click();
    http
      .expectOne((r) => r.url === '/api/clients')
      .flush({ content: [{ memberId: 7, firstName: 'Ana', lastName: 'Pérez', documentNumber: '123' }], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    await estabilizar();
    const select = el().querySelector<HTMLSelectElement>('select[name="clienteReserva"]')!;
    select.selectedIndex = 1;
    select.dispatchEvent(new Event('change'));
    await estabilizar();
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    const req = http.expectOne({ method: 'POST', url: '/api/bookings' });
    expect(req.request.body).toEqual({ classId: 1, memberId: 7 });
    req.flush({ id: 6, memberName: 'Ana Pérez' }, { status: 201, statusText: 'Created' });
    http.expectOne((r) => r.url === '/api/classes').flush(pagina([clase]));
    await estabilizar();
    expect(el().textContent).toContain('Reserva confirmada para Ana Pérez');
  });

  it('el Administrador programa una clase con inicio en instante ISO', async () => {
    await montar(['admin']);
    expect(boton('Reservar')).toBeUndefined();
    boton('Programar clase')!.click();
    http
      .expectOne({ method: 'GET', url: '/api/staff?page=0&size=50&role=trainer' })
      .flush({
        content: [
          { memberId: 9, firstName: 'Carlos', lastName: 'Rojas', active: true, roles: ['trainer'] },
          { memberId: 10, firstName: 'Inés', lastName: 'Mora', active: false, roles: ['trainer'] }
        ],
        page: 0,
        size: 50,
        totalElements: 2,
        totalPages: 1
      });
    await estabilizar();
    expect(el().querySelectorAll('select[name="trainerMemberId"] option')).toHaveLength(2);
    const poner = (nombre: string, valor: string) => {
      const c = el().querySelector<HTMLInputElement>(`[name="${nombre}"]`)!;
      c.value = valor;
      c.dispatchEvent(new Event('input'));
    };
    poner('name', 'Funcional');
    poner('startsAt', '2026-11-12T18:00');
    const sedeSel = el().querySelector<HTMLSelectElement>('select[name="branchId"]')!;
    sedeSel.selectedIndex = 1;
    sedeSel.dispatchEvent(new Event('change'));
    await estabilizar();
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    const req = http.expectOne({ method: 'POST', url: '/api/classes' });
    expect(req.request.body).toMatchObject({ name: 'Funcional', branchId: 2, durationMinutes: 60, capacity: 20 });
    expect(req.request.body.startsAt).toBe(new Date('2026-11-12T18:00').toISOString());
    req.flush(clase, { status: 201, statusText: 'Created' });
    http.expectOne((r) => r.url === '/api/classes').flush(pagina([clase]));
    await estabilizar();
    expect(el().querySelector('form')).toBeNull();
  });

  it('el Administrador cancela una clase y ve el aviso', async () => {
    await montar(['admin']);
    el().querySelectorAll<HTMLButtonElement>('tbody tr')[0].querySelectorAll('button')[1].click();
    await estabilizar();
    expect(el().textContent).toContain('Se cancelan también las 4 reservas confirmadas');
    const motivo = el().querySelector<HTMLTextAreaElement>('textarea[name="motivoClase"]')!;
    motivo.value = 'Entrenador enfermo';
    motivo.dispatchEvent(new Event('input'));
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    const req = http.expectOne({ method: 'PATCH', url: '/api/classes/1/cancel' });
    expect(req.request.body).toEqual({ reason: 'Entrenador enfermo' });
    req.flush({ ...clase, status: 'cancelled' });
    http.expectOne((r) => r.url === '/api/classes').flush(pagina([{ ...clase, status: 'cancelled', confirmedBookings: 0 }]));
    await estabilizar();
    expect(el().textContent).toContain('Clase «Spinning» cancelada');
    expect(el().textContent).toContain('Cancelada');
  });
});
