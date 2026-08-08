import React, { useState, useEffect } from 'react';
import { Route, Map, Truck, DollarSign, Activity } from 'lucide-react';
import MapVisualizer from './components/MapVisualizer';
import './index.css';

function App() {
  const [experiments, setExperiments] = useState([]);
  const [selectedExp, setSelectedExp] = useState(null);
  const [detailData, setDetailData] = useState(null);
  const [loading, setLoading] = useState(true);

  // Fetch list of experiments on mount
  useEffect(() => {
    fetch('http://localhost:8080/api/experiments')
      .then(res => res.json())
      .then(data => {
        setExperiments(data);
        if (data.length > 0) {
          handleSelectExperiment(data[0].EXPERIMENT_ID);
        } else {
          setLoading(false);
        }
      })
      .catch(err => {
        console.error("Failed to fetch experiments:", err);
        setLoading(false);
      });
  }, []);

  const handleSelectExperiment = (id) => {
    setLoading(true);
    fetch(`http://localhost:8080/api/experiments/${id}`)
      .then(res => res.json())
      .then(data => {
        setDetailData(data);
        setSelectedExp(data.experiment);
        setLoading(false);
      })
      .catch(err => {
        console.error("Failed to fetch experiment detail:", err);
        setLoading(false);
      });
  };

  if (loading && experiments.length === 0) {
    return <div className="app-container" style={{ justifyContent: 'center', alignItems: 'center' }}>Loading VRP Data...</div>;
  }

  const expResult = selectedExp || {};
  const routes = detailData?.routes || [];

  return (
    <div className="app-container">
      {/* SIDEBAR */}
      <div className="sidebar glass-panel">
        <div className="sidebar-title">
          <Activity size={20} className="text-accent-blue" />
          <span>VRP Experiments</span>
        </div>
        
        <div style={{ marginBottom: '20px' }}>
          {experiments.map(exp => (
            <div 
              key={exp.EXPERIMENT_ID} 
              className={`experiment-item ${selectedExp?.EXPERIMENT_ID === exp.EXPERIMENT_ID ? 'active' : ''}`}
              onClick={() => handleSelectExperiment(exp.EXPERIMENT_ID)}
            >
              <div style={{ fontWeight: 600 }}>{exp.EXPERIMENT_CODE || `Experiment #${exp.EXPERIMENT_ID}`}</div>
              <div className="experiment-meta">
                <span>Dist: {Number(exp.TOTAL_DISTANCE || 0).toFixed(2)}</span>
                <span style={{ color: exp.IS_FEASIBLE ? 'var(--accent-green)' : 'var(--accent-red)' }}>
                  {exp.IS_FEASIBLE ? 'Feasible' : 'Infeasible'}
                </span>
              </div>
            </div>
          ))}
        </div>

        <div className="sidebar-title" style={{ marginTop: 'auto' }}>
          <Truck size={20} className="text-accent-purple" />
          <span>Vehicles & Routes</span>
        </div>
        
        <div style={{ overflowY: 'auto' }}>
          {routes.map((route, i) => (
            <div key={route.ROUTE_ID} className="route-list-item" style={{ borderLeftColor: `var(--accent-blue)` }}>
              <div className="route-header">
                <span>{route.VEHICLE_CODE || `Vehicle ${route.VEHICLE_ID}`}</span>
                <span>Stop: {route.stops?.length || 0}</span>
              </div>
              <div className="route-stats">
                <span>Load: {Number(route.TOTAL_LOAD || 0).toFixed(2)}</span>
                <span>Dist: {Number(route.TOTAL_DISTANCE || 0).toFixed(2)}</span>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* MAIN CONTENT */}
      <div className="main-content">
        
        {/* METRICS CARDS */}
        <div className="metrics-container">
          <div className="metric-card glass-panel">
            <div className="metric-icon icon-blue"><Route size={24} /></div>
            <div className="metric-info">
              <h3>Total Distance</h3>
              <p>{Number(expResult.TOTAL_DISTANCE || 0).toFixed(2)}</p>
            </div>
          </div>
          
          <div className="metric-card glass-panel">
            <div className="metric-icon icon-purple"><Truck size={24} /></div>
            <div className="metric-info">
              <h3>Vehicles Used</h3>
              <p>{expResult.VEHICLE_USED || 0}</p>
            </div>
          </div>

          <div className="metric-card glass-panel">
            <div className="metric-icon icon-green"><DollarSign size={24} /></div>
            <div className="metric-info">
              <h3>Total Cost / Obj</h3>
              <p>{Number(expResult.OBJECTIVE_VALUE || 0).toFixed(2)}</p>
            </div>
          </div>

          <div className="metric-card glass-panel">
            <div className="metric-icon icon-blue"><Activity size={24} /></div>
            <div className="metric-info">
              <h3>Status</h3>
              <p style={{ color: expResult.IS_FEASIBLE ? 'var(--accent-green)' : 'var(--accent-red)', fontSize: '20px' }}>
                {expResult.IS_FEASIBLE ? 'VALID' : 'INVALID'}
              </p>
            </div>
          </div>
        </div>

        {/* MAP VISUALIZER */}
        <div className="map-container glass-panel">
          {detailData ? (
            <MapVisualizer 
              depots={detailData.depots} 
              customers={detailData.customers} 
              routes={detailData.routes} 
            />
          ) : (
            <div style={{ color: 'var(--text-muted)' }}>Select an experiment to view map</div>
          )}
        </div>
      </div>
    </div>
  );
}

export default App;
