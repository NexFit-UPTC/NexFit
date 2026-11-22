import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SedesComponent } from './sedes.component';

const sede = {
  id: 7,
  name: 'Sede Norte',
  phone: '3001234567',
  schedule: 'L-V 6am-9pm',
  addressLine1: 'Calle 1 # 2-3',
  addressLine2: null,
  city: 'Tunja',
  department: 'Boyacá',
  postalCode: null,
  country: 'Colombia',
  active: true
};

describe('SedesComponent', () => {
  let fixture: ComponentFixture<SedesComponent>;
  let http: HttpTestingController;

  beforeEach(async () => {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SedesComponent);
    fixture.detectChanges();
  });
  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  const el = () => fixture.nativeElement as HTMLElement;

  async function cargar(cuerpo: object[] = [sede, { ...sede, id: 8, name: 'Sede Sur', active: false }]) {
    http.expectOne({ method: 'GET', url: '/api/branches' }).flush(cuerpo);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function escribir(nombre: string, valor: string) {
    const campo = el().querySelector<HTMLInputElement>(`[name="${nombre}"]`)!;
    campo.value = valor;
    campo.dispatchEvent(new Event('input'));
  }

  it('lista las sedes con su estado', async () => {
    await cargar();
    const filas = el().querySelectorAll('tbody tr');
    expect(filas).toHaveLength(2);
    expect(filas[0].textContent).toContain('Sede Norte');
    expect(filas[0].textContent).toContain('Activa');
    expect(filas[1].textContent).toContain('Inactiva');
  });

  it('sin sedes muestra el aviso', async () => {
    await cargar([]);
    expect(el().textContent).toContain('No hay sedes.');
  });

  it('si el listado falla, muestra el error', async () => {
    http.expectOne('/api/branches').flush(null, { status: 500, statusText: 'x' });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el().querySelector('[role="alert"]')?.textContent).toContain('No se pudo cargar');
  });

  it('crear: abre el formulario con Colombia por defecto, hace POST y recarga', async () => {
    await cargar();
    el().querySelector<HTMLButtonElement>('button')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(el().querySelector<HTMLInputElement>('[name="country"]')!.value).toBe('Colombia');

    escribir('name', 'Sede Centro');
    escribir('addressLine1', 'Cra 5 # 6-7');
    escribir('city', 'Duitama');
    escribir('department', 'Boyacá');
    el().querySelector('form')!.dispatchEvent(new Event('submit'));

    const req = http.expectOne({ method: 'POST', url: '/api/branches' });
    expect(req.request.body).toMatchObject({
      name: 'Sede Centro',
      addressLine1: 'Cra 5 # 6-7',
      city: 'Duitama',
      department: 'Boyacá',
      country: 'Colombia'
    });
    req.flush({ ...sede, id: 9 }, { status: 201, statusText: 'Created' });
    http.expectOne({ method: 'GET', url: '/api/branches' }).flush([sede]);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el().querySelector('form')).toBeNull();
  });

  it('editar: precarga los datos y hace PUT sobre la sede', async () => {
    await cargar();
    el().querySelector<HTMLButtonElement>('tbody tr button')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(el().querySelector<HTMLInputElement>('[name="name"]')!.value).toBe('Sede Norte');

    escribir('name', 'Sede Norte 2');
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    const req = http.expectOne({ method: 'PUT', url: '/api/branches/7' });
    expect(req.request.body.name).toBe('Sede Norte 2');
    req.flush(sede);
    http.expectOne({ method: 'GET', url: '/api/branches' }).flush([sede]);
  });

  it('un 400 se muestra junto al campo y el formulario sigue abierto', async () => {
    await cargar();
    el().querySelector<HTMLButtonElement>('button')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    http.expectOne({ method: 'POST', url: '/api/branches' }).flush(
      { code: 'VALIDATION_ERROR', field: 'name', message: 'El nombre es obligatorio' },
      { status: 400, statusText: 'Bad Request' }
    );
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el().querySelector('form')).not.toBeNull();
    expect(el().querySelector('form [role="alert"]')?.textContent).toContain('El nombre es obligatorio');
  });

  it('un 409 por nombre repetido se muestra junto al campo', async () => {
    await cargar();
    el().querySelector<HTMLButtonElement>('button')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    http.expectOne({ method: 'POST', url: '/api/branches' }).flush(
      { code: 'BRANCH_NAME_TAKEN', field: 'name', message: 'Ya existe una sede con ese nombre en el gimnasio' },
      { status: 409, statusText: 'Conflict' }
    );
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el().querySelector('form [role="alert"]')?.textContent).toContain('Ya existe una sede');
  });

  it('desactivar: PATCH con active=false y recarga; en una inactiva ofrece Activar', async () => {
    await cargar();
    const botones = el().querySelectorAll<HTMLButtonElement>('button.alternar');
    expect(botones[0].textContent).toContain('Desactivar');
    expect(botones[1].textContent).toContain('Activar');
    botones[0].click();
    const req = http.expectOne({ method: 'PATCH', url: '/api/branches/7/active' });
    expect(req.request.body).toEqual({ active: false });
    req.flush({ ...sede, active: false });
    http.expectOne({ method: 'GET', url: '/api/branches' }).flush([]);
  });

  it('si activar/desactivar falla, muestra el mensaje de la API', async () => {
    await cargar();
    el().querySelector<HTMLButtonElement>('button.alternar')!.click();
    http.expectOne({ method: 'PATCH', url: '/api/branches/7/active' }).flush(
      { code: 'ACCESS_DENIED', message: 'No tienes permiso' },
      { status: 403, statusText: 'Forbidden' }
    );
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el().querySelector('[role="alert"]')?.textContent).toContain('No tienes permiso');
  });
});
