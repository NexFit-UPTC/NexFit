import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MisAlumnosComponent } from './mis-alumnos.component';

describe('MisAlumnosComponent', () => {
  let fixture: ComponentFixture<MisAlumnosComponent>;
  let http: HttpTestingController;
  const el = () => fixture.nativeElement as HTMLElement;

  async function montar() {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(MisAlumnosComponent);
    fixture.detectChanges();
    return http.expectOne({ method: 'GET', url: '/api/my-students' });
  }

  async function estabilizar() {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('muestra cada alumno con edad, rutinas, última asistencia y contacto de emergencia', async () => {
    const req = await montar();
    req.flush([
      {
        memberId: 20, name: 'Luis Gómez', age: 34,
        emergencyContact: { fullName: 'Marta Gómez', relationship: 'Madre', phone: '3001112233' },
        activeRoutines: 2, lastCheckIn: '2026-11-01'
      },
      { memberId: 21, name: 'Eva Soto', age: null, emergencyContact: null, activeRoutines: 0, lastCheckIn: null }
    ]);
    await estabilizar();
    const tarjetas = el().querySelectorAll('li');
    expect(tarjetas).toHaveLength(2);
    expect(tarjetas[0].textContent).toContain('34 años');
    expect(tarjetas[0].textContent).toContain('2 rutinas activas');
    expect(tarjetas[0].textContent).toContain('01/11/2026');
    expect(tarjetas[0].textContent).toContain('Marta Gómez (Madre)');
    expect(tarjetas[0].querySelector('a')?.getAttribute('href')).toBe('tel:3001112233');
    expect(tarjetas[1].textContent).toContain('Edad no registrada');
    expect(tarjetas[1].textContent).toContain('sin asistencias');
    expect(tarjetas[1].textContent).toContain('Sin contacto de emergencia');
  });

  it('invita a pedir la asignación cuando no hay alumnos', async () => {
    const req = await montar();
    req.flush([]);
    await estabilizar();
    expect(el().querySelector('.nf-vacio')?.textContent).toContain('Aún no tienes alumnos');
  });

  it('muestra el error si falla la consulta', async () => {
    const req = await montar();
    req.flush({ message: 'Sin permiso' }, { status: 403, statusText: 'Forbidden' });
    await estabilizar();
    expect(el().querySelector('[role="alert"]')?.textContent).toContain('Sin permiso');
  });
});
