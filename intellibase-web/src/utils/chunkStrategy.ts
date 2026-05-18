import type { ChunkStrategy } from '../types';

export const DEFAULT_CHUNK_STRATEGY: ChunkStrategy = {
  version: 2,
  type: 'STRUCTURE_AWARE',
  size: 800,
  overlap: 120,
  minSize: 200,
  normalizeWhitespace: true,
  parentChildEnabled: false,
  parentSize: 1800,
  childSize: 420,
  childOverlap: 80,
};

export const normalizeChunkStrategy = (strategy?: Partial<ChunkStrategy> | null): ChunkStrategy => {
  const size = Math.max(20, Number(strategy?.size ?? DEFAULT_CHUNK_STRATEGY.size));
  const overlap = Math.min(Math.max(0, Number(strategy?.overlap ?? DEFAULT_CHUNK_STRATEGY.overlap)), size - 1);
  const minSize = Math.min(Math.max(1, Number(strategy?.minSize ?? DEFAULT_CHUNK_STRATEGY.minSize)), size);
  const parentSize = Math.max(size, Number(strategy?.parentSize ?? DEFAULT_CHUNK_STRATEGY.parentSize));
  const childSize = Math.max(20, Number(strategy?.childSize ?? DEFAULT_CHUNK_STRATEGY.childSize));
  const childOverlap = Math.min(
    Math.max(0, Number(strategy?.childOverlap ?? DEFAULT_CHUNK_STRATEGY.childOverlap)),
    childSize - 1,
  );

  return {
    version: Number(strategy?.version ?? DEFAULT_CHUNK_STRATEGY.version),
    type: strategy?.type || DEFAULT_CHUNK_STRATEGY.type,
    size,
    overlap,
    minSize,
    normalizeWhitespace: strategy?.normalizeWhitespace ?? DEFAULT_CHUNK_STRATEGY.normalizeWhitespace,
    parentChildEnabled: strategy?.parentChildEnabled ?? DEFAULT_CHUNK_STRATEGY.parentChildEnabled,
    parentSize,
    childSize,
    childOverlap,
  };
};

export const getChunkStrategySummary = (strategy?: Partial<ChunkStrategy> | null) => {
  const normalized = normalizeChunkStrategy(strategy);
  const parentChild = normalized.parentChildEnabled
    ? ` · 父子 ${normalized.parentSize}→${normalized.childSize}/${normalized.childOverlap}`
    : '';
  return `${normalized.type} · ${normalized.size}/${normalized.overlap}${parentChild} · min ${normalized.minSize} · ${
    normalized.normalizeWhitespace ? '规范化空白' : '保留原始空白'
  }`;
};
