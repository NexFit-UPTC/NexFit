import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface Clase {
  id: number;
  name: string;
  description: string | null;
  branchId: number;
  branchName: string;
  trainerMemberId: number | null;
  trainerName: string | null;
  startsAt: string;
  durationMinutes: number;
  capacity: number;
  status: 'scheduled' | 'cancelled';
  confirmedBookings: number;
  freeSlots: number;
}

export interface PaginaClases {
  content: Clase[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface FiltroClases {
  branchId?: number;
  from?: string;
  to?: string;
  status?: 'scheduled' | 'cancelled';
  page?: number;
  size?: number;
}

export interface DatosClase {
  name: string;
  description: string;
  branchId: number;
  trainerMemberId: number | null;
  startsAt: string;
  durationMinutes: number;
  capacity: number;
}

@Injectable({ providedIn: 'root' })
export class ClasesService {
  private readonly http = inject(HttpClient);

  listar(filtro: FiltroClases = {}): Observable<PaginaClases> {
    let params = new HttpParams();
    if (filtro.branchId !== undefined) params = params.set('branchId', filtro.branchId);
    if (filtro.from) params = params.set('from', filtro.from);
    if (filtro.to) params = params.set('to', filtro.to);
    if (filtro.status) params = params.set('status', filtro.status);
    if (filtro.page !== undefined) params = params.set('page', filtro.page);
    if (filtro.size !== undefined) params = params.set('size', filtro.size);
    return this.http.get<PaginaClases>('/api/classes', { params });
  }

  crear(datos: DatosClase): Observable<Clase> {
    return this.http.post<Clase>('/api/classes', datos);
  }

  editar(id: number, datos: DatosClase): Observable<Clase> {
    return this.http.put<Clase>(`/api/classes/${id}`, datos);
  }

  cancelar(id: number, reason: string): Observable<Clase> {
    return this.http.patch<Clase>(`/api/classes/${id}/cancel`, reason.trim() ? { reason: reason.trim() } : {});
  }
}
