import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
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

  listar(): Observable<Personal[]> {
    return this.http.get<Personal[]>('/api/staff');
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
