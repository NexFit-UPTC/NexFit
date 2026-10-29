import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PersonalComponent } from './personal.component';

const entrenador = {
  memberId: 11,
  userId: 21,
  documentType: 'CC',
  documentNumber: '1010',
  firstName: 'Laura',
  lastName: 'Ríos',
  birthDate: '1995-04-02',
  email: 'laura@nexfit.test',
  active: true,
  roles: ['trainer'],
  trainerProfile: { specialty: 'Crossfit', certification: null, hireDate: '2025-01-10' }
};
const staff = {
  ...entrenador,
  memberId: 12,
  userId: 22,
  firstName: 'Pedro',
  lastName: 'Gómez',
  email: 'pedro@nexfit.test',
  active: false,
  roles: ['admin', 'staff'],
  trainerProfile: null
};

describe('PersonalComponent', () => {
  let fixture: ComponentFixture<PersonalComponent>;
  let http: HttpTestingController;

  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PersonalComponent);
    fixture.detectChanges();
  });
  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  const el = () => fixture.nativeElement as HTMLElement;

  async function cargar(cuerpo: object[] = [entrenador, staff]) {
    http.expectOne({ method: 'GET', url: '/api/staff' }).flush(cuerpo);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function abrirNuevo() {
    el().querySelector<HTMLButtonElement>('button')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function escribir(nombre: string, valor: string) {
    const campo = el().querySelector<HTMLInputElement>(`[name="${nombre}"]`)!;
    campo.value = valor;
    campo.dispatchEvent(new Event('input'));
  }

  function marcar(nombre: string, valor: boolean) {
    const campo = el().querySelector<HTMLInputElement>(`[name="${nombre}"]`)!;
    campo.checked = valor;
    campo.dispatchEvent(new Event('change'));
  }

  function llenarBasicos() {
    escribir('documentNumber', '2020');
    escribir('firstName', 'Ana');
    escribir('lastName', 'Mora');
    escribir('birthDate', '1998-03-04');
    escribir('email', 'ana@nexfit.test');
  }

  it('lista el personal con roles en español y estado', async () => {
    await cargar();
    const filas = el().querySelectorAll('tbody tr');
    expect(filas).toHaveLength(2);
    expect(filas[0].textContent).toContain('Laura Ríos');
    expect(filas[0].textContent).toContain('Entrenador');
    expect(filas[0].textContent).toContain('Activo');
    expect(filas[1].textContent).toContain('Administrador, Staff');
    expect(filas[1].textContent).toContain('Inactivo');
  });

  it('sin personal muestra el aviso', async () => {
    await cargar([]);
    expect(el().textContent).toContain('No hay personal registrado.');
  });

  it('si el listado falla, muestra el error', async () => {
    http.expectOne('/api/staff').flush(null, { status: 500, statusText: 'x' });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el().querySelector('[role="alert"]')?.textContent).toContain('No se pudo cargar');
  });

  it('crear Staff: POST con los roles y muestra la contraseña temporal una sola vez', async () => {
    await cargar();
    await abrirNuevo();
    llenarBasicos();
    el().querySelector('form')!.dispatchEvent(new Event('submit'));

    const req = http.expectOne({ method: 'POST', url: '/api/staff' });
    expect(req.request.body).toMatchObject({
      documentType: 'CC',
      documentNumber: '2020',
      firstName: 'Ana',
      lastName: 'Mora',
      birthDate: '1998-03-04',
      email: 'ana@nexfit.test',
      roles: ['staff']
    });
    expect(req.request.body.specialty).toBeUndefined();
    req.flush({ userId: 30, memberId: 40, temporaryPassword: 'Temp-123-abc' }, { status: 201, statusText: 'Created' });
    http.expectOne({ method: 'GET', url: '/api/staff' }).flush([entrenador]);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el().querySelector('[role="status"]')?.textContent).toContain('Temp-123-abc');
    expect(el().querySelector('form')).toBeNull();

    el().querySelector<HTMLButtonElement>('[role="status"] button')!.click();
    fixture.detectChanges();
    expect(el().textContent).not.toContain('Temp-123-abc');
  });

  it('crear Entrenador: pide y envía los datos de entrenador', async () => {
    await cargar();
    await abrirNuevo();
    llenarBasicos();
    marcar('staff', false);
    marcar('trainer', true);
    fixture.detectChanges();
    await fixture.whenStable();
    escribir('specialty', 'Yoga');
    escribir('hireDate', '2026-02-01');
    el().querySelector('form')!.dispatchEvent(new Event('submit'));

    const req = http.expectOne({ method: 'POST', url: '/api/staff' });
    expect(req.request.body).toMatchObject({ roles: ['trainer'], specialty: 'Yoga', hireDate: '2026-02-01' });
    expect(req.request.body.certification).toBeUndefined();
    req.flush({ userId: 30, memberId: 40, temporaryPassword: 'x' }, { status: 201, statusText: 'Created' });
    http.expectOne({ method: 'GET', url: '/api/staff' }).flush([]);
  });

  it('una persona que ya existía queda afiliada y no hay contraseña', async () => {
    await cargar();
    await abrirNuevo();
    llenarBasicos();
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    http.expectOne({ method: 'POST', url: '/api/staff' }).flush({
      userId: 30,
      memberId: 41,
      firstName: 'Ana',
      lastName: 'Mora'
    });
    http.expectOne({ method: 'GET', url: '/api/staff' }).flush([]);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el().querySelector('[role="status"]')?.textContent).toContain('ya existía');
    expect(el().querySelector('code')).toBeNull();
  });

  it('un 400 (menor de edad) se muestra junto al campo', async () => {
    await cargar();
    await abrirNuevo();
    llenarBasicos();
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    http.expectOne({ method: 'POST', url: '/api/staff' }).flush(
      { code: 'VALIDATION_ERROR', field: 'birthDate', message: 'La persona debe ser mayor de 18 años' },
      { status: 400, statusText: 'Bad Request' }
    );
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el().querySelector('form')).not.toBeNull();
    expect(el().querySelector('form [role="alert"]')?.textContent).toContain('mayor de 18');
  });

  it('un 409 sin campo (RN-04, correo ya usado…) se muestra como mensaje general', async () => {
    await cargar();
    await abrirNuevo();
    llenarBasicos();
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    http.expectOne({ method: 'POST', url: '/api/staff' }).flush(
      { message: 'La persona ya es miembro de este gimnasio' },
      { status: 409, statusText: 'Conflict' }
    );
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el().querySelector('form [role="alert"]')?.textContent).toContain('ya es miembro');
  });

  it('editar roles: precarga, no pide datos personales y hace PATCH con los roles', async () => {
    await cargar();
    el().querySelector<HTMLButtonElement>('button.editar')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(el().querySelector('[name="documentNumber"]')).toBeNull();
    expect(el().querySelector<HTMLInputElement>('[name="specialty"]')!.value).toBe('Crossfit');

    marcar('staff', true);
    fixture.detectChanges();
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    const req = http.expectOne({ method: 'PATCH', url: '/api/staff/11' });
    expect(req.request.body).toMatchObject({ roles: ['staff', 'trainer'], specialty: 'Crossfit', hireDate: '2025-01-10' });
    req.flush(entrenador);
    http.expectOne({ method: 'GET', url: '/api/staff' }).flush([entrenador]);
  });

  it('desactivar usa POST /deactivate y activar usa POST /activate', async () => {
    await cargar();
    const botones = el().querySelectorAll<HTMLButtonElement>('button.alternar');
    expect(botones[0].textContent).toContain('Desactivar');
    expect(botones[1].textContent).toContain('Activar');

    botones[0].click();
    http.expectOne({ method: 'POST', url: '/api/staff/11/deactivate' }).flush({ ...entrenador, active: false });
    http.expectOne({ method: 'GET', url: '/api/staff' }).flush([{ ...entrenador, active: false }, staff]);
    await fixture.whenStable();
    fixture.detectChanges();

    el().querySelectorAll<HTMLButtonElement>('button.alternar')[1].click();
    http.expectOne({ method: 'POST', url: '/api/staff/12/activate' }).flush({ ...staff, active: true });
    http.expectOne({ method: 'GET', url: '/api/staff' }).flush([]);
  });

  it('si activar/desactivar falla, muestra el mensaje de la API', async () => {
    await cargar();
    el().querySelector<HTMLButtonElement>('button.alternar')!.click();
    http.expectOne({ method: 'POST', url: '/api/staff/11/deactivate' }).flush(
      { message: 'Miembro no encontrado' },
      { status: 404, statusText: 'Not Found' }
    );
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el().querySelector('[role="alert"]')?.textContent).toContain('Miembro no encontrado');
  });
});
