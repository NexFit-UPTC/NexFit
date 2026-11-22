import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PlanesComponent } from './planes.component';

const plan = {
  id: 3,
  name: 'Mensual',
  description: null,
  price: 90000,
  durationDays: 30,
  benefits: 'Acceso total',
  active: true
};

describe('PlanesComponent', () => {
  let fixture: ComponentFixture<PlanesComponent>;
  let http: HttpTestingController;

  beforeEach(async () => {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PlanesComponent);
    fixture.detectChanges();
  });
  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  const el = () => fixture.nativeElement as HTMLElement;

  async function cargar(cuerpo: object[] = [plan, { ...plan, id: 4, name: 'Anual', durationDays: 365, active: false }]) {
    http.expectOne({ method: 'GET', url: '/api/membership-plans' }).flush(cuerpo);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function escribir(nombre: string, valor: string) {
    const campo = el().querySelector<HTMLInputElement>(`[name="${nombre}"]`)!;
    campo.value = valor;
    campo.dispatchEvent(new Event('input'));
  }

  async function abrirNuevo() {
    el().querySelector<HTMLButtonElement>('button')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
  }

  it('lista los planes con duración y estado', async () => {
    await cargar();
    const filas = el().querySelectorAll('tbody tr');
    expect(filas).toHaveLength(2);
    expect(filas[0].textContent).toContain('Mensual');
    expect(filas[0].textContent).toContain('30 días');
    expect(filas[0].textContent).toContain('Activo');
    expect(filas[1].textContent).toContain('Inactivo');
  });

  it('sin planes muestra el aviso', async () => {
    await cargar([]);
    expect(el().textContent).toContain('No hay planes.');
  });

  it('crear: envía precio y duración numéricos', async () => {
    await cargar();
    await abrirNuevo();
    escribir('name', 'Trimestral');
    escribir('price', '240000');
    escribir('durationDays', '90');
    el().querySelector('form')!.dispatchEvent(new Event('submit'));

    const req = http.expectOne({ method: 'POST', url: '/api/membership-plans' });
    expect(req.request.body).toMatchObject({ name: 'Trimestral', price: 240000, durationDays: 90 });
    req.flush({ ...plan, id: 5 }, { status: 201, statusText: 'Created' });
    http.expectOne({ method: 'GET', url: '/api/membership-plans' }).flush([plan]);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el().querySelector('form')).toBeNull();
  });

  it('editar: precarga y hace PUT sobre el plan', async () => {
    await cargar();
    el().querySelector<HTMLButtonElement>('tbody tr button')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(el().querySelector<HTMLInputElement>('[name="name"]')!.value).toBe('Mensual');
    expect(el().querySelector<HTMLInputElement>('[name="durationDays"]')!.value).toBe('30');

    escribir('name', 'Mensual Plus');
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    const req = http.expectOne({ method: 'PUT', url: '/api/membership-plans/3' });
    expect(req.request.body.name).toBe('Mensual Plus');
    req.flush(plan);
    http.expectOne({ method: 'GET', url: '/api/membership-plans' }).flush([plan]);
  });

  it('un 400 se muestra junto al campo', async () => {
    await cargar();
    await abrirNuevo();
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    http.expectOne({ method: 'POST', url: '/api/membership-plans' }).flush(
      { code: 'VALIDATION_ERROR', field: 'price', message: 'El precio es obligatorio' },
      { status: 400, statusText: 'Bad Request' }
    );
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el().querySelector('form [role="alert"]')?.textContent).toContain('El precio es obligatorio');
  });

  it('un 409 por nombre repetido se muestra junto al campo', async () => {
    await cargar();
    await abrirNuevo();
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    http.expectOne({ method: 'POST', url: '/api/membership-plans' }).flush(
      { code: 'PLAN_NAME_TAKEN', field: 'name', message: 'Ya existe un plan con ese nombre' },
      { status: 409, statusText: 'Conflict' }
    );
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el().querySelector('form [role="alert"]')?.textContent).toContain('Ya existe un plan');
  });

  it('desactivar: PATCH con active=false y recarga', async () => {
    await cargar();
    const botones = el().querySelectorAll<HTMLButtonElement>('button.alternar');
    expect(botones[1].textContent).toContain('Activar');
    botones[0].click();
    const req = http.expectOne({ method: 'PATCH', url: '/api/membership-plans/3/active' });
    expect(req.request.body).toEqual({ active: false });
    req.flush({ ...plan, active: false });
    http.expectOne({ method: 'GET', url: '/api/membership-plans' }).flush([]);
  });
});
