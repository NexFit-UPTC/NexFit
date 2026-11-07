import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export type EstadoEquipo = 'available' | 'maintenance' | 'out_of_service' | 'retired';

export const ETIQUETA_ESTADO_EQUIPO: Record<EstadoEquipo, string> = {
  available: 'Disponible',
  maintenance: 'En mantenimiento',
  out_of_service: 'Fuera de servicio',
  retired: 'Dado de baja'
};

export interface Equipo {
  id: number;
  branchId: number;
  branchName: string;
  name: string;
  category: string | null;
  status: EstadoEquipo;
  acquisitionDate: string | null;
  imageUrl: string | null;
}

export interface DatosEquipo {
  name: string;
  category: string | null;
  acquisitionDate: string | null;
  imageUrl: string | null;
}

export interface FiltroEquipos {
  branchId?: number;
  status?: EstadoEquipo;
  category?: string;
}

@Injectable({ providedIn: 'root' })
export class EquiposService {
  private readonly http = inject(HttpClient);

  listar(filtro: FiltroEquipos = {}): Observable<Equipo[]> {
    let params = new HttpParams();
    if (filtro.branchId !== undefined) params = params.set('branchId', filtro.branchId);
    if (filtro.status) params = params.set('status', filtro.status);
    if (filtro.category?.trim()) params = params.set('category', filtro.category.trim());
    return this.http.get<Equipo[]>('/api/equipment', { params });
  }

  crear(branchId: number, datos: DatosEquipo, status?: EstadoEquipo): Observable<Equipo> {
    return this.http.post<Equipo>('/api/equipment', { branchId, ...datos, ...(status ? { status } : {}) });
  }

  editar(id: number, datos: DatosEquipo): Observable<Equipo> {
    return this.http.put<Equipo>(`/api/equipment/${id}`, datos);
  }

  cambiarEstado(id: number, status: EstadoEquipo): Observable<Equipo> {
    return this.http.patch<Equipo>(`/api/equipment/${id}/status`, { status });
  }
}
