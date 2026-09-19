import { useMemo, useState } from 'react';
import { ROUTE_COLORS } from '../routeColors';
import { createMapProjection } from '../mapProjection';

/** SVG độc lập, không cần API bản đồ ngoài; dùng chính tọa độ lưu trong dataset. */
export default function MapVisualizer({ depots = [], customers = [], routes = [] }) {
  const [hovered, setHovered] = useState(null);
  const point = useMemo(
    () => createMapProjection(depots, customers),
    [depots, customers],
  );

  return (
    <div className="map-visualizer">
      <svg viewBox="0 0 800 600" className="map-canvas">
        <defs><filter id="glow"><feGaussianBlur stdDeviation="3" result="blur" /><feMerge><feMergeNode in="blur" /><feMergeNode in="SourceGraphic" /></feMerge></filter></defs>
        <g opacity=".06">{[100, 200, 300, 400, 500, 600, 700].map(x => <line key={x} x1={x} y1="0" x2={x} y2="600" stroke="white" strokeDasharray="4 4" />)}{[100, 200, 300, 400, 500].map(y => <line key={y} x1="0" y1={y} x2="800" y2={y} stroke="white" strokeDasharray="4 4" />)}</g>
        {routes.map((route, index) => {
          const points = (route.stops || []).map(stop => point(stop.CODE));
          if (points.length < 2) return null;
          const path = points.map((point, pointIndex) => `${pointIndex ? 'L' : 'M'} ${point.x} ${point.y}`).join(' ');
          const color = ROUTE_COLORS[index % ROUTE_COLORS.length];
          return <g key={route.ROUTE_ID}><path d={path} fill="none" stroke={color} strokeWidth="8" opacity=".13" /><path d={path} className="route-path" stroke={color} /></g>;
        })}
        {customers.map(customer => {
          const position = point(customer.CUSTOMER_CODE);
          return <g key={customer.CUSTOMER_ID} transform={`translate(${position.x} ${position.y})`} className="node" onMouseEnter={() => setHovered(position)} onMouseLeave={() => setHovered(null)}>
            <circle r="6" fill="#0284c7" stroke="white" strokeWidth="1.5" />
            <text y="-10" fill="#e2e8f0" fontSize="10" textAnchor="middle">{customer.CUSTOMER_CODE}</text>
          </g>;
        })}
        {depots.map(depot => {
          const position = point(depot.DEPOT_CODE);
          return <g key={depot.DEPOT_ID} transform={`translate(${position.x} ${position.y})`} className="node" onMouseEnter={() => setHovered(position)} onMouseLeave={() => setHovered(null)}>
            <rect x="-8" y="-8" width="16" height="16" rx="3" fill="#ef4444" stroke="white" strokeWidth="2" filter="url(#glow)" />
            <text y="-13" fill="#fca5a5" fontSize="11" fontWeight="bold" textAnchor="middle">{depot.DEPOT_CODE}</text>
          </g>;
        })}
      </svg>
      <div className="map-legend"><strong>Chú thích</strong><span><i className="legend-depot" /> Kho trung tâm</span><span><i className="legend-customer" /> Khách hàng</span>{routes.map((route, index) => <span key={route.ROUTE_ID}><i style={{ background: ROUTE_COLORS[index % ROUTE_COLORS.length] }} /> {route.VEHICLE_CODE}</span>)}</div>
      {hovered && <div className="map-tooltip" style={{ left: `${hovered.x / 8}%`, top: `${hovered.y / 6}%` }}><strong>{hovered.code}</strong><span>{hovered.type}{hovered.demand != null ? ` · ${hovered.demand} kg` : ''}</span></div>}
    </div>
  );
}
