import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { SessionService } from '../core/session.service';
import { InicioComponent } from './inicio.component';

const progreso = {
  today: '2026-11-07', streak: 12, weeklyGoal: 4, weekCount: 3, weekStart: '2026-11-02', weekEnd: '2026-11-08',
  totalWorkouts: 20
};
const rutina = {
  id: 5, trainerMemberId: 10, trainerName: 'Ana Ruiz', clientMemberId: 20, clientName: 'Luis', name: 'Fuerza A',
  category: 'strength', level: null, estimatedMinutes: 50, estimatedKcal: null, createdAt: '2026-10-01T00:00:00Z',
  active: true, exerciseCount: 1
};
const detalle = {
  ...rutina,
  exercises: [
    { id: 51, exerciseId: 100, exerciseName: 'Press banca', muscleGroup: 'Pecho', imageUrl: null, order: 1, series: 3,
      repetitions: 12, restSeconds: 60 }
  ]
};
const historial = [
  { id: 1, routineId: 5, routineName: 'Fuerza A', performedOn: '2026-11-06', completedCount: 1, totalCount: 1 },
  { id: 2, routineId: 5, routineName: 'Fuerza A', performedOn: '2026-11-05', completedCount: 1, totalCount: 1 },
  { id: 3, routineId: 5, routineName: 'Fuerza A', performedOn: '2026-10-30', completedCount: 1, totalCount: 1 }
];
const pesos = [
  { id: 1, measuredOn: '2026-10-01', weightKg: 70 },
  { id: 2, measuredOn: '2026-11-01', weightKg: 68.6 }
];

describe('InicioComponent', () => {
  let fixture: ComponentFixture<InicioComponent>;
  let http: HttpTestingController;
  const el = () => fixture.nativeElement as HTMLElement;

  async function montar(reservas: object[] = []) {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    TestBed.inject(SessionService).usuario.set({ firstName: 'Laura', lastName: 'Gómez', email: 'l@x.co' });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(InicioComponent);
    fixture.detectChanges();
    http.expectOne('/api/my-progress').flush(progreso);
    http.expectOne('/api/body-weight').flush(pesos);
    http.expectOne('/api/workouts').flush(historial);
    http.expectOne((r) => r.url === '/api/bookings' && r.params.get('status') === 'confirmed').flush(reservas);
    http.expectOne((r) => r.url === '/api/routines' && r.params.get('active') === 'true').flush([rutina]);
    http.expectOne('/api/routines/5').flush(detalle);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  it('saluda por el nombre y muestra racha, meta y peso', async () => {
    await montar();

    expect(el().querySelector('h1')?.textContent).toContain('Hola, Laura');
    expect(el().textContent).toContain('Sábado 7 de noviembre');
    expect(el().textContent).toContain('12 días');
    expect(el().textContent).toContain('3 / 4 sesiones');
    expect(el().textContent).toContain('68.6');
    expect(el().textContent).toContain('-1.4 kg desde el primer registro');
  });

  it('muestra la rutina activa con sus ejercicios', async () => {
    await montar();

    expect(el().textContent).toContain('Fuerza A');
    expect(el().textContent).toContain('Asignada por Ana Ruiz');
    expect(el().textContent).toContain('Press banca');
    expect(el().textContent).toContain('3 × 12');
  });

  it('marca en el calendario solo los días entrenados del mes', async () => {
    await montar();

    const hechos = el().querySelectorAll('.nf-calendario li.hecho');
    expect(hechos.length).toBe(2);
    expect(hechos[0].getAttribute('aria-label')).toBe('5 de noviembre: entrenaste');
    expect(el().textContent).toContain('2 días entrenados');
  });

  it('solo lista clases futuras confirmadas y máximo tres', async () => {
    const futuro = (dia: number) => `2099-01-0${dia}T13:00:00Z`;
    await montar([
      { id: 1, classId: 1, className: 'Spinning', classStartsAt: '2000-01-01T13:00:00Z', branchName: 'Centro' },
      { id: 2, classId: 2, className: 'Yoga', classStartsAt: futuro(3), branchName: 'Centro' },
      { id: 3, classId: 3, className: 'Zumba', classStartsAt: futuro(1), branchName: 'Norte' },
      { id: 4, classId: 4, className: 'Pilates', classStartsAt: futuro(2), branchName: 'Centro' },
      { id: 5, classId: 5, className: 'Boxeo', classStartsAt: futuro(4), branchName: 'Centro' }
    ]);

    const nombres = Array.from(el().querySelectorAll('.nf-proximas strong')).map((e) => e.textContent);
    expect(nombres).toEqual(['Zumba', 'Pilates', 'Yoga']);
  });
});
