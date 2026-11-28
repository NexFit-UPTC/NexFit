import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { SessionService } from '../core/session.service';
import { CompletarRegistroComponent } from './completar-registro.component';

const PENDIENTE = {
  email: 'ana@gmail.com',
  firstName: 'Ana María',
  lastName: 'Pérez',
  policy: { version: '0.1-borrador', effectiveDate: '2026-10-18', privacyText: 'Texto de la política vigente' }
};

describe('CompletarRegistroComponent', () => {
  let fixture: ComponentFixture<CompletarRegistroComponent>;
  let http: HttpTestingController;
  let router: Router;
  let sesion: SessionService;

  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    sessionStorage.removeItem('nexfit.gimnasio');
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    sesion = TestBed.inject(SessionService);
    sesion.estado.set('anonimo');
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
  });
  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
    sessionStorage.removeItem('nexfit.gimnasio');
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const campo = (id: string) => el().querySelector<HTMLInputElement>('#' + id)!;
  const enlaceGoogle = () => el().querySelector<HTMLAnchorElement>('a[href="/api/oauth2/authorization/google"]');

  /** Abre la pantalla y deja pasar el GET del pendiente (null = sin pendiente, 404). */
  async function abrir(pendiente: object | null = PENDIENTE) {
    fixture = TestBed.createComponent(CompletarRegistroComponent);
    fixture.detectChanges();
    const req = http.expectOne({ method: 'GET', url: '/api/auth/google-registration' });
    if (pendiente) req.flush(pendiente);
    else req.flush({ code: 'NO_PENDING_REGISTRATION' }, { status: 404, statusText: 'Not Found' });
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function escribir(id: string, valor: string) {
    campo(id).value = valor;
    campo(id).dispatchEvent(new Event(id === 'documentType' ? 'change' : 'input'));
  }

  function llenarFaltantes() {
    escribir('documentType', 'CE');
    escribir('documentNumber', ' 12345 ');
    escribir('birthDate', '1990-05-17');
  }

  function aceptar() {
    campo('acepto').checked = true;
    campo('acepto').dispatchEvent(new Event('change'));
  }

  async function enviar() {
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('mientras lee el pendiente muestra «Cargando» y todavía no hay formulario', () => {
    fixture = TestBed.createComponent(CompletarRegistroComponent);
    fixture.detectChanges();
    expect(el().textContent).toContain('Cargando tus datos de Google');
    expect(el().querySelector('form')).toBeNull();
    http.expectOne('/api/auth/google-registration');
  });

  it('precarga nombre y apellido (editables) y el correo de solo lectura; sin contraseña', async () => {
    await abrir();
    expect(el().querySelector('h1')?.textContent).toContain('Antes de crear tu cuenta necesitamos unos datos');
    expect(campo('firstName').value).toBe('Ana María');
    expect(campo('lastName').value).toBe('Pérez');
    expect(campo('firstName').readOnly).toBe(false);
    expect(campo('lastName').readOnly).toBe(false);
    expect(campo('email').value).toBe('ana@gmail.com');
    expect(campo('email').readOnly).toBe(true);
    expect(campo('password')).toBeNull();
    expect(campo('confirmar')).toBeNull();
    expect(campo('documentNumber')).toBeTruthy();
    expect(campo('birthDate').type).toBe('date');
    expect(el().textContent).toContain('Texto de la política vigente');
    expect(el().textContent).toContain('Acepto la política de tratamiento de datos (versión 0.1-borrador)');
    expect(campo('acepto').type).toBe('checkbox');
  });

  it('si Google no entregó nombre, los campos quedan vacíos para escribirlos', async () => {
    await abrir({ ...PENDIENTE, firstName: '', lastName: '' });
    expect(campo('firstName').value).toBe('');
    expect(campo('lastName').value).toBe('');
  });

  it('envía los datos con acceptPolicy, sin correo ni contraseña, y sigue como un ingreso', async () => {
    sessionStorage.setItem('nexfit.gimnasio', '7');
    await abrir();
    escribir('lastName', ' Gómez ');
    llenarFaltantes();
    aceptar();
    const hecho = enviar();
    const req = http.expectOne({ method: 'POST', url: '/api/auth/google-registration' });
    expect(req.request.body).toEqual({
      documentType: 'CE',
      documentNumber: '12345',
      firstName: 'Ana María',
      lastName: 'Gómez',
      birthDate: '1990-05-17',
      acceptPolicy: true
    });
    req.flush({ userId: 9 }, { status: 201, statusText: 'Created' });
    await Promise.resolve();
    http.expectOne('/api/me').flush({ code: 'ESTABLISHMENT_REQUIRED' }, { status: 403, statusText: '' });
    await hecho;
    expect(sesion.estado()).toBe('sin-establecimiento');
    expect(sessionStorage.getItem('nexfit.gimnasio')).toBe('7');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/');
    http.expectNone('/api/auth/register');
  });

  it('sin marcar la casilla ni llenar lo que falta no llama a la API y lo dice junto a cada campo', async () => {
    await abrir();
    await enviar();
    http.expectNone('/api/auth/google-registration');
    expect(el().textContent).toContain('Escribe tu número de documento.');
    expect(el().textContent).toContain('Escribe tu fecha de nacimiento.');
    expect(el().textContent).toContain('Debes aceptar la política para crear tu cuenta.');
  });

  it('400: el mensaje del servidor aparece junto al campo de `field`', async () => {
    await abrir();
    llenarFaltantes();
    aceptar();
    const hecho = enviar();
    http
      .expectOne('/api/auth/google-registration')
      .flush(
        { code: 'VALIDATION_ERROR', field: 'birthDate', message: 'La fecha debe tener el formato AAAA-MM-DD' },
        { status: 400, statusText: 'Bad Request' }
      );
    await hecho;
    expect(el().querySelector('#birthDate-error')?.textContent).toContain('La fecha debe tener el formato');
    expect(el().querySelector('[role=alert]')).toBeNull();
  });

  it('409 DOCUMENT_TAKEN: marca el documento, conserva lo escrito y reenvía sin volver a Google', async () => {
    await abrir();
    llenarFaltantes();
    aceptar();
    const hecho = enviar();
    http
      .expectOne('/api/auth/google-registration')
      .flush(
        { code: 'DOCUMENT_TAKEN', field: 'documentNumber', message: 'Ya hay una cuenta con este número de documento' },
        { status: 409, statusText: 'Conflict' }
      );
    await hecho;
    expect(el().querySelector('#documentNumber-error')?.textContent).toContain(
      'Ya hay una cuenta con este número de documento'
    );
    expect(el().querySelector('[role=alert]')).toBeNull();
    // El formulario sigue ahí, con lo que la persona había escrito; no se pide volver a verificar con Google.
    expect(campo('firstName').value).toBe('Ana María');
    expect(campo('birthDate').value).toBe('1990-05-17');
    expect(campo('acepto').checked).toBe(true);
    expect(enlaceGoogle()).toBeNull();

    escribir('documentNumber', '67890');
    const segundo = enviar();
    http.expectNone({ method: 'GET', url: '/api/auth/google-registration' });
    const req = http.expectOne({ method: 'POST', url: '/api/auth/google-registration' });
    expect(req.request.body.documentNumber).toBe('67890');
    req.flush({ userId: 9 }, { status: 201, statusText: 'Created' });
    await Promise.resolve();
    http.expectOne('/api/me').flush({ code: 'ESTABLISHMENT_REQUIRED' }, { status: 403, statusText: '' });
    await segundo;
    expect(el().querySelector('#documentNumber-error')).toBeNull();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/');
  });

  it('409 EMAIL_TAKEN: mensaje bajo el correo y enlace «Continuar con Google»', async () => {
    await abrir();
    llenarFaltantes();
    aceptar();
    const hecho = enviar();
    http
      .expectOne('/api/auth/google-registration')
      .flush(
        { code: 'EMAIL_TAKEN', field: 'email', message: 'Ya hay una cuenta con este correo' },
        { status: 409, statusText: 'Conflict' }
      );
    await hecho;
    expect(el().querySelector('#email-error')?.textContent).toContain('Ya hay una cuenta con este correo');
    expect(campo('email').readOnly).toBe(true);
    expect(enlaceGoogle()?.textContent).toContain('Continuar con Google');
    expect(el().querySelector('[role=alert]')).toBeNull();
  });

  it('409 ACCOUNT_TAKEN (sin campo): alerta general con el mensaje del servidor', async () => {
    await abrir();
    llenarFaltantes();
    aceptar();
    const hecho = enviar();
    http
      .expectOne('/api/auth/google-registration')
      .flush({ code: 'ACCOUNT_TAKEN', message: 'Ya existe una cuenta con esos datos' }, { status: 409, statusText: 'Conflict' });
    await hecho;
    expect(el().querySelector('[role=alert]')?.textContent).toContain('Ya existe una cuenta con esos datos');
  });

  it('GET 404: ofrece volver a verificar con Google y no muestra ningún formulario', async () => {
    await abrir(null);
    expect(el().textContent).toContain('La verificación con Google venció o no se inició');
    expect(enlaceGoogle()?.textContent).toContain('Vuelve a verificar con Google');
    expect(el().querySelector('form')).toBeNull();
    expect(campo('password')).toBeNull();
  });

  it('POST 404 (el pendiente venció mientras escribía): ofrece volver a verificar con Google', async () => {
    await abrir();
    llenarFaltantes();
    aceptar();
    const hecho = enviar();
    http
      .expectOne('/api/auth/google-registration')
      .flush({ code: 'NO_PENDING_REGISTRATION' }, { status: 404, statusText: 'Not Found' });
    await hecho;
    expect(enlaceGoogle()?.textContent).toContain('Vuelve a verificar con Google');
    expect(el().querySelector('form')).toBeNull();
  });

  it('falla de red al leer el pendiente: mensaje y «Reintentar» que vuelve a pedirlo', async () => {
    fixture = TestBed.createComponent(CompletarRegistroComponent);
    fixture.detectChanges();
    http.expectOne('/api/auth/google-registration').error(new ProgressEvent('error'));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el().querySelector('[role=alert]')?.textContent).toContain('No se pudo conectar');
    expect(enlaceGoogle()).toBeNull();

    el().querySelector<HTMLButtonElement>('button.nf-boton')!.click();
    http.expectOne('/api/auth/google-registration').flush(PENDIENTE);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(campo('email').value).toBe('ana@gmail.com');
  });

  it('con sesión activa redirige a /; también si /api/me llega después de pintar', async () => {
    sesion.estado.set('cargando');
    await abrir();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    sesion.estado.set('activa');
    fixture.detectChanges();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/');
  });
});
