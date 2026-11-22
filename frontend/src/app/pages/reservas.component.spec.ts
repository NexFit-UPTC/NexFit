import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SessionService } from '../core/session.service';
import { ReservasComponent } from './reservas.component';

const reserva = {
  id: 1,
  classId: 4,
  className: 'Spinning',
  classStartsAt: '2026-11-10T23:00:00Z',
  branchName: 'Sede Norte',
  memberId: 7,
  memberName: 'Ana Pérez',
  status: 'confirmed',
  bookedAt: '2026-11-07T10:00:00Z',
  cancelledAt: null
};
const cancelada = { ...reserva, id: 2, status: 'cancelled', cancelledAt: '2026-11-08T10:00:00Z' };

describe('ReservasComponent', () => {
  let fixture: ComponentFixture<ReservasComponent>;
  let http: HttpTestingController;

  async function montar(roles: string[], datos: object[] = [reserva, cancelada]) {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.inject(SessionService).roles.set(roles);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ReservasComponent);
    fixture.detectChanges();
    http.expectOne({ method: 'GET', url: '/api/bookings' }).flush(datos);
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

  it('el Cliente ve «Mis reservas» con confirmadas y canceladas, sin columna de cliente', async () => {
    await montar(['client']);
    expect(el().querySelector('h1')!.textContent).toContain('Mis reservas');
    expect(el().querySelector('thead')!.textContent).not.toContain('Cliente');
    const filas = el().querySelectorAll('tbody tr');
    expect(filas).toHaveLength(2);
    expect(filas[0].textContent).toContain('Confirmada');
    expect(filas[1].textContent).toContain('Cancelada');
  });

  it('solo las confirmadas ofrecen cancelar y se recarga después', async () => {
    await montar(['client']);
    const botones = el().querySelectorAll<HTMLButtonElement>('tbody button');
    expect(botones).toHaveLength(1);
    botones[0].click();
    http.expectOne({ method: 'PATCH', url: '/api/bookings/1/cancel' }).flush({ ...reserva, status: 'cancelled' });
    http.expectOne({ method: 'GET', url: '/api/bookings' }).flush([{ ...reserva, status: 'cancelled' }, cancelada]);
    await estabilizar();
    expect(el().querySelectorAll('tbody button')).toHaveLength(0);
  });

  it('el Administrador y el Entrenador ven el cliente pero no cancelan', async () => {
    await montar(['admin']);
    expect(el().querySelector('thead')!.textContent).toContain('Cliente');
    expect(el().querySelectorAll('tbody button')).toHaveLength(0);
  });

  it('filtra por estado', async () => {
    await montar(['staff'], []);
    const select = el().querySelector<HTMLSelectElement>('select[name="estado"]')!;
    select.value = 'cancelled';
    select.dispatchEvent(new Event('change'));
    http.expectOne({ method: 'GET', url: '/api/bookings?status=cancelled' }).flush([cancelada]);
    await estabilizar();
    expect(el().querySelectorAll('tbody tr')).toHaveLength(1);
  });

  it('muestra el error si no se puede cancelar', async () => {
    await montar(['client']);
    el().querySelector<HTMLButtonElement>('tbody button')!.click();
    http
      .expectOne({ method: 'PATCH', url: '/api/bookings/1/cancel' })
      .flush({ code: 'CLASS_STARTED', field: 'status', message: 'La clase ya inició: la reserva no se puede cancelar' }, { status: 409, statusText: 'Conflict' });
    await estabilizar();
    expect(el().textContent).toContain('La clase ya inició');
  });

  it('el Entrenador ve cada clase con sus inscritos confirmados', async () => {
    const otra = { ...reserva, id: 3, memberId: 8, memberName: 'Beto Gil' };
    const otraClase = { ...reserva, id: 4, classId: 9, className: 'Yoga', classStartsAt: '2026-11-09T13:00:00Z' };
    await montar(['trainer'], [reserva, cancelada, otra, otraClase]);

    const tarjetas = el().querySelectorAll('.nf-clase-reservas');
    expect(tarjetas).toHaveLength(2);
    expect(tarjetas[0].textContent).toContain('Yoga');
    expect(tarjetas[1].textContent).toContain('2 inscritos');
    expect(tarjetas[1].textContent).toContain('1 reserva cancelada');
    expect(Array.from(tarjetas[1].querySelectorAll('li')).map((l) => l.textContent)).toEqual(['Ana Pérez', 'Beto Gil']);
    expect(el().querySelector('table')).toBeNull();
  });
});
