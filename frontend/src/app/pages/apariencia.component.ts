import { Component, inject, signal } from '@angular/core';
import { ModoTema, PRESETS, TemaService } from '../core/tema.service';

const MODOS: { valor: ModoTema; titulo: string }[] = [
  { valor: 'claro', titulo: 'Claro' },
  { valor: 'oscuro', titulo: 'Oscuro' },
  { valor: 'sistema', titulo: 'Sistema' }
];

@Component({
  selector: 'app-apariencia',
  template: `
    <h1>Apariencia</h1>

    <section class="bloque" aria-labelledby="t-modo">
      <h2 id="t-modo">Modo</h2>
      <div class="opciones" role="radiogroup" aria-labelledby="t-modo">
        @for (m of modos; track m.valor) {
          <button
            type="button"
            class="modo"
            role="radio"
            [attr.aria-checked]="tema.modo() === m.valor"
            [class.elegido]="tema.modo() === m.valor"
            (click)="tema.cambiarModo(m.valor)"
          >
            {{ m.titulo }}
          </button>
        }
      </div>
    </section>

    <section class="bloque" aria-labelledby="t-acento">
      <h2 id="t-acento">Color de acento</h2>
      <div class="opciones" role="radiogroup" aria-labelledby="t-acento">
        @for (p of presets; track p.hex) {
          <button
            type="button"
            class="preset"
            role="radio"
            [attr.aria-checked]="tema.acento() === p.hex"
            [attr.aria-label]="p.nombre"
            [class.elegido]="tema.acento() === p.hex"
            (click)="elegirPreset(p.hex)"
          >
            <span class="muestra" [style.background]="p.hex"></span>
            {{ p.nombre }}
          </button>
        }
      </div>

      <div class="nf-campo hex">
        <label for="hex">Color personalizado (hex)</label>
        <input
          id="hex"
          class="nf-entrada"
          type="text"
          maxlength="7"
          autocomplete="off"
          placeholder="#c8f03a"
          [value]="hex()"
          [attr.aria-invalid]="hexInvalido()"
          (input)="escribirHex($any($event.target).value)"
        />
        @if (hexInvalido()) {
          <p class="nf-error-campo" role="alert">Escribe un color como #c8f03a o #cf3.</p>
        }
        @if (tema.contrasteBajo()) {
          <p class="nf-alerta" role="status">
            Con este acento el texto de los botones tendría poco contraste ({{ tema.mejorContraste().toFixed(1) }}:1,
            se recomienda al menos 4,5:1). Prueba otro tono.
          </p>
        }
      </div>
    </section>

    <div class="vista" aria-hidden="true">
      <span class="nf-boton">Botón de ejemplo</span>
    </div>

    <button type="button" class="nf-boton-texto" (click)="restablecer()">Restablecer</button>
  `,
  styles: `
    :host {
      display: grid;
      gap: var(--espacio-4);
      max-width: 640px;
    }

    h1,
    h2 {
      margin: 0;
    }

    h2 {
      font-size: 1.0625rem;
    }

    .bloque {
      display: grid;
      gap: var(--espacio-3);
      padding: var(--espacio-4);
      background: var(--superficie);
      border: 1px solid var(--borde);
      border-radius: var(--radio);
    }

    .opciones {
      display: flex;
      flex-wrap: wrap;
      gap: var(--espacio-2);
    }

    .modo,
    .preset {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      padding: 8px 14px;
      font: inherit;
      color: var(--texto);
      background: var(--fondo);
      border: 1px solid var(--borde);
      border-radius: 999px;
      cursor: pointer;
    }

    .elegido {
      font-weight: 600;
      border-color: var(--texto);
      box-shadow: inset 0 0 0 1px var(--texto);
    }

    .muestra {
      width: 18px;
      height: 18px;
      border: 1px solid var(--borde);
      border-radius: 50%;
    }

    .hex {
      max-width: 280px;
    }

    .vista {
      display: flex;
    }

    .nf-boton-texto {
      justify-self: start;
    }
  `
})
export class AparienciaComponent {
  protected readonly tema = inject(TemaService);
  protected readonly modos = MODOS;
  protected readonly presets = PRESETS;
  protected readonly hex = signal(this.tema.acento());
  protected readonly hexInvalido = signal(false);

  protected elegirPreset(hex: string): void {
    this.tema.cambiarAcento(hex);
    this.hex.set(hex);
    this.hexInvalido.set(false);
  }

  protected escribirHex(valor: string): void {
    this.hex.set(valor);
    // Vacío no es error mientras se escribe; se aplica solo cuando el hex es válido.
    this.hexInvalido.set(valor.trim() !== '' && !this.tema.cambiarAcento(valor));
  }

  protected restablecer(): void {
    this.tema.restablecer();
    this.hex.set(this.tema.acento());
    this.hexInvalido.set(false);
  }
}
