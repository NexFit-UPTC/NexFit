import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PaginacionComponent } from './paginacion.component';

describe('PaginacionComponent', () => {
  let fixture: ComponentFixture<PaginacionComponent>;

  function montar(pagina: number, paginas: number, total: number) {
    fixture = TestBed.createComponent(PaginacionComponent);
    fixture.componentRef.setInput('pagina', pagina);
    fixture.componentRef.setInput('paginas', paginas);
    fixture.componentRef.setInput('total', total);
    fixture.componentRef.setInput('etiqueta', 'eventos');
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const boton = (clase: string) => el().querySelector<HTMLButtonElement>(`button.${clase}`)!;

  it('muestra el total y la página, y deshabilita Anterior en la primera', () => {
    montar(0, 3, 45);
    expect(el().textContent).toContain('45 eventos · página 1 de 3');
    expect(boton('anterior').disabled).toBe(true);
    expect(boton('siguiente').disabled).toBe(false);
  });

  it('deshabilita Siguiente en la última y emite la página pedida', () => {
    montar(2, 3, 45);
    let pedida = -1;
    fixture.componentInstance.cambiar.subscribe((p) => (pedida = p));
    expect(boton('siguiente').disabled).toBe(true);
    boton('anterior').click();
    expect(pedida).toBe(1);
  });

  it('sin resultados muestra «página 1 de 1» y no deja avanzar', () => {
    montar(0, 0, 0);
    expect(el().textContent).toContain('0 eventos · página 1 de 1');
    expect(boton('siguiente').disabled).toBe(true);
  });
});
