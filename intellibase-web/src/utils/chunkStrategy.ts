import type { ChunkStrategy } from '../types';

export const DEFAULT_CHUNK_STRATEGY: ChunkStrategy = {
  version: 2,
  type: 'STRUCTURE_AWARE',
  size: 800,
  overlap: 120,
  minSize: 200,
  normalizeWhitespace: true,
};

export const normalizeChunkStrategy = (strategy?: Partial<ChunkStrategy> | null): ChunkStrategy => {
  const size = Math.max(100, Number(strategy?.size ?? DEFAULT_CHUNK_STRATEGY.size));
  const overlap = Math.min(Math.max(0, Number(strategy?.overlap ?? DEFAULT_CHUNK_STRATEGY.overlap)), size - 1);
  const minSize = Math.min(Math.max(1, Number(strategy?.minSize ?? DEFAULT_CHUNK_STRATEGY.minSize)), size);

  return {
    version: Number(strategy?.version ?? DEFAULT_CHUNK_STRATEGY.version),
    type: strategy?.type || DEFAULT_CHUNK_STRATEGY.type,
    size,
    overlap,
    minSize,
    normalizeWhitespace: strategy?.normalizeWhitespace ?? DEFAULT_CHUNK_STRATEGY.normalizeWhitespace,
  };
};

export const getChunkStrategySummary = (strategy?: Partial<ChunkStrategy> | null) => {
  const normalized = normalizeChunkStrategy(strategy);
  return `${normalized.type} · ${normalized.size}/${normalized.overlap} · min ${normalized.minSize} · ${
    normalized.normalizeWhitespace ? '规范化空白' : '保留原始空白'
  }`;
};
