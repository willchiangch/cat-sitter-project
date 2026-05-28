export interface Plan {
  id: string;
  name: string;
  price: number;
  description: string;
}

export interface Pet {
  id: string;
  name: string;
  type: string;
  avatar?: string;
}

export interface BookingItem {
  planId: string;
  dates: string[];
  timesPerDay: number;
}

export interface ScheduleConfig {
  dates: string[];
  timesPerDay: number;
  petIds?: string[]; // 關聯的毛孩 ID
}

export interface PlanConfig {
  planId: string;
  schedules: ScheduleConfig[];
}

export interface BookingState {
  sitterId: string;
  planConfigs: PlanConfig[];
  notes: string;
  totalAmount: number;
}
