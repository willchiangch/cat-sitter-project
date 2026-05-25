export interface ServicePlan {
  id?: string;
  sitterId?: string;
  name: string;
  price: number;
  dailyCapacity: number;
  defaultTasks: string[];
  applicablePetTypes: string[];
  description?: string;
  startDate?: string | null; // DATE - YYYY-MM-DD
  endDate?: string | null;   // DATE - YYYY-MM-DD
  isRestricted: boolean;
  sortOrder?: number;
  version?: number;
}

export interface ServicePlanSortRequest {
  planIds: string[];
}
