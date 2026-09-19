export const API_BASE_URL = (import.meta.env?.VITE_API_BASE_URL || '/api').replace(/\/$/, '');

export async function apiFetch(path, options = {}) {
  const response = await fetch(`${API_BASE_URL}${path}`, options);
  const contentType = response.headers.get('content-type') || '';
  const body = contentType.includes('application/json') ? await response.json() : null;
  if (!response.ok) {
    throw new Error(body?.error || `API trả về HTTP ${response.status}`);
  }
  return body;
}

export function buildRunPayload(config) {
  return {
    datasetId: Number(config.datasetId),
    algorithmCode: config.algorithmCode,
    seed: Number(config.seed),
    capacityOverride: config.capacityOverride === '' ? null : Number(config.capacityOverride),
    iterationLimit: Number(config.iterationLimit),
    timeLimitSeconds: Number(config.timeLimitSeconds),
    parameters: Object.fromEntries(
      Object.entries(config.parameters || {}).map(([key, value]) => [key, String(value)]),
    ),
  };
}

export function formatClock(totalSeconds) {
  const seconds = Math.max(0, Number(totalSeconds) || 0);
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const remainder = Math.floor(seconds % 60);
  return [hours, minutes, remainder].map(value => String(value).padStart(2, '0')).join(':');
}

export function defaultParameters(algorithm) {
  return Object.fromEntries(
    (algorithm?.parameters || []).map(parameter => [parameter.code, parameter.defaultValue ?? '']),
  );
}
