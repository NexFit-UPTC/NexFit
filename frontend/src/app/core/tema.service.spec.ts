import { TestBed } from '@angular/core/testing';
import { ACENTO_DEFECTO, CLAVE_TEMA, contraste, normalizarHex, PRESETS, sobreAcento, TemaService } from './tema.service';

describe('TemaService', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('style');
  });

  it('normaliza hex corto, mayúsculas y sin #', () => {
    expect(normalizarHex('#ABC')).toBe('#aabbcc');
    expect(normalizarHex('c8f03a')).toBe('#c8f03a');
    expect(normalizarHex('#12')).toBeNull();
    expect(normalizarHex('zzzzzz')).toBeNull();
  });

  it('calcula el contraste WCAG', () => {
    expect(contraste('#000000', '#ffffff')).toBeCloseTo(21, 0);
    expect(contraste('#ffffff', '#ffffff')).toBeCloseTo(1, 5);
  });

  it('elige texto oscuro sobre lima y blanco sobre azul oscuro', () => {
    expect(sobreAcento('#c8f03a')).toBe('#11151a');
    expect(sobreAcento('#1d2bd6')).toBe('#ffffff');
  });

  it('aplica acento, sobre-acento y modo al documento y los persiste', () => {
    const tema = TestBed.inject(TemaService);
    tema.cambiarModo('oscuro');
    expect(tema.cambiarAcento('#2563eb')).toBe(true);
    const raiz = document.documentElement;
    expect(raiz.getAttribute('data-theme')).toBe('oscuro');
    expect(raiz.style.getPropertyValue('--acento')).toBe('#2563eb');
    expect(raiz.style.getPropertyValue('--sobre-acento')).toBe(sobreAcento('#2563eb'));
    expect(JSON.parse(localStorage.getItem(CLAVE_TEMA)!)).toEqual({ modo: 'oscuro', acento: '#2563eb' });
  });

  it('rechaza un hex inválido sin cambiar el acento', () => {
    const tema = TestBed.inject(TemaService);
    expect(tema.cambiarAcento('nope')).toBe(false);
    expect(tema.acento()).toBe(ACENTO_DEFECTO);
  });

  it('lee lo guardado y restablece a los valores por defecto', () => {
    localStorage.setItem(CLAVE_TEMA, JSON.stringify({ modo: 'claro', acento: '#ff4d8d' }));
    const tema = TestBed.inject(TemaService);
    expect(tema.modo()).toBe('claro');
    expect(tema.acento()).toBe('#ff4d8d');
    tema.restablecer();
    expect(tema.modo()).toBe('sistema');
    expect(tema.acento()).toBe(ACENTO_DEFECTO);
    expect(localStorage.getItem(CLAVE_TEMA)).toBeNull();
  });

  it('ignora un valor guardado corrupto', () => {
    localStorage.setItem(CLAVE_TEMA, '{no es json');
    expect(TestBed.inject(TemaService).acento()).toBe(ACENTO_DEFECTO);
  });

  it('sigue funcionando si localStorage lanza', () => {
    const espia = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('cuota');
    });
    const tema = TestBed.inject(TemaService);
    expect(() => tema.cambiarAcento('#16a34a')).not.toThrow();
    expect(tema.acento()).toBe('#16a34a');
    espia.mockRestore();
  });

  it('el contraste es bajo solo si ni blanco ni negro llegan a 4,5:1', () => {
    const tema = TestBed.inject(TemaService);
    expect(tema.contrasteBajo()).toBe(false);
    tema.cambiarAcento('#8b5cf6');
    expect(tema.mejorContraste()).toBeLessThan(4.5);
    expect(tema.contrasteBajo()).toBe(true);
    tema.cambiarAcento('#1d2bd6');
    expect(tema.contrasteBajo()).toBe(false);
  });

  it('ningún preset dispara el aviso de contraste', () => {
    const tema = TestBed.inject(TemaService);
    for (const p of PRESETS) {
      tema.cambiarAcento(p.hex);
      expect(tema.contrasteBajo(), `${p.nombre} ${p.hex} (${tema.mejorContraste().toFixed(2)}:1)`).toBe(false);
    }
  });
});
