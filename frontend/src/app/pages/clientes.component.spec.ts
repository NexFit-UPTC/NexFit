import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ClientesComponent } from './clientes.component';

const laura = {
  memberId: 5,
  documentType: 'CC',
  documentNumber: '1010',
  firstName: 'Laura',
  lastName: 'Gómez',
  active: true,
  membershipStatus: 'active',
  membershipEndDate: '2026-12-15'
};
const pedro = {
  ...laura,
  memberId: 6,
  documentNumber: '2020',
  firstName: 'Pedro',
  lastName: 'Ramírez',
  active: false,
  membershipStatus: null,
  membershipEndDate: null
};
const vencido = { ...laura, memberId: 7, firstName: 'Ana', lastName: 'Mora', membershipStatus: 'expired' };

function pagina(content: object[], page = 0, totalPages = 1) {
  return { content, page, size: 20, totalElements: content.length, totalPages };
}

const detalle = {
  ...laura,
  email: 'laura@nexfit.test',
  birthDate: '1995-04-02',
  joinedAt: '2026-10-01T15:00:00Z',
  membership: { status: 'active', planName: 'Mensual', startDate: '2026-11-15', endDate: '2026-12-15' },
  phone: '3001234567',
  epsId: 3,
  epsName: 'Sanitas',
  emergencyContacts: [{ id: 1, fullName: 'Luis Gómez', relationship: 'Padre', phone: '3009999999', primary: true }]
};

