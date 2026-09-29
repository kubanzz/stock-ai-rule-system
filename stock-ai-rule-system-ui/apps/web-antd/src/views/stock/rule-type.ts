const ruleTypeLabels: Record<string, string> = {
  technical: '技术',
  trend: '趋势',
  risk: '风险',
  risk_guard: '风险防守',
  sentiment: '情绪',
  ai_candidate: 'AI 候选',
};

export const ruleTypeOptions = Object.entries(ruleTypeLabels).map(
  ([value, label]) => ({ label, value }),
);

export function getRuleTypeLabel(ruleType: string): string {
  return ruleTypeLabels[ruleType] ?? ruleType;
}
