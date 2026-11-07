import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface Entrenador {
  memberId: number;
  name: string;
  specialty: string | null;
  activeStudents: number;
}

export interface Asignacion {
  trainerMemberId: number;
  trainerName: string;
  clientMemberId: number;
  clientName: string;
  assignedAt: string;
  active: boolean;
}

export interface ContactoAlumno {
  fullName: string;
  relationship: string;
  phone: string;
}

export interface Alumno {
  memberId: number;
  name: string;
  age: number | null;
  emergencyContact: ContactoAlumno | null;
  activeRoutines: number;
  lastCheckIn: string | null;
}

export interface FiltroAsignaciones {
  trainerMemberId?: number;
  active?: boolean;
}

@Injectable({ providedIn: 'root' })
export class AsignacionesService {
  private readonly http = inject(HttpClient);

  entrenadores(): Observable<Entrenador[]> {
    return this.http.get<Entrenador[]>('/api/trainers');
  }

  listar(filtro: FiltroAsignaciones = {}): Observable<Asignacion[]> {
    let params = new HttpParams();
    if (filtro.trainerMemberId !== undefined) params = params.set('trainerMemberId', filtro.trainerMemberId);
    if (filtro.active !== undefined) params = params.set('active', filtro.active);
    return this.http.get<Asignacion[]>('/api/trainer-assignments', { params });
  }

  asignar(trainerMemberId: number, clientMemberId: number): Observable<Asignacion> {
    return this.http.post<Asignacion>('/api/trainer-assignments', { trainerMemberId, clientMemberId });
  }

  cambiarEstado(trainerMemberId: number, clientMemberId: number, active: boolean): Observable<Asignacion> {
    return this.http.patch<Asignacion>(`/api/trainer-assignments/${trainerMemberId}/${clientMemberId}`, { active });
  }

  misAlumnos(): Observable<Alumno[]> {
    return this.http.get<Alumno[]>('/api/my-students');
  }
}
