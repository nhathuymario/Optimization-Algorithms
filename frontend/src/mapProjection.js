/**
 * Chuẩn hóa tọa độ database thành hệ SVG 800x600.
 * Không đưa (0, 0) giả vào bounds vì sẽ làm các tọa độ địa lý gần nhau bị nén.
 */
export function createMapProjection(depots = [], customers = []) {
  const nodes = [
    ...depots.map(item => ({
      code: item.DEPOT_CODE,
      longitude: Number(item.LONGITUDE),
      latitude: Number(item.LATITUDE),
      type: 'Kho',
    })),
    ...customers.map(item => ({
      code: item.CUSTOMER_CODE,
      longitude: Number(item.LONGITUDE),
      latitude: Number(item.LATITUDE),
      type: 'Khách hàng',
      demand: item.DEMAND_WEIGHT,
    })),
  ];

  const positions = new Map();
  const coordinateKeys = new Set(nodes.map(node => `${node.longitude}:${node.latitude}`));
  const hasUsableCoordinates = nodes.length > 1
    && nodes.every(node => Number.isFinite(node.longitude) && Number.isFinite(node.latitude))
    && coordinateKeys.size > 1
    && nodes.some(node => node.longitude !== 0 || node.latitude !== 0);

  if (!hasUsableCoordinates) {
    // Dataset cũ không có tọa độ: depot ở tâm, customer phân bố đều quanh depot.
    nodes.forEach((node, index) => {
      if (index === 0) {
        positions.set(node.code, { ...node, longitude: 0, latitude: 0 });
        return;
      }
      const angle = ((index - 1) / Math.max(1, nodes.length - 1)) * Math.PI * 2;
      positions.set(node.code, {
        ...node,
        longitude: Math.cos(angle),
        latitude: Math.sin(angle),
      });
    });
  } else {
    nodes.forEach(node => positions.set(node.code, node));
  }

  const values = [...positions.values()];
  if (!values.length) return () => ({ x: 400, y: 300 });

  let minX = Math.min(...values.map(node => node.longitude));
  let maxX = Math.max(...values.map(node => node.longitude));
  let minY = Math.min(...values.map(node => node.latitude));
  let maxY = Math.max(...values.map(node => node.latitude));

  // Tránh chia cho 0 nhưng vẫn giữ đúng tâm nếu toàn bộ điểm thẳng hàng.
  const referenceSpan = Math.max(maxX - minX, maxY - minY, 0.001);
  if (minX === maxX) { minX -= referenceSpan / 2; maxX += referenceSpan / 2; }
  if (minY === maxY) { minY -= referenceSpan / 2; maxY += referenceSpan / 2; }
  const padX = (maxX - minX) * 0.1;
  const padY = (maxY - minY) * 0.1;

  return code => {
    const node = positions.get(code);
    if (!node) return { code, x: 400, y: 300, type: 'Không xác định' };
    return {
      ...node,
      x: 30 + ((node.longitude - minX + padX) / (maxX - minX + 2 * padX)) * 740,
      y: 570 - ((node.latitude - minY + padY) / (maxY - minY + 2 * padY)) * 540,
    };
  };
}
