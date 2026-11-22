import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AuditoriaComponent } from './auditoria.component';

const evento = {
  id: 1,
  organizationId: 5,
  actorUserId: 9,
  eventType: 'MEMBERSHIP_CANCELLED',
  entityType: 'membership',
  entityId: 4,
  occurredAt: '2026-11-05T15:00:00Z',
  ipAddress: '10.0.0.1',
  metadata: { reason: 'Error de registro' }
};

const pagina = (content: object[], extra: object = {}) => ({
  content,
  page: 0,
  size: 20,
  totalElements: content.length,
  totalPages: content.length ? 1 : 0,
  ...extra
});

describe('AuditoriaComponent', () => {
  let fixture: ComponentFixture<AuditoriaComponent>;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AuditoriaComponent);
    fixture.detectChanges();
  });

  const el = () => fixture.nativeElement as HTMLElement;

  async function estabilizar() {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('muestra los eventos con su nombre en español, la entidad y el motivo', async () => {
    http.expectOne({ method: 'GET', url: '/api/audit/events?page=0&size=20' }).flush(pagina([evento, { ...evento, id: 2, eventType: 'LOGIN', entityType: null, entityId: null, actorUserId: null, metadata: {} }]));
    await estabilizar();
    const filas = el().querySelectorAll('tbody tr');
    expect(filas).toHaveLength(2);
    expect(filas[0].textContent).toContain('Membresía cancelada');
    expect(filas[0].textContent).toContain('membership #4');
    expect(filas[0].textContent).toContain('Error de registro');
    expect(filas[1].textContent).toContain('Inicio de sesión');
    expect(filas[1].textContent).toContain('Sistema');
  });

  it('filtra por tipo y fechas', async () => {
    http.expectOne({ method: 'GET', url: '/api/audit/events?page=0&size=20' }).flush(pagina([]));
    await estabilizar();
    expect(el().textContent).toContain('No hay eventos');
    const tipo = el().querySelector<HTMLSelectElement>('select[name="tipo"]')!;
    tipo.value = 'PAYMENT_VOIDED';
    tipo.dispatchEvent(new Event('change'));
    http.expectOne({ method: 'GET', url: '/api/audit/events?type=PAYMENT_VOIDED&page=0&size=20' }).flush(pagina([]));
    await estabilizar();
    const desde = el().querySelector<HTMLInputElement>('input[name="desde"]')!;
    desde.value = '2026-11-01';
    desde.dispatchEvent(new Event('input'));
    http.expectOne({ method: 'GET', url: '/api/audit/events?from=2026-11-01&type=PAYMENT_VOIDED&page=0&size=20' }).flush(pagina([]));
  });

  it('avisa si no se pudo cargar', async () => {
    http.expectOne({ method: 'GET', url: '/api/audit/events?page=0&size=20' }).flush({}, { status: 500, statusText: 'Error' });
    await estabilizar();
    expect(el().textContent).toContain('No se pudo cargar la auditoría');
  });

  it('pagina: muestra el total, pasa a la siguiente página y conserva el filtro', async () => {
    http
      .expectOne({ method: 'GET', url: '/api/audit/events?page=0&size=20' })
      .flush(pagina([evento], { totalElements: 45, totalPages: 3 }));
    await estabilizar();
    expect(el().textContent).toContain('45 eventos · página 1 de 3');
    expect(el().querySelector<HTMLButtonElement>('button.anterior')!.disabled).toBe(true);
    const tipo = el().querySelector<HTMLSelectElement>('select[name="tipo"]')!;
    tipo.value = 'LOGIN';
    tipo.dispatchEvent(new Event('change'));
    http
      .expectOne({ method: 'GET', url: '/api/audit/events?type=LOGIN&page=0&size=20' })
      .flush(pagina([evento], { totalElements: 45, totalPages: 3 }));
    await estabilizar();
    el().querySelector<HTMLButtonElement>('button.siguiente')!.click();
    http
      .expectOne({ method: 'GET', url: '/api/audit/events?type=LOGIN&page=1&size=20' })
      .flush(pagina([evento], { page: 1, totalElements: 45, totalPages: 3 }));
    await estabilizar();
    expect(el().textContent).toContain('página 2 de 3');
    expect(el().querySelector<HTMLButtonElement>('button.anterior')!.disabled).toBe(false);
  });

  it('al cambiar un filtro vuelve a la primera página', async () => {
    http
      .expectOne({ method: 'GET', url: '/api/audit/events?page=0&size=20' })
      .flush(pagina([evento], { totalElements: 45, totalPages: 3 }));
    await estabilizar();
    el().querySelector<HTMLButtonElement>('button.siguiente')!.click();
    http
      .expectOne({ method: 'GET', url: '/api/audit/events?page=1&size=20' })
      .flush(pagina([evento], { page: 1, totalElements: 45, totalPages: 3 }));
    await estabilizar();
    const tipo = el().querySelector<HTMLSelectElement>('select[name="tipo"]')!;
    tipo.value = 'LOGIN';
    tipo.dispatchEvent(new Event('change'));
    http.expectOne({ method: 'GET', url: '/api/audit/events?type=LOGIN&page=0&size=20' }).flush(pagina([]));
  });
});