describe('ClientesComponent', () => {
  let fixture: ComponentFixture<ClientesComponent>;
  let http: HttpTestingController;

  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ClientesComponent);
    fixture.detectChanges();
  });
  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  const el = () => fixture.nativeElement as HTMLElement;

  async function refrescar() {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function pedirListado() {
    return http.expectOne((r) => r.method === 'GET' && r.url === '/api/clients');
  }

  async function cargar(cuerpo: object = pagina([laura, pedro, vencido])) {
    pedirListado().flush(cuerpo);
    await refrescar();
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

  function elegir(nombre: string, indice: number) {
    const campo = el().querySelector<HTMLSelectElement>(`[name="${nombre}"]`)!;
    campo.selectedIndex = indice;
    campo.dispatchEvent(new Event('change'));
  }

  function boton(texto: string): HTMLButtonElement {
    return Array.from(el().querySelectorAll('button')).find((b) => b.textContent?.trim() === texto)!;
  }

  async function abrirRegistro() {
    boton('Registrar cliente').click();
    fixture.detectChanges();
    http.expectOne({ method: 'GET', url: '/api/health-insurance-providers' }).flush([
      { id: 3, name: 'Sanitas' },
      { id: 4, name: 'Sura' }
    ]);
    await refrescar();
  }

  async function llenarRegistro() {
    escribir('documentNumber', '3030');
    escribir('firstName', 'Camila');
    escribir('lastName', 'Rojas');
    escribir('birthDate', '1999-05-06');
    escribir('email', 'camila@nexfit.test');
    escribir('phone', '3101234567');
    elegir('epsId', 2);
    escribir('contactoNombre', 'Marta Rojas');
    escribir('contactoParentesco', 'Madre');
    escribir('contactoTelefono', '3205550000');
    marcar('healthConsent', true);
    marcar('privacyAccepted', true);
    await refrescar();
  }

  function enviar() {
    el().querySelector('form:not([role="search"])')!.dispatchEvent(new Event('submit'));
  }

  // ---- listado y búsqueda ----------------------------------------------------------------

  it('al abrir carga la primera página sin filtros', async () => {
    const req = pedirListado();
    expect(req.request.params.get('page')).toBe('0');
    expect(req.request.params.get('size')).toBe('20');
    expect(req.request.params.has('q')).toBe(false);
    expect(req.request.params.has('active')).toBe(false);
    req.flush(pagina([]));
  });

  it('muestra nombre, documento, afiliación y membresía en español', async () => {
    await cargar();
    const filas = el().querySelectorAll('tbody tr');
    expect(filas).toHaveLength(3);
    expect(filas[0].textContent).toContain('Laura Gómez');
    expect(filas[0].textContent).toContain('CC 1010');
    expect(filas[0].textContent).toContain('Activa');
    expect(filas[0].textContent).toContain('Vigente');
    expect(filas[0].textContent).toContain('15/12/2026');
    expect(filas[1].textContent).toContain('Inactiva');
    expect(filas[1].textContent).toContain('Sin membresía');
    expect(filas[2].textContent).toContain('Vencida');
  });

  it('sin resultados muestra el aviso', async () => {
    await cargar(pagina([]));
    expect(el().textContent).toContain('No hay clientes que coincidan');
  });

  it('buscar envía el texto y el estado de la afiliación desde la primera página', async () => {
    await cargar();
    escribir('q', '  laura ');
    elegir('estado', 1);
    el().querySelector('form[role="search"]')!.dispatchEvent(new Event('submit'));

    const req = pedirListado();
    expect(req.request.params.get('q')).toBe('laura');
    expect(req.request.params.get('active')).toBe('true');
    expect(req.request.params.get('page')).toBe('0');
    req.flush(pagina([laura]));
  });

  it('la paginación conserva los filtros de la última búsqueda y no lo que se esté escribiendo', async () => {
    await cargar();
    escribir('q', 'laura');
    el().querySelector('form[role="search"]')!.dispatchEvent(new Event('submit'));
    pedirListado().flush(pagina([laura], 0, 3));
    await refrescar();

    escribir('q', 'otra cosa sin buscar');
    boton('Siguiente').click();

    const req = pedirListado();
    expect(req.request.params.get('q')).toBe('laura');
    expect(req.request.params.get('page')).toBe('1');
    req.flush(pagina([laura], 1, 3));
    await refrescar();
    expect(el().textContent).toContain('página 2 de 3');
    expect(boton('Anterior').disabled).toBe(false);
  });

  it('en la primera y última página se desactivan Anterior y Siguiente', async () => {
    await cargar(pagina([laura], 0, 1));
    expect(boton('Anterior').disabled).toBe(true);
    expect(boton('Siguiente').disabled).toBe(true);
  });

  it('si el listado falla, muestra el mensaje de la API', async () => {
    pedirListado().flush({ code: 'ACCESS_DENIED', message: 'Solo Staff y Administrador' }, { status: 403, statusText: 'x' });
    await refrescar();
    expect(el().querySelector('[role="alert"]')?.textContent).toContain('Solo Staff y Administrador');
  });

  // ---- detalle ---------------------------------------------------------------------------------

  it('ver muestra EPS, contactos y membresía, y se puede cerrar', async () => {
    await cargar();
    el().querySelector<HTMLButtonElement>('.ver')!.click();
    http.expectOne({ method: 'GET', url: '/api/clients/5' }).flush(detalle);
    await refrescar();

    const texto = el().textContent!;
    expect(texto).toContain('laura@nexfit.test');
    expect(texto).toContain('Sanitas');
    expect(texto).toContain('Luis Gómez (Padre)');
    expect(texto).toContain('Vigente · Mensual');
    expect(texto).toContain('02/04/1995');
    expect(texto).toContain('queda registrado en auditoría');

    boton('Cerrar').click();
    fixture.detectChanges();
    expect(el().textContent).not.toContain('Sanitas');
  });

  it('si el detalle falla, muestra el mensaje', async () => {
    await cargar();
    el().querySelector<HTMLButtonElement>('.ver')!.click();
    http.expectOne('/api/clients/5').flush({ message: 'Cliente no encontrado' }, { status: 404, statusText: 'x' });
    await refrescar();
    expect(el().textContent).toContain('Cliente no encontrado');
  });

  // ---- registro ---------------------------------------------------------------------------------

  it('registrar abre el formulario con las EPS del catálogo y contacto de emergencia obligatorio', async () => {
    await cargar();
    await abrirRegistro();
    const opciones = Array.from(el().querySelectorAll('select[name="epsId"] option')).map((o) => o.textContent?.trim());
    expect(opciones).toEqual(['Selecciona una EPS', 'Sanitas', 'Sura']);
    expect(el().textContent).toContain('Contacto de emergencia (obligatorio)');
  });

  it('registrar un cliente nuevo: POST con todos los datos y muestra la contraseña temporal una sola vez', async () => {
    await cargar();
    await abrirRegistro();
    await llenarRegistro();
    enviar();

    const req = http.expectOne({ method: 'POST', url: '/api/clients' });
    expect(req.request.body).toEqual({
      documentType: 'CC',
      documentNumber: '3030',
      firstName: 'Camila',
      lastName: 'Rojas',
      birthDate: '1999-05-06',
      email: 'camila@nexfit.test',
      phone: '3101234567',
      epsId: 4,
      emergencyContact: { fullName: 'Marta Rojas', relationship: 'Madre', phone: '3205550000' },
      healthConsent: true,
      privacyAccepted: true
    });
    req.flush({ userId: 30, memberId: 40, temporaryPassword: 'Temp-123-abc' }, { status: 201, statusText: 'Created' });
    pedirListado().flush(pagina([laura]));
    await refrescar();

    expect(el().querySelector('[role="status"]')?.textContent).toContain('Temp-123-abc');
    expect(el().querySelector('form:not([role="search"])')).toBeNull();
    boton('Ya la copié').click();
    fixture.detectChanges();
    expect(el().textContent).not.toContain('Temp-123-abc');
  });

  it('afiliar a una persona que ya existía (200) no muestra contraseña', async () => {
    await cargar();
    await abrirRegistro();
    await llenarRegistro();
    enviar();

    http.expectOne({ method: 'POST', url: '/api/clients' }).flush({ userId: 9, memberId: 41, firstName: 'Camila', lastName: 'Rojas' });
    pedirListado().flush(pagina([laura]));
    await refrescar();

    expect(el().querySelector('[role="status"]')?.textContent).toContain('Camila Rojas ya tenía cuenta');
    expect(el().textContent).not.toContain('Contraseña temporal');
  });

  it('un error de campo se muestra bajo ese campo y el formulario queda abierto', async () => {
    await cargar();
    await abrirRegistro();
    enviar();

    http
      .expectOne({ method: 'POST', url: '/api/clients' })
      .flush({ code: 'VALIDATION_ERROR', field: 'epsId', message: 'La EPS es obligatoria' }, { status: 400, statusText: 'x' });
    await refrescar();

    expect(el().textContent).toContain('La EPS es obligatoria');
    expect(el().querySelector('form:not([role="search"])')).not.toBeNull();
  });

  it('el error del contacto de emergencia se muestra en su bloque', async () => {
    await cargar();
    await abrirRegistro();
    enviar();

    http
      .expectOne({ method: 'POST', url: '/api/clients' })
      .flush(
        { code: 'VALIDATION_ERROR', field: 'emergencyContact', message: 'El contacto de emergencia es obligatorio' },
        { status: 400, statusText: 'x' }
      );
    await refrescar();

    expect(el().querySelector('fieldset [role="alert"]')?.textContent).toContain('contacto de emergencia es obligatorio');
  });

  it('un conflicto sin campo (409) se muestra como mensaje general', async () => {
    await cargar();
    await abrirRegistro();
    enviar();

    http
      .expectOne({ method: 'POST', url: '/api/clients' })
      .flush({ message: 'La persona ya es miembro de este gimnasio' }, { status: 409, statusText: 'x' });
    await refrescar();

    expect(el().querySelector('.nf-alerta[role="alert"]')?.textContent).toContain('ya es miembro de este gimnasio');
  });

  it('si el catálogo de EPS no carga, avisa', async () => {
    await cargar();
    boton('Registrar cliente').click();
    fixture.detectChanges();
    http.expectOne('/api/health-insurance-providers').flush(null, { status: 500, statusText: 'x' });
    await refrescar();
    expect(el().textContent).toContain('No se pudo cargar el catálogo de EPS');
  });

  it('cancelar cierra el formulario sin enviar nada', async () => {
    await cargar();
    await abrirRegistro();
    boton('Cancelar').click();
    fixture.detectChanges();
    expect(el().querySelector('form:not([role="search"])')).toBeNull();
    http.expectNone({ method: 'POST', url: '/api/clients' });
  });

  // ---- ¿la persona ya existe? -------------------------------------------------------------------

  it('buscar por documento exige confirmar la verificación y la presencia', async () => {
    await cargar();
    await abrirRegistro();
    escribir('documentNumber', '3030');
    await refrescar();
    expect(boton('Buscar por documento').disabled).toBe(true);

    marcar('documentoVerificado', true);
    await refrescar();
    expect(boton('Buscar por documento').disabled).toBe(true);

    marcar('personaPresente', true);
    await refrescar();
    expect(boton('Buscar por documento').disabled).toBe(false);
  });

  it('si la persona existe, lo indica; si no, avisa que se creará una cuenta', async () => {
    await cargar();
    await abrirRegistro();
    escribir('documentNumber', '3030');
    marcar('documentoVerificado', true);
    marcar('personaPresente', true);
    await refrescar();

    boton('Buscar por documento').click();
    const req = http.expectOne((r) => r.url === '/api/clients/search');
    expect(req.request.params.get('documentType')).toBe('CC');
    expect(req.request.params.get('documentNumber')).toBe('3030');
    expect(req.request.params.get('documentVerified')).toBe('true');
    expect(req.request.params.get('personPresent')).toBe('true');
    req.flush({ firstName: 'Camila', lastName: 'Rojas' });
    await refrescar();
    expect(el().textContent).toContain('Ya existe: Camila Rojas');

    boton('Buscar por documento').click();
    http.expectOne((r) => r.url === '/api/clients/search').flush({ message: 'Persona no encontrada' }, { status: 404, statusText: 'x' });
    await refrescar();
    expect(el().textContent).toContain('No existe: se creará una cuenta nueva');
    expect(el().textContent).not.toContain('Ya existe');
  });

  it('un error al buscar la persona se muestra', async () => {
    await cargar();
    await abrirRegistro();
    escribir('documentNumber', '3030');
    marcar('documentoVerificado', true);
    marcar('personaPresente', true);
    await refrescar();

    boton('Buscar por documento').click();
    http
      .expectOne((r) => r.url === '/api/clients/search')
      .flush({ code: 'ACCESS_DENIED', message: 'La búsqueda de personas solo está permitida para Staff' }, { status: 403, statusText: 'x' });
    await refrescar();

    expect(el().textContent).toContain('solo está permitida para Staff');
  });
});
