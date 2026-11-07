import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface Eps {
  id: number;
  name: string;
  active: boolean;
}

@Injectable({ providedIn: 'root' })
export class EpsService {
  private readonly http = inject(HttpClient);

  /** EPS activas por nombre (catálogo global); el Master puede pedir también las inactivas. */
  listar(incluirInactivas = false): Observable<Eps[]> {
    const params = incluirInactivas ? new HttpParams().set('includeInactive', true) : undefined;
    return this.http.get<Eps[]>('/api/health-insurance-providers', { params });
  }

  crear(name: string): Observable<Eps> {
    return this.http.post<Eps>('/api/health-insurance-providers', { name });
  }

  renombrar(id: number, name: string): Observable<Eps> {
    return this.http.put<Eps>(`/api/health-insurance-providers/${id}`, { name });
  }

  activar(id: number, active: boolean): Observable<Eps> {
    return this.http.patch<Eps>(`/api/health-insurance-providers/${id}/active`, { active });
  }
}
