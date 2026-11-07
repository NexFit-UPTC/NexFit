import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { SessionService } from '../core/session.service';
import { PanelComponent } from './panel.component';

const gimnasio = {
  date: '2026-11-07', timezone: 'America/Bogota', activeClients: 120, currentMemberships: 98,
  expiringMemberships: 7, expiringInDays: 7, monthRevenue: 4500000, todayCheckIns: 31,
  upcomingOccupancy: { classes: 12, confirmedBookings: 90, capacity: 240, percent: 37.5 }
};
const plataforma = { gyms: 4, branches: 9, clients: 650, currentMemberships: 540 };

describe('PanelComponent', () => {
  let fixture: ComponentFixture<PanelComponent>;
  let http: HttpTestingController;

  function montar(roles: string[]) {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    TestBed.inject(SessionService).roles.set(roles);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PanelComponent);
    fixture.detectChanges();
  }
  const el = () => fixture.nativeElement as HTMLElement;
  async function estabilizar() {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('el Administrador ve los seis indicadores de su gimnasio', async () => {
    montar(['admin']);
    http.expectOne({ method: 'GET', url: '/api/dashboard/gym' }).flush(gimnasio);
    await estabilizar();
    expect(el().querySelector('h1')!.textContent).toContain('Panel del gimnasio');
    const texto = el().textContent!;
    expect(el().querySelectorAll('.nf-indicador')).toHaveLength(6);
    expect(texto).toContain('120');
    expect(texto).toContain('Por vencer en 7 días');
    expect(texto).toMatch(/4[.,]500[.,]000/);
    expect(texto).toContain('37.5%');
    expect(texto).toContain('90 de 240 cupos en 12 clases');
    expect(texto).toContain('America/Bogota');
  });

  it('el Master ve solo cifras agregadas de la plataforma', async () => {
    montar(['master']);
    http.expectOne({ method: 'GET', url: '/api/dashboard/platform' }).flush(plataforma);
    await estabilizar();
    expect(el().querySelector('h1')!.textContent).toContain('Panel de la plataforma');
    expect(el().querySelectorAll('.nf-indicador')).toHaveLength(4);
    expect(el().textContent).toContain('Gimnasios activos');
    expect(el().textContent).toContain('650');
  });

  it('muestra un mensaje si no se pueden cargar', async () => {
    montar(['admin']);
    http.expectOne('/api/dashboard/gym').flush('x', { status: 500, statusText: 'Error' });
    await estabilizar();
    expect(el().querySelector('[role="alert"]')!.textContent).toContain('No se pudieron cargar');
  });
});
