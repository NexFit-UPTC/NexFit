import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface Progreso {
  today: string;
  streak: number;
  weeklyGoal: number | null;
  weekCount: number;
  weekStart: string;
  weekEnd: string;
  totalWorkouts: number;
}

export interface ResumenEntrenamiento {
  id: number;
  routineId: number;
  routineName: string;
  performedOn: string;
  completedCount: number;
  totalCount: number;
}

export interface EjercicioEntrenado {
  routineExerciseId: number;
  exerciseId: number;
  exerciseName: string;
  order: number;
  series: number;
  repetitions: number;
}

export interface Entrenamiento {
  id: number;
  routineId: number;
  routineName: string;
  performedOn: string;
  createdAt: string;
  exercises: EjercicioEntrenado[];
}

export interface RegistroPeso {
  id: number;
  measuredOn: string;
  weightKg: number;
}

@Injectable({ providedIn: 'root' })
export class EntrenamientosService {
  private readonly http = inject(HttpClient);

  progreso(): Observable<Progreso> {
    return this.http.get<Progreso>('/api/my-progress');
  }

  fijarMeta(weeklyGoal: number | null): Observable<Progreso> {
    return this.http.put<Progreso>('/api/my-goal', { weeklyGoal });
  }

  registrar(routineId: number, completedExerciseIds: number[], performedOn?: string): Observable<Entrenamiento> {
    return this.http.post<Entrenamiento>('/api/workouts', {
      routineId,
      completedExerciseIds,
      ...(performedOn ? { performedOn } : {})
    });
  }

  historial(): Observable<ResumenEntrenamiento[]> {
    return this.http.get<ResumenEntrenamiento[]>('/api/workouts');
  }

  pesos(): Observable<RegistroPeso[]> {
    return this.http.get<RegistroPeso[]>('/api/body-weight');
  }

  guardarPeso(weightKg: number, measuredOn?: string): Observable<RegistroPeso> {
    return this.http.post<RegistroPeso>('/api/body-weight', {
      weightKg,
      ...(measuredOn ? { measuredOn } : {})
    });
  }
}
