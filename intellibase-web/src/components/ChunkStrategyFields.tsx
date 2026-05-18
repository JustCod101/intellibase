import type { ChangeEvent } from 'react';
import type { ChunkStrategy } from '../types';
import { getChunkStrategySummary } from '../utils/chunkStrategy';

interface ChunkStrategyFieldsProps {
  value: ChunkStrategy;
  onChange: (value: ChunkStrategy) => void;
  defaultOpen?: boolean;
}

const ChunkStrategyFields = ({ value, onChange, defaultOpen = false }: ChunkStrategyFieldsProps) => {
  const handleNumberChange = (field: keyof ChunkStrategy) => (event: ChangeEvent<HTMLInputElement>) => {
    const nextValue = Number(event.target.value);
    onChange({
      ...value,
      [field]: Number.isNaN(nextValue) ? 0 : nextValue,
    });
  };

  return (
    <details className="chunk-strategy-card chunk-strategy-card-collapsible" open={defaultOpen}>
      <summary className="chunk-strategy-summary">
        <div className="chunk-strategy-summary-main">
          <span className="chunk-strategy-title">Chunk 策略</span>
          <span className="chunk-strategy-summary-text">{getChunkStrategySummary(value)}</span>
        </div>
        <span className="chunk-strategy-toggle">展开配置</span>
      </summary>

      <div className="chunk-strategy-content">
        <div className="chunk-strategy-meta">
          <span className="strategy-chip">Version {value.version}</span>
          <span className="strategy-chip">{value.type}</span>
        </div>

        <div className="chunk-strategy-grid">
          <div className="form-group">
            <label>Chunk Size</label>
            <input type="number" min={100} value={value.size} onChange={handleNumberChange('size')} />
          </div>

          <div className="form-group">
            <label>Overlap</label>
            <input type="number" min={0} value={value.overlap} onChange={handleNumberChange('overlap')} />
          </div>

          <div className="form-group">
            <label>Min Size</label>
            <input type="number" min={1} value={value.minSize} onChange={handleNumberChange('minSize')} />
          </div>

          <label className="form-check chunk-strategy-checkbox">
            <input
              type="checkbox"
              checked={value.normalizeWhitespace}
              onChange={(event) => onChange({ ...value, normalizeWhitespace: event.target.checked })}
            />
            <span>解析前规范化空白和换行</span>
          </label>

          <label className="form-check chunk-strategy-checkbox">
            <input
              type="checkbox"
              checked={value.parentChildEnabled}
              onChange={(event) => onChange({ ...value, parentChildEnabled: event.target.checked })}
            />
            <span>启用父子分块（子块检索，父块生成）</span>
          </label>

          {value.parentChildEnabled && (
            <>
              <div className="form-group">
                <label>Parent Size</label>
                <input type="number" min={100} value={value.parentSize} onChange={handleNumberChange('parentSize')} />
              </div>

              <div className="form-group">
                <label>Child Size</label>
                <input type="number" min={20} value={value.childSize} onChange={handleNumberChange('childSize')} />
              </div>

              <div className="form-group">
                <label>Child Overlap</label>
                <input
                  type="number"
                  min={0}
                  value={value.childOverlap}
                  onChange={handleNumberChange('childOverlap')}
                />
              </div>
            </>
          )}
        </div>
      </div>
    </details>
  );
};

export default ChunkStrategyFields;
