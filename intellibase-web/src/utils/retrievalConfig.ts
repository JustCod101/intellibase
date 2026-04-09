import type { RetrievalConfig, RetrievalPreset } from '../types';

export const RETRIEVAL_PRESET_OPTIONS: { value: RetrievalPreset; label: string; description: string }[] = [
  { value: 'GENERAL_QA', label: '通用问答', description: '平衡术语命中和语义召回，适合作为默认企业知识问答策略。' },
  { value: 'EXACT_LOOKUP', label: '精确查找', description: '优先关键词和术语命中，适合错误码、接口名、版本号定位。' },
  { value: 'LONGFORM_SYNTHESIS', label: '长文综合', description: '扩大候选集和上下文窗口，适合长文档综合总结。' },
];

const PRESET_DEFAULTS: Record<RetrievalPreset, RetrievalConfig> = {
  GENERAL_QA: {
    preset: 'GENERAL_QA',
    hybridEnabled: true,
    rerankEnabled: true,
    denseTopK: 20,
    sparseTopK: 20,
    fusionTopK: 15,
    finalTopK: 5,
    denseWeight: 0.55,
    sparseWeight: 0.45,
  },
  EXACT_LOOKUP: {
    preset: 'EXACT_LOOKUP',
    hybridEnabled: true,
    rerankEnabled: true,
    denseTopK: 10,
    sparseTopK: 30,
    fusionTopK: 15,
    finalTopK: 5,
    denseWeight: 0.35,
    sparseWeight: 0.65,
  },
  LONGFORM_SYNTHESIS: {
    preset: 'LONGFORM_SYNTHESIS',
    hybridEnabled: true,
    rerankEnabled: true,
    denseTopK: 30,
    sparseTopK: 15,
    fusionTopK: 20,
    finalTopK: 8,
    denseWeight: 0.65,
    sparseWeight: 0.35,
  },
};

export const DEFAULT_RETRIEVAL_CONFIG = PRESET_DEFAULTS.GENERAL_QA;

export const getPresetRetrievalConfig = (preset: RetrievalPreset): RetrievalConfig => ({
  ...PRESET_DEFAULTS[preset],
});

export const normalizeRetrievalConfig = (config?: Partial<RetrievalConfig> | null): RetrievalConfig => {
  const preset = config?.preset ?? DEFAULT_RETRIEVAL_CONFIG.preset;
  const defaults = getPresetRetrievalConfig(preset);

  const denseTopK = positiveInt(config?.denseTopK, defaults.denseTopK);
  const sparseTopK = positiveInt(config?.sparseTopK, defaults.sparseTopK);
  const fusionTopK = clamp(positiveInt(config?.fusionTopK, defaults.fusionTopK), 1, denseTopK + sparseTopK);
  const finalTopK = clamp(positiveInt(config?.finalTopK, defaults.finalTopK), 1, fusionTopK);
  const denseWeight = nonNegativeNumber(config?.denseWeight, defaults.denseWeight);
  const sparseWeight = nonNegativeNumber(config?.sparseWeight, defaults.sparseWeight);
  const weightSum = denseWeight + sparseWeight || defaults.denseWeight + defaults.sparseWeight;

  return {
    preset,
    hybridEnabled: config?.hybridEnabled ?? defaults.hybridEnabled,
    rerankEnabled: config?.rerankEnabled ?? defaults.rerankEnabled,
    denseTopK,
    sparseTopK,
    fusionTopK,
    finalTopK,
    denseWeight: denseWeight / weightSum,
    sparseWeight: sparseWeight / weightSum,
  };
};

export const getRetrievalConfigSummary = (config?: Partial<RetrievalConfig> | null) => {
  const normalized = normalizeRetrievalConfig(config);
  const presetLabel = RETRIEVAL_PRESET_OPTIONS.find((item) => item.value === normalized.preset)?.label ?? normalized.preset;
  return `${presetLabel} · dense ${normalized.denseTopK} / sparse ${normalized.sparseTopK} / final ${normalized.finalTopK} · ${Math.round(normalized.denseWeight * 100)}:${Math.round(normalized.sparseWeight * 100)}`;
};

const positiveInt = (value: number | undefined, fallback: number) => {
  const next = Number(value ?? fallback);
  return Number.isFinite(next) && next > 0 ? Math.round(next) : fallback;
};

const nonNegativeNumber = (value: number | undefined, fallback: number) => {
  const next = Number(value ?? fallback);
  return Number.isFinite(next) && next >= 0 ? next : fallback;
};

const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(max, value));
