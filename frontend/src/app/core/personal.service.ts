import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { expand, map, Observable, reduce, EMPTY } from 'rxjs';
import { Pagina, TAMANO_MAXIMO, TAMANO_PAGINA } from './pagina';
import { TipoDocumento } from './establecimientos.service';

export type RolPersonal = 'staff' | 'trainer';

export interface PerfilEntrenador {
  specialty: string | null;
  certification: string | null;
  hireDate: string | null;
}

export interface Personal {
  memberId: number;
  userId: number;
  documentType: TipoDocumento;
  documentNumber: string;
  firstName: string;
  lastName: string;
  birthDate: string | null;
  email: string;
  active: boolean;
  roles: string[];
  trainerProfile: PerfilEntrenador | null;
}

export interface DatosPersonal {
  documentType: TipoDocumento;
  documentNumber: string;
  firstName: string;
  lastName: string;
  birthDate: string;
  email: string;
  roles: RolPersonal[];
  specialty?: string;
  certification?: string;
  hireDate?: string;
}

export interface CambiosPersonal {
  roles: RolPersonal[];
  specialty?: string;
  certification?: string;
  hireDate?: string;
}

/** 201 trae temporaryPassword (cuenta nueva); 200 solo userId, memberId y nombre (persona ya existente, afiliada). */
export interface ResultadoPersonal {
  userId: number;
  memberId: number;
  temporaryPassword?: string;
  firstName?: string;
  lastName?: string;
}

@Injectable({ providedIn: 'root' })
export class PersonalService {
  private readonly http = inject(HttpClient);

  /** Una página del personal (administrador, staff y entrenadores); `rol` filtra por un solo rol. */
  listar(page = 0, size = TAMANO_PAGINA, rol?: string): Observable<Pagina<Personal>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (rol) params = params.set('role', rol);
    return this.http.get<Pagina<Personal>>('/api/staff', { params });
  }

  /** Todos los entrenadores activos, para elegirlos en un formulario: recorre las páginas del servidor. */
  entrenadoresActivos(): Observable<Personal[]> {
    return this.listar(0, TAMANO_MAXIMO, 'trainer').pipe(
      expand((p) => (p.page + 1 < p.totalPages ? this.listar(p.page + 1, TAMANO_MAXIMO, 'trainer') : EMPTY)),
      map((p) => p.content),
      reduce((todos, pagina) => [...todos, ...pagina], [] as Personal[]),
      map((todos) => todos.filter((t) => t.active))
    );
  }

  crear(datos: DatosPersonal): Observable<ResultadoPersonal> {
    return this.http.post<ResultadoPersonal>('/api/staff', datos);
  }

  editar(memberId: number, cambios: CambiosPersonal): Observable<Personal> {
    return this.http.patch<Personal>(`/api/staff/${memberId}`, cambios);
  }

  activar(memberId: number): Observable<Personal> {
    return this.http.post<Personal>(`/api/staff/${memberId}/activate`, {});
  }

  desactivar(memberId: number): Observable<Personal> {
    return this.http.post<Personal>(`/api/staff/${memberId}/deactivate`, {});
  }
}
