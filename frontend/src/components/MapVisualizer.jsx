import React, { useMemo } from 'react';

const COLORS = [
  '#3b82f6', // blue
  '#8b5cf6', // purple
  '#10b981', // green
  '#f59e0b', // yellow
  '#ef4444', // red
  '#ec4899', // pink
  '#06b6d4', // cyan
];

export default function MapVisualizer({ depots, customers, routes }) {
  
  // Calculate bounding box to scale SVG coordinates
  const { minX, maxX, minY, maxY } = useMemo(() => {
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    const updateBounds = (x, y) => {
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    };

    depots?.forEach(d => updateBounds(d.LONGITUDE, d.LATITUDE));
    customers?.forEach(c => updateBounds(c.LONGITUDE, c.LATITUDE));

    // Add some padding
    const padX = (maxX - minX) * 0.1 || 10;
    const padY = (maxY - minY) * 0.1 || 10;
    
    return {
      minX: minX - padX,
      maxX: maxX + padX,
      minY: minY - padY,
      maxY: maxY + padY
    };
  }, [depots, customers]);

  const scaleX = (x) => ((x - minX) / (maxX - minX)) * 800;
  const scaleY = (y) => (1 - (y - minY) / (maxY - minY)) * 600; // Invert Y axis for screen

  return (
    <svg viewBox="0 0 800 600" className="map-canvas">
      <defs>
        <filter id="glow">
          <feGaussianBlur stdDeviation="2.5" result="coloredBlur"/>
          <feMerge>
            <feMergeNode in="coloredBlur"/>
            <feMergeNode in="SourceGraphic"/>
          </feMerge>
        </filter>
      </defs>

      {/* Draw Routes */}
      {routes?.map((route, i) => {
        const color = COLORS[i % COLORS.length];
        const stops = route.stops || [];
        
        if (stops.length < 2) return null;

        const pathData = stops.map((stop, j) => {
          const x = scaleX(stop.LONGITUDE);
          const y = scaleY(stop.LATITUDE);
          return `${j === 0 ? 'M' : 'L'} ${x} ${y}`;
        }).join(' ');

        // Calculate stroke-dasharray for animation
        return (
          <path
            key={route.ROUTE_ID}
            className="route-path"
            d={pathData}
            stroke={color}
            style={{ 
              strokeDasharray: '4000',
              strokeDashoffset: '4000'
            }}
          />
        );
      })}

      {/* Draw Customers */}
      {customers?.map(c => (
        <g key={`c-${c.CUSTOMER_ID}`} className="node" transform={`translate(${scaleX(c.LONGITUDE)}, ${scaleY(c.LATITUDE)})`}>
          <circle r="4" fill="#94a3b8" />
          <text y="-8" fill="#cbd5e1" fontSize="10" textAnchor="middle">{c.CUSTOMER_CODE}</text>
        </g>
      ))}

      {/* Draw Depots */}
      {depots?.map(d => (
        <g key={`d-${d.DEPOT_ID}`} className="node" transform={`translate(${scaleX(d.LONGITUDE)}, ${scaleY(d.LATITUDE)})`}>
          <rect x="-6" y="-6" width="12" height="12" fill="#ef4444" filter="url(#glow)" />
          <text y="-10" fill="#fca5a5" fontSize="12" fontWeight="bold" textAnchor="middle">{d.DEPOT_CODE}</text>
        </g>
      ))}
    </svg>
  );
}
