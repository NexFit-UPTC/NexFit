import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SessionService } from '../core/session.service';
import { EquiposComponent } from './equipos.component';

const sede = {
  id: 3, name: 'Sede Norte', phone: null, schedule: null, addressLine1: 'Calle 1', addressLine2: null,
  city: 'Tunja', department: 'Boyacá', postalCode: null, country: 'Colombia', active: true
};
const cinta = {
  id: 1, branchId: 3, branchName: 'Sede Norte', name: 'Cinta Pro', category: 'Cardio', status: 'available',
  acquisitionDate: '2025-03-10', imageUrl: null
};
const prensa = { ...cinta, id: 2, name: 'Prensa', category: 'Fuerza', status: 'maintenance', imageUrl: 'https://cdn/p.png' };

describe('EquiposComponent', () => {
  let fixture: ComponentFixture<EquiposComponent>;
  let http: HttpTestingController;

  async function montar(roles: string[], datos: object[] = [cinta, prensa]) {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.inject(SessionService).roles.set(roles);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(EquiposComponent);
    fixture.detectChanges();
    http.expectOne({ method: 'GET', url: '/api/branches' }).flush([sede]);
    http.expectOne({ method: 'GET', url: '/api/equipment' }).flush(datos);
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

  it('muestra las tarjetas con imagen propia o la ilustración por categoría', async () => {
    await montar(['staff']);
    const imgs = el().querySelectorAll<HTMLImageElement>('.nf-media img');
    expect(imgs).toHaveLength(2);
    expect(imgs[0].getAttribute('src')).toBe('/img/equipos/cardio.svg');
    expect(imgs[1].getAttribute('src')).toBe('https://cdn/p.png');
    expect(el().textContent).toContain('En mantenimiento');
  });

  it('el Staff solo consulta: sin botones de registrar, editar ni cambio de estado', async () => {
    await montar(['staff']);
    expect(el().querySelector('.nf-cabecera button')).toBeNull();
    expect(el().querySelectorAll('.nf-media button, .nf-media select')).toHaveLength(0);
  });

  it('el Administrador registra un equipo en una sede', async () => {
    await montar(['admin'], []);
    el().querySelector<HTMLButtonElement>('.nf-cabecera button')!.click();
    await estabilizar();
    const nombre = el().querySelector<HTMLInputElement>('input[name="nombre"]')!;
    nombre.value = 'Remo';
    nombre.dispatchEvent(new Event('input'));
    await estabilizar();
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    const req = http.expectOne({ method: 'POST', url: '/api/equipment' });
    expect(req.request.body).toEqual({
      branchId: 3, name: 'Remo', category: null, acquisitionDate: null, imageUrl: null, status: 'available'
    });
    req.flush(cinta);
    http.expectOne({ method: 'GET', url: '/api/equipment' }).flush([cinta]);
    await estabilizar();
    expect(el().querySelector('form')).toBeNull();
  });

  it('exige el nombre antes de llamar al servidor', async () => {
    await montar(['admin'], []);
    el().querySelector<HTMLButtonElement>('.nf-cabecera button')!.click();
    await estabilizar();
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await estabilizar();
    http.expectNone({ method: 'POST', url: '/api/equipment' });
    expect(el().querySelector('[role="alert"]')!.textContent).toContain('El nombre es obligatorio');
  });

  it('cambiar a mantenimiento se envía; dar de baja pide confirmación primero', async () => {
    await montar(['admin']);
    const selects = el().querySelectorAll<HTMLSelectElement>('.nf-media select');
    selects[0].value = 'maintenance';
    selects[0].dispatchEvent(new Event('change'));
    http.expectOne({ method: 'PATCH', url: '/api/equipment/1/status' }).flush({ ...cinta, status: 'maintenance' });
    http.expectOne({ method: 'GET', url: '/api/equipment' }).flush([{ ...cinta, status: 'maintenance' }, prensa]);
    await estabilizar();

    const otra = el().querySelectorAll<HTMLSelectElement>('.nf-media select')[1];
    otra.value = 'retired';
    otra.dispatchEvent(new Event('change'));
    await estabilizar();
    http.expectNone({ method: 'PATCH', url: '/api/equipment/2/status' });
    expect(el().querySelector('[role="alertdialog"]')!.textContent).toContain('definitivo');

    el().querySelector<HTMLButtonElement>('[role="alertdialog"] .nf-boton')!.click();
    http.expectOne({ method: 'PATCH', url: '/api/equipment/2/status' }).flush({ ...prensa, status: 'retired' });
    http.expectOne({ method: 'GET', url: '/api/equipment' }).flush([cinta, { ...prensa, status: 'retired' }]);
    await estabilizar();
    // El equipo dado de baja ya no ofrece cambiar de estado.
    expect(el().querySelectorAll('.nf-media select')).toHaveLength(1);
  });

  it('filtra por estado', async () => {
    await montar(['admin']);
    const select = el().querySelector<HTMLSelectElement>('select[name="filtroEstado"]')!;
    select.value = 'maintenance';
    select.dispatchEvent(new Event('change'));
    http.expectOne({ method: 'GET', url: '/api/equipment?status=maintenance' }).flush([prensa]);
    await estabilizar();
    expect(el().querySelectorAll('.nf-media')).toHaveLength(1);
  });
});
