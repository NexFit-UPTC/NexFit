/** Página de un listado del servidor (mismo contrato en auditoría, personal, clientes y gimnasios). */
export interface Pagina<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** Tamaño de página por defecto y máximo que acepta el servidor (BE-12). */
export const TAMANO_PAGINA = 20;
export const TAMANO_MAXIMO = 50;
