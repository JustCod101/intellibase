import type { ChangeEvent } from 'react';
import type { RetrievalConfig, RetrievalPreset } from '../types';
import {
  getPresetRetrievalConfig,
  getRetrievalConfigSummary,
  normalizeRetrievalConfig,
  RETRIEVAL_PRESET_OPTIONS,
} from '../utils/retrievalConfig';

interface RetrievalConfigFieldsProps {
  value: RetrievalConfig;
  onChange: (value: RetrievalConfig) => void;
  defaultOpen?: boolean;
}

type RetrievalNumberField =
  | 'denseTopK'
  | 'sparseTopK'
  | 'fusionTopK'
  | 'finalTopK'
  | 'denseWeight'
  | 'sparseWeight';

const RetrievalConfigFields = ({
  value,
  onChange,
  defaultOpen = false,
}: RetrievalConfigFieldsProps) => {
  const normalized = normalizeRetrievalConfig(value);

  const handleNumberChange = (field: RetrievalNumberField) => (event: ChangeEvent<HTMLInputElement>) => {
    const nextValue = Number(event.target.value);
    onChange(normalizeRetrievalConfig({
      ...normalized,
      [field]: Number.isNaN(nextValue) ? 0 : nextValue,
    }));
  };

  return (
    <details className="retrieval-config-card retrieval-config-card-collapsible" open={defaultOpen}>
      <summary className="retrieval-config-summary">
        <div className="retrieval-config-summary-main">
          <span className="retrieval-config-title">检索策略</span>
          <span className="retrieval-config-summary-text">{getRetrievalConfigSummary(normalized)}</span>
        </div>
        <span className="retrieval-config-toggle">展开配置</span>
      </summary>

      <div className="retrieval-config-content">
        <div className="form-group">
          <label>场景预设</label>
          <select
            value={normalized.preset}
            onChange={(event) => onChange(getPresetRetrievalConfig(event.target.value as RetrievalPreset))}
          >
            {RETRIEVAL_PRESET_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <p className="retrieval-config-hint">
            {RETRIEVAL_PRESET_OPTIONS.find((option) => option.value === normalized.preset)?.description}
          </p>
        </div>

        <div className="retrieval-config-meta">
          <span className="strategy-chip">{normalized.preset}</span>
          <span className="strategy-chip">{normalized.hybridEnabled ? '混合召回' : '纯向量'}</span>
          <span className="strategy-chip">{normalized.rerankEnabled ? '规则重排' : '不重排'}</span>
        </div>

        <div className="retrieval-config-grid">
          <label className="form-check">
            <input
              type="checkbox"
              checked={normalized.hybridEnabled}
              onChange={(event) => onChange({ ...normalized, hybridEnabled: event.target.checked })}
            />
            <span>启用混合检索</span>
          </label>

          <label className="form-check">
            <input
              type="checkbox"
              checked={normalized.rerankEnabled}
              onChange={(event) => onChange({ ...normalized, rerankEnabled: event.target.checked })}
            />
            <span>启用规则重排</span>
          </label>

          <div className="form-group">
            <label>Dense TopK</label>
            <input type="number" min={1} value={normalized.denseTopK} onChange={handleNumberChange('denseTopK')} />
          </div>

          <div className="form-group">
            <label>Sparse TopK</label>
            <input type="number" min={1} value={normalized.sparseTopK} onChange={handleNumberChange('sparseTopK')} />
          </div>

          <div className="form-group">
            <label>Fusion TopK</label>
            <input type="number" min={1} value={normalized.fusionTopK} onChange={handleNumberChange('fusionTopK')} />
          </div>

          <div className="form-group">
            <label>Final TopK</label>
            <input type="number" min={1} value={normalized.finalTopK} onChange={handleNumberChange('finalTopK')} />
          </div>

          <div className="form-group">
            <label>Dense Weight</label>
            <input type="number" min={0} max={1} step={0.05} value={normalized.denseWeight} onChange={handleNumberChange('denseWeight')} />
          </div>

          <div className="form-group">
            <label>Sparse Weight</label>
            <input type="number" min={0} max={1} step={0.05} value={normalized.sparseWeight} onChange={handleNumberChange('sparseWeight')} />
          </div>
        </div>
      </div>
    </details>
  );
};

export default RetrievalConfigFields;
