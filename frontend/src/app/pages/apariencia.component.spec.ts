import { TestBed } from '@angular/core/testing';
import { ACENTO_DEFECTO, TemaService } from '../core/tema.service';
import { AparienciaComponent } from './apariencia.component';

describe('AparienciaComponent', () => {
  beforeEach(() => localStorage.clear());

  function montar() {
    const fixture = TestBed.createComponent(AparienciaComponent);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement, tema: TestBed.inject(TemaService) };
  }

  it('muestra 3 modos y 8 presets', () => {
    const { el } = montar();
    expect(el.querySelectorAll('.modo').length).toBe(3);
    expect(el.querySelectorAll('.preset').length).toBe(8);
  });

  it('elegir un preset cambia el acento', () => {
    const { fixture, el, tema } = montar();
    (el.querySelectorAll('.preset')[2] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(tema.acento()).toBe('#2563eb');
  });

  it('un hex válido se aplica y uno inválido muestra error', () => {
    const { fixture, el, tema } = montar();
    const campo = el.querySelector('#hex') as HTMLInputElement;
    campo.value = '#ff4d8d';
    campo.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(tema.acento()).toBe('#ff4d8d');
    campo.value = '#xyz';
    campo.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(el.querySelector('.nf-error-campo')).not.toBeNull();
    expect(tema.acento()).toBe('#ff4d8d');
  });

  it('no avisa con el lima por defecto en ningún modo', () => {
    const { fixture, el, tema } = montar();
    for (const modo of ['claro', 'oscuro'] as const) {
      tema.cambiarModo(modo);
      fixture.detectChanges();
      expect(el.querySelector('[role="status"]')).toBeNull();
    }
  });

  it('avisa si el mejor texto sobre el acento queda bajo 4,5:1', () => {
    const { fixture, el, tema } = montar();
    tema.cambiarAcento('#8b5cf6');
    fixture.detectChanges();
    expect(el.querySelector('[role="status"]')).not.toBeNull();
    tema.cambiarAcento('#1d2bd6');
    fixture.detectChanges();
    expect(el.querySelector('[role="status"]')).toBeNull();
  });

  it('Restablecer vuelve al acento por defecto', () => {
    const { fixture, el, tema } = montar();
    tema.cambiarAcento('#16a34a');
    (el.querySelector('.nf-boton-texto') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(tema.acento()).toBe(ACENTO_DEFECTO);
    expect((el.querySelector('#hex') as HTMLInputElement).value).toBe(ACENTO_DEFECTO);
  });
});
