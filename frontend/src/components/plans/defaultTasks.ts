/**
 * 新增服務方案時的預設 SOP 任務，依「適用寵物」給 (SD-003 §前端，Backlog 第 3 批 U6)。
 * 過去不論勾哪種寵物一律預填「基本餵食 / 清理砂盆 / 更換新鮮水」，狗狗方案也帶著「清理砂盆」。
 * 多選時依勾選順序取聯集、去重；只是建議模板，保母可再自行增刪。
 */
export const DEFAULT_TASKS_BY_PET_TYPE: Record<string, string[]> = {
  CAT: ['基本餵食', '清理砂盆', '更換新鮮水'],
  DOG: ['基本餵食', '散步遛狗', '清理排泄物', '更換新鮮水'],
  BIRD: ['基本餵食', '清理鳥籠', '更換新鮮水'],
  MOUSE: ['基本餵食', '更換墊料', '更換新鮮水'],
  RABBIT: ['基本餵食', '補充牧草', '清理便盆', '更換新鮮水'],
  REPTILE: ['基本餵食', '檢查溫濕度', '更換新鮮水'],
  INSECT: ['基本餵食', '檢查飼育環境'],
  OTHER: ['基本餵食', '更換新鮮水']
};

export const defaultTasksFor = (petTypes: string[]): string[] => {
  const tasks: string[] = [];
  for (const type of petTypes) {
    for (const task of DEFAULT_TASKS_BY_PET_TYPE[type] ?? []) {
      if (!tasks.includes(task)) tasks.push(task);
    }
  }
  return tasks;
};
