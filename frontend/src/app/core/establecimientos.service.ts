import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface Establecimiento {
  id: number;
  name: string;
  contactEmail: string;
  timezone: string;
  active: boolean;
}

export interface ResumenEstablecimiento extends Establecimiento {
  branchCount: number;
  clientCount: number;
}

export interface DatosEstablecimiento {
  name: string;
  contactEmail: string;
  timezone: string;
}

export type TipoDocumento = 'CC' | 'CE' | 'PPT' | 'PA';

export interface DatosAdministrador {
  documentType: TipoDocumento;
  documentNumber: string;
  firstName: string;
  lastName: string;
  birthDate: string;
  email: string;
}

/** 201 trae temporaryPassword (cuenta nueva); 200 solo userId y nombre (persona ya existente, afiliada). */
export interface ResultadoAsignacion {
  userId: number;
  temporaryPassword?: string;
  firstName?: string;
  lastName?: string;
}

export interface ResultadoLookup {
  exists: boolean;
  firstName?: string;
  lastName?: string;
}

/** Error de validación 400 del backend. */
export interface ErrorCampo {
  code: string;
  field: string;
  message: string;
}

/** Errores por campo de un 400 {code, field, message}; el resto se devuelve como mensaje general. */
export function errorDeApi(e: HttpErrorResponse): { campo: string | null; mensaje: string } {
  const cuerpo = e.error as Partial<ErrorCampo> | null;
  if (e.status === 400 && cuerpo?.field) {
    return { campo: cuerpo.field, mensaje: cuerpo.message ?? 'Dato inválido' };
  }
  return { campo: null, mensaje: cuerpo?.message ?? 'No se pudo completar la operación.' };
}

@Injectable({ providedIn: 'root' })
export class EstablecimientosService {
  private readonly http = inject(HttpClient);

  listar(): Observable<ResumenEstablecimiento[]> {
    return this.http.get<ResumenEstablecimiento[]>('/api/establishments');
  }

  crear(datos: DatosEstablecimiento): Observable<Establecimiento> {
    return this.http.post<Establecimiento>('/api/establishments', datos);
  }

  editar(id: number, datos: DatosEstablecimiento): Observable<Establecimiento> {
    return this.http.put<Establecimiento>(`/api/establishments/${id}`, datos);
  }

  buscarAdministrador(orgId: number, tipo: TipoDocumento, numero: string): Observable<ResultadoLookup> {
    return this.http.get<ResultadoLookup>(`/api/establishments/${orgId}/admin/lookup`, {
      params: { documentType: tipo, documentNumber: numero }
    });
  }

  asignarAdministrador(orgId: number, datos: DatosAdministrador): Observable<ResultadoAsignacion> {
    return this.http.post<ResultadoAsignacion>(`/api/establishments/${orgId}/admin`, datos);
  }
}
