import assert from 'node:assert/strict';
import test from 'node:test';
import { buildRunPayload, defaultParameters, formatClock } from './api.js';
import { createMapProjection } from './mapProjection.js';

test('buildRunPayload chuẩn hóa kiểu dữ liệu gửi tới API', () => {
  assert.deepEqual(buildRunPayload({
    datasetId: '4', algorithmCode: 'TABU_SEARCH', seed: '42',
    capacityOverride: '', iterationLimit: '250', timeLimitSeconds: '3',
    parameters: { tabu_tenure: 8, neighborhood: 'SWAP' },
  }), {
    datasetId: 4, algorithmCode: 'TABU_SEARCH', seed: 42,
    capacityOverride: null, iterationLimit: 250, timeLimitSeconds: 3,
    parameters: { tabu_tenure: '8', neighborhood: 'SWAP' },
  });
});

test('formatClock hiển thị giây theo HH:mm:ss', () => {
  assert.equal(formatClock(3661), '01:01:01');
  assert.equal(formatClock(null), '00:00:00');
});

test('defaultParameters lấy cấu hình mặc định từ metadata', () => {
  assert.deepEqual(defaultParameters({ parameters: [
    { code: 'population_size', defaultValue: '50' },
    { code: 'mutation_rate', defaultValue: '0.1' },
  ] }), { population_size: '50', mutation_rate: '0.1' });
});

test('map projection không nén các tọa độ địa lý gần nhau vào một điểm', () => {
  const project = createMapProjection(
    [{ DEPOT_CODE: 'D0', LONGITUDE: 106.7009, LATITUDE: 10.7769 }],
    [
      { CUSTOMER_CODE: 'C1', LONGITUDE: 106.7309, LATITUDE: 10.7969 },
      { CUSTOMER_CODE: 'C2', LONGITUDE: 106.6709, LATITUDE: 10.7569 },
    ],
  );
  assert.ok(Math.abs(project('C1').x - project('C2').x) > 500);
  assert.ok(Math.abs(project('C1').y - project('C2').y) > 300);
});

test('map projection rải điểm khi dataset cũ không có tọa độ', () => {
  const project = createMapProjection(
    [{ DEPOT_CODE: 'D0', LONGITUDE: 0, LATITUDE: 0 }],
    [
      { CUSTOMER_CODE: 'C1', LONGITUDE: 0, LATITUDE: 0 },
      { CUSTOMER_CODE: 'C2', LONGITUDE: 0, LATITUDE: 0 },
    ],
  );
  assert.notDeepEqual(
    [project('D0').x, project('D0').y],
    [project('C1').x, project('C1').y],
  );
});
