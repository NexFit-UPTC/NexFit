import { Component, computed, input, output } from '@angular/core';

/** Pie de tabla con «N elementos · página X de Y» y los botones Anterior y Siguiente (la página empieza en 0). */
@Component({
  selector: 'app-paginacion',
  template: `
    <div class="paginacion">
      <span class="nf-ayuda">{{ total() }} {{ etiqueta() }} · página {{ pagina() + 1 }} de {{ paginasVisibles() }}</span>
      <button type="button" class="nf-boton-sec anterior" [disabled]="pagina() === 0" (click)="cambiar.emit(pagina() - 1)">
        Anterior
      </button>
      <button
        type="button"
        class="nf-boton-sec siguiente"
        [disabled]="pagina() + 1 >= paginas()"
        (click)="cambiar.emit(pagina() + 1)"
      >
        Siguiente
      </button>
    </div>
  `,
  styles: `
    .paginacion {
      display: flex;
      gap: var(--espacio-3);
      align-items: center;
      justify-content: flex-end;
      margin-top: var(--espacio-3);
    }
  `
})
export class PaginacionComponent {
  readonly pagina = input.required<number>();
  readonly paginas = input.required<number>();
  readonly total = input.required<number>();
  readonly etiqueta = input('resultados');
  readonly cambiar = output<number>();

  /** Con 0 resultados el servidor responde 0 páginas; se muestra «1 de 1». */
  protected readonly paginasVisibles = computed(() => Math.max(this.paginas(), 1));
}
