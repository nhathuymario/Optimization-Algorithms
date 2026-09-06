import React, { useState, useMemo } from 'react';

export const ROUTE_COLORS = [
  '#3b82f6', // blue
  '#8b5cf6', // purple
  '#10b981', // green
  '#f59e0b', // yellow
  '#ec4899', // pink
  '#06b6d4', // cyan
  '#f97316', // orange
  '#14b8a6', // teal
];

export default function MapVisualizer({ depots, customers, routes }) {
  const [hoveredNode, setHoveredNode] = useState(null);

  // Map coordinates and calculate bounds
  const { coords, minX, maxX, minY, maxY } = useMemo(() => {
    let areAllZero = true;
    depots?.forEach(d => { if (d.LONGITUDE !== 0 || d.LATITUDE !== 0) areAllZero = false; });
    customers?.forEach(c => { if (c.LONGITUDE !== 0 || c.LATITUDE !== 0) areAllZero = false; });

    const nodeCoords = new Map();
    if (areAllZero) {
      depots?.forEach(d => nodeCoords.set(d.DEPOT_CODE, { x: 0, y: 0, label: d.DEPOT_CODE, type: 'depot' }));
      const radius = 100;
      customers?.forEach((c, i) => {
        const angle = (i / (customers.length || 1)) * Math.PI * 2;
        nodeCoords.set(c.CUSTOMER_CODE, {
          x: radius * Math.cos(angle),
          y: radius * Math.sin(angle),
          label: c.CUSTOMER_CODE,
          type: 'customer'
        });
      });
    } else {
      depots?.forEach(d => nodeCoords.set(d.DEPOT_CODE, {
        x: d.LONGITUDE,
        y: d.LATITUDE,
        label: d.DEPOT_CODE,
        name: d.DEPOT_NAME,
        type: 'depot'
      }));
      customers?.forEach(c => nodeCoords.set(c.CUSTOMER_CODE, {
        x: c.LONGITUDE,
        y: c.LATITUDE,
        label: c.CUSTOMER_CODE,
        name: c.CUSTOMER_NAME,
        type: 'customer'
      }));
    }

    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    nodeCoords.forEach(pos => {
      if (pos.x < minX) minX = pos.x;
      if (pos.x > maxX) maxX = pos.x;
      if (pos.y < minY) minY = pos.y;
      if (pos.y > maxY) maxY = pos.y;
    });

    if (minX === Infinity) { minX = 0; maxX = 10; minY = 0; maxY = 10; }
    if (minX === maxX) { minX -= 1; maxX += 1; }
    if (minY === maxY) { minY -= 1; maxY += 1; }

    const padX = (maxX - minX) * 0.12 || 1;
    const padY = (maxY - minY) * 0.12 || 1;

    return {
      coords: nodeCoords,
      minX: minX - padX,
      maxX: maxX + padX,
      minY: minY - padY,
      maxY: maxY + padY
    };
  }, [depots, customers]);

  const scaleX = (x) => ((x - minX) / (maxX - minX)) * 800;
  const scaleY = (y) => (1 - (y - minY) / (maxY - minY)) * 540 + 30;

  return (
    <div style={{ position: 'relative', width: '100%', height: '100%' }}>
      <svg viewBox="0 0 800 600" className="map-canvas" style={{ width: '100%', height: '100%' }}>
        <defs>
          <filter id="glow">
            <feGaussianBlur stdDeviation="3" result="coloredBlur"/>
            <feMerge>
              <feMergeNode in="coloredBlur"/>
              <feMergeNode in="SourceGraphic"/>
            </feMerge>
          </filter>
          <filter id="node-shadow">
            <feDropShadow dx="0" dy="2" stdDeviation="2" floodOpacity="0.5"/>
          </filter>
        </defs>

        {/* Grid Background Lines for Tech Feel */}
        <g opacity="0.06">
          {[100, 200, 300, 400, 500, 600, 700].map(x => (
            <line key={`gx-${x}`} x1={x} y1="0" x2={x} y2="600" stroke="#ffffff" strokeDasharray="4 4" />
          ))}
          {[100, 200, 300, 400, 500].map(y => (
            <line key={`gy-${y}`} x1="0" y1={y} x2="800" y2={y} stroke="#ffffff" strokeDasharray="4 4" />
          ))}
        </g>

        {/* Draw Routes */}
        {routes?.map((route, i) => {
          const color = ROUTE_COLORS[i % ROUTE_COLORS.length];
          const stops = route.stops || [];
          if (stops.length < 2) return null;

          const pathData = stops.map((stop, j) => {
            const pos = coords.get(stop.CODE) || { x: 0, y: 0 };
            const x = scaleX(pos.x);
            const y = scaleY(pos.y);
            return `${j === 0 ? 'M' : 'L'} ${x} ${y}`;
          }).join(' ');

          return (
            <g key={`route-${route.ROUTE_ID}`}>
              {/* Route glow line */}
              <path
                d={pathData}
                fill="none"
                stroke={color}
                strokeWidth="6"
                strokeOpacity="0.2"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
              {/* Main route path */}
              <path
                className="route-path"
                d={pathData}
                fill="none"
                stroke={color}
                strokeWidth="3"
                strokeLinecap="round"
                strokeLinejoin="round"
                style={{
                  strokeDasharray: '2500',
                  strokeDashoffset: '2500'
                }}
              />
            </g>
          );
        })}

        {/* Draw Customers */}
        {customers?.map(c => {
          const pos = coords.get(c.CUSTOMER_CODE) || { x: c.LONGITUDE, y: c.LATITUDE };
          const cx = scaleX(pos.x);
          const cy = scaleY(pos.y);
          const isHovered = hoveredNode?.label === c.CUSTOMER_CODE;

          return (
            <g
              key={`c-${c.CUSTOMER_ID}`}
              className="node"
              transform={`translate(${cx}, ${cy})`}
              onMouseEnter={() => setHoveredNode({
                label: c.CUSTOMER_CODE,
                type: 'Khách hàng',
                lat: pos.x,
                lng: pos.y,
                x: cx,
                y: cy
              })}
              onMouseLeave={() => setHoveredNode(null)}
              style={{ cursor: 'pointer' }}
            >
              <circle
                r={isHovered ? "9" : "6"}
                fill={isHovered ? "#38bdf8" : "#0284c7"}
                stroke="#ffffff"
                strokeWidth={isHovered ? "2.5" : "1.5"}
                filter="url(#node-shadow)"
              />
              <text
                y={isHovered ? "-13" : "-10"}
                fill={isHovered ? "#38bdf8" : "#e2e8f0"}
                fontSize={isHovered ? "12" : "10"}
                fontWeight={isHovered ? "bold" : "500"}
                textAnchor="middle"
              >
                {c.CUSTOMER_CODE}
              </text>
            </g>
          );
        })}

        {/* Draw Depots */}
        {depots?.map(d => {
          const pos = coords.get(d.DEPOT_CODE) || { x: d.LONGITUDE, y: d.LATITUDE };
          const cx = scaleX(pos.x);
          const cy = scaleY(pos.y);
          const isHovered = hoveredNode?.label === d.DEPOT_CODE;

          return (
            <g
              key={`d-${d.DEPOT_ID}`}
              className="node"
              transform={`translate(${cx}, ${cy})`}
              onMouseEnter={() => setHoveredNode({
                label: d.DEPOT_CODE,
                type: 'Kho Trung Tâm (Depot)',
                lat: pos.x,
                lng: pos.y,
                x: cx,
                y: cy
              })}
              onMouseLeave={() => setHoveredNode(null)}
              style={{ cursor: 'pointer' }}
            >
              <rect
                x={isHovered ? "-9" : "-7"}
                y={isHovered ? "-9" : "-7"}
                width={isHovered ? "18" : "14"}
                height={isHovered ? "18" : "14"}
                rx="3"
                fill="#ef4444"
                stroke="#ffffff"
                strokeWidth={isHovered ? "2.5" : "2"}
                filter="url(#glow)"
              />
              <text
                y={isHovered ? "-15" : "-12"}
                fill="#fca5a5"
                fontSize="12"
                fontWeight="bold"
                textAnchor="middle"
              >
                {d.DEPOT_CODE}
              </text>
            </g>
          );
        })}
      </svg>

      {/* Floating Legend */}
      <div style={{
        position: 'absolute',
        top: '16px',
        right: '16px',
        background: 'rgba(15, 23, 42, 0.85)',
        border: '1px solid rgba(255, 255, 255, 0.1)',
        borderRadius: '10px',
        padding: '10px 14px',
        fontSize: '12px',
        backdropFilter: 'blur(8px)',
        pointerEvents: 'none',
        display: 'flex',
        flexDirection: 'column',
        gap: '6px',
        boxShadow: '0 4px 16px rgba(0, 0, 0, 0.4)'
      }}>
        <div style={{ fontWeight: 600, color: '#94a3b8', borderBottom: '1px solid rgba(255,255,255,0.08)', paddingBottom: '4px' }}>
          Chú Thích Bản Đồ
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <span style={{ width: '10px', height: '10px', background: '#ef4444', borderRadius: '2px', display: 'inline-block' }}></span>
          <span>Kho Trung Tâm (D0)</span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <span style={{ width: '10px', height: '10px', background: '#0284c7', borderRadius: '50%', display: 'inline-block' }}></span>
          <span>Điểm Khách Hàng (C...)</span>
        </div>
        {routes && routes.length > 0 && (
          <div style={{ borderTop: '1px solid rgba(255,255,255,0.08)', paddingTop: '4px', marginTop: '2px' }}>
            <div style={{ color: '#94a3b8', fontSize: '11px', marginBottom: '4px' }}>Lộ trình các xe:</div>
            {routes.map((r, i) => (
              <div key={r.ROUTE_ID} style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '11px' }}>
                <span style={{ width: '12px', height: '3px', background: ROUTE_COLORS[i % ROUTE_COLORS.length], display: 'inline-block', borderRadius: '2px' }}></span>
                <span>{r.VEHICLE_CODE || `Xe ${i + 1}`} ({r.stops?.length ? r.stops.length - 2 : 0} khách)</span>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Floating Hover Tooltip */}
      {hoveredNode && (
        <div style={{
          position: 'absolute',
          left: `${(hoveredNode.x / 800) * 100}%`,
          top: `${(hoveredNode.y / 600) * 100}%`,
          transform: 'translate(-50%, -130%)',
          background: 'rgba(30, 41, 59, 0.95)',
          border: '1px solid #38bdf8',
          color: '#fff',
          padding: '6px 10px',
          borderRadius: '6px',
          fontSize: '12px',
          whiteSpace: 'nowrap',
          pointerEvents: 'none',
          boxShadow: '0 4px 12px rgba(0,0,0,0.5)',
          zIndex: 10
        }}>
          <div style={{ fontWeight: 'bold', color: '#38bdf8' }}>{hoveredNode.label}</div>
          <div style={{ color: '#94a3b8', fontSize: '11px' }}>{hoveredNode.type}</div>
        </div>
      )}
    </div>
  );
}

