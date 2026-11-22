import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { GimnasioDetalleComponent } from './gimnasio-detalle.component';

describe('GimnasioDetalleComponent', () => {
  function montar(id: string) {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: new Map([['id', id]]) } } }
      ]
    });
    const fixture = TestBed.createComponent(GimnasioDetalleComponent);
    fixture.detectChanges();
    return { fixture, http: TestBed.inject(HttpTestingController), el: fixture.nativeElement as HTMLElement };
  }

  it('CA2: muestra sedes y planes con precio y duración', () => {
    const { fixture, http, el } = montar('7');
    http.expectOne('/api/public/gyms/7').flush({
      id: 7,
      name: 'Iron Gym',
      branches: [{ name: 'Centro', addressLine1: 'Calle 1', addressLine2: null, city: 'Tunja', department: 'Boyacá' }],
      plans: [{ name: 'Mensual', price: 90000, durationDays: 30 }],
      contactEmail: 'secreto@gym.co',
      timezone: 'America/Bogota'
    });
    fixture.detectChanges();
    const t = el.textContent!;
    expect(t).toContain('Iron Gym');
    expect(t).toContain('Centro');
    expect(t).toContain('Tunja, Boyacá');
    expect(t).toContain('Mensual');
    expect(t).toMatch(/\$\s?90[.,]000/);
    expect(t).toContain('30 días');
    expect(t).not.toMatch(/@|America\/Bogota/);
    const ingresar = Array.from(el.querySelectorAll('a')).find((a) => a.textContent!.includes('Ingresar al gimnasio'))!;
    expect(ingresar.getAttribute('href')).toBe('/login?gimnasio=7');
  });

  it('CA3: 404 → «Gimnasio no encontrado»', () => {
    const { fixture, http, el } = montar('999');
    http.expectOne('/api/public/gyms/999').flush(null, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();
    expect(el.textContent).toContain('Gimnasio no encontrado');
    expect(el.querySelector('[role=alert]')).toBeNull();
  });

  it('CA3: id no numérico → «no encontrado» sin petición', () => {
    const { http, el } = montar('abc');
    http.expectNone(() => true);
    expect(el.textContent).toContain('Gimnasio no encontrado');
  });

  it('CA7: error de red o 5xx → alerta con Reintentar, no «no encontrado»', () => {
    const { fixture, http, el } = montar('7');
    http.expectOne('/api/public/gyms/7').flush(null, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')?.textContent).toContain('No se pudo cargar');
    expect(el.textContent).not.toContain('Gimnasio no encontrado');
    el.querySelector<HTMLButtonElement>('.reintentar')!.click();
    http.expectOne('/api/public/gyms/7').error(new ProgressEvent('error'));
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')).toBeTruthy();
  });

  it('CA7: muestra «cargando» mientras espera', () => {
    const { el } = montar('7');
    expect(el.querySelector('[role=status]')?.textContent).toContain('Cargando');
  });
});
