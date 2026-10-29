import { DOCUMENT } from '@angular/common';
import { Injectable, computed, inject, signal } from '@angular/core';

export type ModoTema = 'claro' | 'oscuro' | 'sistema';

export interface PresetAcento {
  nombre: string;
  hex: string;
}

export const ACENTO_DEFECTO = '#c8f03a';
export const MODO_DEFECTO: ModoTema = 'sistema';
export const CLAVE_TEMA = 'nexfit.tema';
/** Mínimo WCAG AA para texto normal. */
export const CONTRASTE_MINIMO = 4.5;

export const PRESETS: PresetAcento[] = [
  { nombre: 'Lima', hex: '#c8f03a' },
  { nombre: 'Celeste', hex: '#45c8f5' },
  { nombre: 'Azul', hex: '#2563eb' },
  { nombre: 'Violeta', hex: '#7c3aed' },
  { nombre: 'Rosa', hex: '#ff4d8d' },
  { nombre: 'Naranja', hex: '#ff8a1f' },
  { nombre: 'Amarillo', hex: '#ffd60a' },
  { nombre: 'Verde', hex: '#16a34a' }
];

const OSCURO = '#11151a';
const BLANCO = '#ffffff';

/** Acepta "#abc" o "#aabbcc" (con o sin #) y devuelve "#aabbcc" en minúsculas, o null si no es válido. */
export function normalizarHex(valor: string): string | null {
  const m = /^#?([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(valor.trim());
  if (!m) {
    return null;
  }
  const h = m[1].length === 3 ? [...m[1]].map((c) => c + c).join('') : m[1];
  return `#${h.toLowerCase()}`;
}

export function luminancia(hex: string): number {
  const canal = (i: number) => {
    const c = parseInt(hex.slice(1 + i * 2, 3 + i * 2), 16) / 255;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * canal(0) + 0.7152 * canal(1) + 0.0722 * canal(2);
}

export function contraste(a: string, b: string): number {
  const la = luminancia(a);
  const lb = luminancia(b);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

/** Color de texto (oscuro o blanco) con mayor contraste sobre el acento. */
export function sobreAcento(acento: string): string {
  return contraste(acento, OSCURO) >= contraste(acento, BLANCO) ? OSCURO : BLANCO;
}

interface Guardado {
  modo: ModoTema;
  acento: string;
}

@Injectable({ providedIn: 'root' })
export class TemaService {
  private readonly doc = inject(DOCUMENT);

  readonly modo = signal<ModoTema>(MODO_DEFECTO);
  readonly acento = signal<string>(ACENTO_DEFECTO);
  private readonly sistemaOscuro = signal(false);

  /** Modo efectivo tras resolver "sistema". */
  readonly modoEfectivo = computed<'claro' | 'oscuro'>(() => {
    const m = this.modo();
    return m === 'sistema' ? (this.sistemaOscuro() ? 'oscuro' : 'claro') : m;
  });
  readonly sobreAcento = computed(() => sobreAcento(this.acento()));
  /** Contraste del mejor texto (blanco u oscuro) sobre el acento. */
  readonly mejorContraste = computed(() => contraste(this.acento(), this.sobreAcento()));
  readonly contrasteBajo = computed(() => this.mejorContraste() < CONTRASTE_MINIMO);

  constructor() {
    this.leer();
    const consulta = this.doc.defaultView?.matchMedia?.('(prefers-color-scheme: dark)');
    if (consulta) {
      this.sistemaOscuro.set(consulta.matches);
      consulta.addEventListener('change', (e) => {
        this.sistemaOscuro.set(e.matches);
        this.aplicar();
      });
    }
    this.aplicar();
  }

  cambiarModo(modo: ModoTema): void {
    this.modo.set(modo);
    this.guardarYAplicar();
  }

  /** Devuelve false si el hex no es válido (no cambia nada). */
  cambiarAcento(valor: string): boolean {
    const hex = normalizarHex(valor);
    if (!hex) {
      return false;
    }
    this.acento.set(hex);
    this.guardarYAplicar();
    return true;
  }

  restablecer(): void {
    this.modo.set(MODO_DEFECTO);
    this.acento.set(ACENTO_DEFECTO);
    try {
      this.doc.defaultView?.localStorage.removeItem(CLAVE_TEMA);
    } catch {
      // Almacenamiento no disponible: el tema solo dura la sesión.
    }
    this.aplicar();
  }

  private leer(): void {
    try {
      const crudo = this.doc.defaultView?.localStorage.getItem(CLAVE_TEMA);
      if (!crudo) {
        return;
      }
      const g = JSON.parse(crudo) as Partial<Guardado>;
      if (g.modo === 'claro' || g.modo === 'oscuro' || g.modo === 'sistema') {
        this.modo.set(g.modo);
      }
      const hex = typeof g.acento === 'string' ? normalizarHex(g.acento) : null;
      if (hex) {
        this.acento.set(hex);
      }
    } catch {
      // Valor corrupto o almacenamiento bloqueado: se usan los valores por defecto.
    }
  }

  private guardarYAplicar(): void {
    try {
      const g: Guardado = { modo: this.modo(), acento: this.acento() };
      this.doc.defaultView?.localStorage.setItem(CLAVE_TEMA, JSON.stringify(g));
    } catch {
      // Sin persistencia (modo privado o cuota): el cambio sigue aplicándose en esta sesión.
    }
    this.aplicar();
  }

  private aplicar(): void {
    const raiz = this.doc.documentElement;
    raiz.setAttribute('data-theme', this.modoEfectivo());
    raiz.style.setProperty('--acento', this.acento());
    raiz.style.setProperty('--sobre-acento', this.sobreAcento());
  }
}
