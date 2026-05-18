export interface User {
  id: number;
  username: string;
  email: string;
  role: string;
}

export interface ChunkStrategy {
  version: number;
  type: string;
  size: number;
  overlap: number;
  minSize: number;
  normalizeWhitespace: boolean;
  parentChildEnabled: boolean;
  parentSize: number;
  childSize: number;
  childOverlap: number;
}

export type RetrievalPreset = 'GENERAL_QA' | 'EXACT_LOOKUP' | 'LONGFORM_SYNTHESIS';

export interface RetrievalConfig {
  preset: RetrievalPreset;
  hybridEnabled: boolean;
  rerankEnabled: boolean;
  denseTopK: number;
  sparseTopK: number;
  fusionTopK: number;
  finalTopK: number;
  denseWeight: number;
  sparseWeight: number;
}

export interface KnowledgeBase {
  id: number;
  name: string;
  description: string;
  embeddingModel: string;
  chunkStrategy: ChunkStrategy;
  retrievalConfig: RetrievalConfig;
  docCount: number;
  status: string;
  createdAt: string;
}

export interface Document {
  id: number;
  kbId: number;
  title: string;
  fileType: string;
  fileSize: number;
  parseStatus: string;
  chunkCount: number;
  metadata: any;
  createdAt: string;
}

export interface Conversation {
  id: number;
  kbId?: number;
  title: string;
  model: string;
  config?: any;
  createdAt: string;
  updatedAt: string;
}

export interface ChatMessage {
  id: number | string; // 兼容前端临时生成的 ID
  conversationId: number;
  role: 'user' | 'assistant' | 'system';
  content: string;
  sources?: any[];
  tokenUsage?: any;
  latencyMs?: number;
  createdAt?: string;
}

export interface ApiResponse<T = any> {
  code: number;
  message: string;
  data: T;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  page: number;
  size: number;
}

export interface AdminUser {
  id: number;
  username: string;
  email: string;
  role: string;
  status?: number;
}

export interface CacheLevelStats {
  hits: number;
  misses: number;
  total: number;
  hit_rate: string;
}

export interface CacheStats {
  l1_semantic_cache: CacheLevelStats;
  l2_retrieval_cache: CacheLevelStats;
  db_queries: number;
  overall_cache_hit_rate: string;
}
