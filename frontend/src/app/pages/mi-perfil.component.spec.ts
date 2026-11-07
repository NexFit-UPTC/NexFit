import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { SessionService } from '../core/session.service';
import { MiPerfilComponent } from './mi-perfil.component';

const identity = {
  documentType: 'CC',
  documentNumber: '1049',
  firstName: 'Ana',
  lastName: 'Pérez',
  birthDate: '1995-03-10',
  email: 'ana@nexfit.test'
};
const afiliacion = (roles: string[]) => ({
  organizationId: 5,
  organizationName: 'Gimnasio Central',
  roles,
  phone: '3001234567',
  epsId: 2,
  epsName: 'Sanitas',
  emergencyContacts: [{ id: 1, fullName: 'Luis Pérez', relationship: 'Padre', phone: '3000000000', primary: true }]
});
const membresia = {
  id: 1,
  memberId: 7,
  memberName: 'Ana Pérez',
  planId: 3,
  planName: 'Mensual',
  startDate: '2026-11-01',
  endDate: '2026-11-30',
  status: 'active',
  effectiveStatus: 'current',
  statusReason: null,
  statusChangedAt: null,
  createdAt: '2026-11-01T10:00:00Z'
};

describe('MiPerfilComponent', () => {
  let fixture: ComponentFixture<MiPerfilComponent>;
  let http: HttpTestingController;

  function montar(roles: string[]) {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    TestBed.inject(SessionService).roles.set(roles);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(MiPerfilComponent);
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

  it('el Cliente ve identidad, EPS, contactos y su membresía vigente', async () => {
    montar(['client']);
    http.expectOne({ method: 'GET', url: '/api/me' }).flush({ identity, affiliation: afiliacion(['client']) });
    await estabilizar();
    http.expectOne({ method: 'GET', url: '/api/memberships' }).flush([membresia]);
    await estabilizar();
    const texto = el().textContent!;
    expect(texto).toContain('Ana Pérez');
    expect(texto).toContain('ana@nexfit.test');
    expect(texto).toContain('Gimnasio Central');
    expect(texto).toContain('Sanitas');
    expect(texto).toContain('Luis Pérez');
    expect(texto).toContain('Mensual');
    expect(texto).toContain('Vigente');
  });

  it('el personal no ve EPS ni membresías y no consulta /memberships', async () => {
    montar(['staff']);
    http.expectOne({ method: 'GET', url: '/api/me' }).flush({ identity, affiliation: afiliacion(['staff']) });
    await estabilizar();
    expect(el().textContent).not.toContain('Sanitas');
    expect(el().textContent).not.toContain('Mi membresía');
    http.verify();
  });

  it('el personal edita solo el teléfono', async () => {
    montar(['staff']);
    http.expectOne({ method: 'GET', url: '/api/me' }).flush({ identity, affiliation: afiliacion(['staff']) });
    await estabilizar();
    boton('Editar mis datos')!.click();
    await estabilizar();
    const phone = el().querySelector<HTMLInputElement>('input[name="phone"]')!;
    phone.value = '3119998888';
    phone.dispatchEvent(new Event('input'));
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    const req = http.expectOne({ method: 'PATCH', url: '/api/me' });
    expect(req.request.body).toEqual({ phone: '3119998888' });
    req.flush({ identity, affiliation: { ...afiliacion(['staff']), phone: '3119998888' } });
    await estabilizar();
    expect(el().textContent).toContain('3119998888');
    expect(el().textContent).toContain('Tus datos se actualizaron');
  });

  it('el Cliente edita teléfono, EPS y contactos; muestra el rechazo del servidor', async () => {
    montar(['client']);
    http.expectOne({ method: 'GET', url: '/api/me' }).flush({ identity, affiliation: afiliacion(['client']) });
    await estabilizar();
    http.expectOne({ method: 'GET', url: '/api/memberships' }).flush([]);
    boton('Editar mis datos')!.click();
    http.expectOne({ method: 'GET', url: '/api/health-insurance-providers' }).flush([{ id: 2, name: 'Sanitas' }, { id: 3, name: 'Nueva EPS' }]);
    await estabilizar();
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    const req = http.expectOne({ method: 'PATCH', url: '/api/me' });
    expect(req.request.body).toEqual({
      phone: '3001234567',
      epsId: 2,
      healthConsent: false,
      emergencyContacts: [{ fullName: 'Luis Pérez', relationship: 'Padre', phone: '3000000000', primary: true }]
    });
    req.flush({ message: 'La EPS requiere autorización para tratar tus datos de salud' }, { status: 400, statusText: 'Bad Request' });
    await estabilizar();
    expect(el().textContent).toContain('autorización');
  });
});
