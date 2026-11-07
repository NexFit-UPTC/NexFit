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
    http.expectOne({ method: 'GET', url: '/api/audit/events' }).flush([evento, { ...evento, id: 2, eventType: 'LOGIN', entityType: null, entityId: null, actorUserId: null, metadata: {} }]);
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
    http.expectOne({ method: 'GET', url: '/api/audit/events' }).flush([]);
    await estabilizar();
    expect(el().textContent).toContain('No hay eventos');
    const tipo = el().querySelector<HTMLSelectElement>('select[name="tipo"]')!;
    tipo.value = 'PAYMENT_VOIDED';
    tipo.dispatchEvent(new Event('change'));
    http.expectOne({ method: 'GET', url: '/api/audit/events?type=PAYMENT_VOIDED' }).flush([]);
    await estabilizar();
    const desde = el().querySelector<HTMLInputElement>('input[name="desde"]')!;
    desde.value = '2026-11-01';
    desde.dispatchEvent(new Event('input'));
    http.expectOne({ method: 'GET', url: '/api/audit/events?from=2026-11-01&type=PAYMENT_VOIDED' }).flush([]);
  });

  it('avisa si no se pudo cargar', async () => {
    http.expectOne({ method: 'GET', url: '/api/audit/events' }).flush({}, { status: 500, statusText: 'Error' });
    await estabilizar();
    expect(el().textContent).toContain('No se pudo cargar la auditoría');
  });
});
