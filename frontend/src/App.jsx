import React, { useState, useEffect } from 'react';
import { 
  Route, Map, Truck, DollarSign, Activity, Play, Trash2, X, Settings, 
  PlusCircle, Database, Dices, Sparkles, AlertTriangle 
} from 'lucide-react';
import MapVisualizer, { ROUTE_COLORS } from './components/MapVisualizer';
import './index.css';

const CITY_PRESETS = {
  hcm: { name: 'TP. Hồ Chí Minh', lat: 10.7769, lng: 106.7009 },
  hanoi: { name: 'Hà Nội', lat: 21.0285, lng: 105.8542 },
  danang: { name: 'Đà Nẵng', lat: 16.0544, lng: 108.2022 }
};

function App() {
  const [experiments, setExperiments] = useState([]);
  const [selectedExp, setSelectedExp] = useState(null);
  const [detailData, setDetailData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [datasets, setDatasets] = useState([]);
  const [toast, setToast] = useState(null);

  // Run Settings Modal State
  const [showSettings, setShowSettings] = useState(false);
  const [runDatasetId, setRunDatasetId] = useState('');
  const [runSeed, setRunSeed] = useState(Date.now());
  const [runCapacityOverride, setRunCapacityOverride] = useState('');
  const [runningAlgo, setRunningAlgo] = useState(false);
  const [runError, setRunError] = useState('');

  // Create Dataset Modal State
  const [showCreateDataset, setShowCreateDataset] = useState(false);
  const [dsName, setDsName] = useState('');
  const [dsCustomerCount, setDsCustomerCount] = useState(8);
  const [dsVehicleCount, setDsVehicleCount] = useState(3);
  const [dsVehicleCapacity, setDsVehicleCapacity] = useState(40);
  const [dsMinDemand, setDsMinDemand] = useState(5);
  const [dsMaxDemand, setDsMaxDemand] = useState(15);
  const [dsRadiusKm, setDsRadiusKm] = useState(6);
  const [dsPreset, setDsPreset] = useState('hcm');
  const [creatingDataset, setCreatingDataset] = useState(false);
  const [createError, setCreateError] = useState('');

  const showToast = (message, type = 'success') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3500);
  };

  const fetchDatasets = async () => {
    try {
      const res = await fetch('http://localhost:8088/api/datasets');
      const data = await res.json();
      setDatasets(data);
      if (data.length > 0 && !runDatasetId) {
        setRunDatasetId(data[0].DATASET_ID);
      }
    } catch (err) {
      console.error('Failed to fetch datasets:', err);
    }
  };

  const fetchExperiments = async (selectId = null) => {
    try {
      const res = await fetch('http://localhost:8088/api/experiments');
      const data = await res.json();
      setExperiments(data);
      if (data.length > 0) {
        const targetId = selectId || data[0].EXPERIMENT_ID;
        handleSelectExperiment(targetId);
      } else {
        setLoading(false);
      }
    } catch (err) {
      console.error('Failed to fetch experiments:', err);
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchDatasets();
    fetchExperiments();
  }, []);

  const handleSelectExperiment = (id) => {
    setLoading(true);
    fetch(`http://localhost:8088/api/experiments/${id}`)
      .then(res => res.json())
      .then(data => {
        setDetailData(data);
        setSelectedExp(data.experiment);
        setLoading(false);
      })
      .catch(err => {
        console.error('Failed to fetch experiment detail:', err);
        setLoading(false);
      });
  };

  const handleDeleteExperiment = (id, e) => {
    e.stopPropagation();
    if (!window.confirm('Bạn có chắc chắn muốn xoá Experiment này khỏi cơ sở dữ liệu?')) return;

    fetch(`http://localhost:8088/api/experiments/${id}`, { method: 'DELETE' })
      .then(res => res.json())
      .then(data => {
        if (data.success) {
          showToast('Đã xoá experiment thành công!');
          setExperiments(prev => prev.filter(exp => exp.EXPERIMENT_ID !== id));
          if (selectedExp?.EXPERIMENT_ID === id) {
            setDetailData(null);
            setSelectedExp(null);
          }
        } else {
          showToast('Lỗi xoá: ' + data.error, 'error');
        }
      })
      .catch(err => showToast('Lỗi kết nối máy chủ', 'error'));
  };

  const handleRunAlgorithm = () => {
    setRunError('');
    setRunningAlgo(true);

    const payload = {
      datasetId: Number(runDatasetId),
      seed: Number(runSeed),
      capacityOverride: runCapacityOverride ? Number(runCapacityOverride) : null
    };

    fetch('http://localhost:8088/api/experiments/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })
      .then(async res => {
        const data = await res.json();
        if (!res.ok) {
          throw new Error(data.error || 'Có lỗi xảy ra khi giải bài toán');
        }
        return data;
      })
      .then(data => {
        setRunningAlgo(false);
        setShowSettings(false);
        setDetailData(data);
        setSelectedExp(data.experiment);
        showToast('Chạy thuật toán thành công!');
        fetchExperiments(data.experiment?.EXPERIMENT_ID);
      })
      .catch(err => {
        setRunningAlgo(false);
        setRunError(err.message);
      });
  };

  const handleCreateDataset = () => {
    setCreateError('');
    setCreatingDataset(true);

    const preset = CITY_PRESETS[dsPreset];
    const payload = {
      name: dsName.trim() || `Dataset ${CITY_PRESETS[dsPreset].name} (${dsCustomerCount} điểm)`,
      customerCount: Number(dsCustomerCount),
      vehicleCount: Number(dsVehicleCount),
      vehicleCapacity: Number(dsVehicleCapacity),
      minDemand: Number(dsMinDemand),
      maxDemand: Number(dsMaxDemand),
      centerLat: preset.lat,
      centerLng: preset.lng,
      radiusKm: Number(dsRadiusKm),
      seed: Date.now()
    };

    fetch('http://localhost:8088/api/datasets/generate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })
      .then(async res => {
        const data = await res.json();
        if (!res.ok) throw new Error(data.error || 'Lỗi khi tạo dataset');
        return data;
      })
      .then(data => {
        setCreatingDataset(false);
        setShowCreateDataset(false);
        showToast(`Đã tạo thành công dataset #${data.datasetId}!`);
        // Refresh datasets list and automatically set it in run modal
        fetchDatasets().then(() => {
          setRunDatasetId(data.datasetId);
          setShowSettings(true); // Prompt user to run immediately
        });
      })
      .catch(err => {
        setCreatingDataset(false);
        setCreateError(err.message);
      });
  };

  if (loading && experiments.length === 0) {
    return (
      <div className="app-container" style={{ justifyContent: 'center', alignItems: 'center', flexDirection: 'column', gap: '16px' }}>
        <div style={{ fontSize: '20px', fontWeight: 600 }}>Đang kết nối VRP Engine...</div>
        <div style={{ color: 'var(--text-muted)' }}>Đang tải dữ liệu từ Spring Boot API</div>
      </div>
    );
  }

  const expResult = selectedExp || {};
  const routes = detailData?.routes || [];

  return (
    <div className="app-container">
      {/* TOAST NOTIFICATION */}
      {toast && (
        <div className="toast-container">
          <div className={`toast ${toast.type === 'error' ? 'toast-error' : 'toast-success'}`}>
            {toast.message}
          </div>
        </div>
      )}

      {/* SIDEBAR */}
      <div className="sidebar glass-panel">
        <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <Activity size={22} className="text-accent-blue" />
              <span style={{ fontSize: '18px', fontWeight: 700 }}>VRP Optimizer</span>
            </div>
          </div>

          {/* ACTION BUTTONS */}
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '8px' }}>
            <button 
              onClick={() => {
                setDsName(`Dataset ${CITY_PRESETS[dsPreset].name} - ${dsCustomerCount} điểm`);
                setShowCreateDataset(true);
              }} 
              className="btn-create-dataset"
              title="Tạo bộ dữ liệu mới"
            >
              <PlusCircle size={15} /> Tạo Dataset
            </button>
            <button 
              onClick={() => {
                setRunError('');
                setShowSettings(true);
              }} 
              className="btn-run-demo"
              title="Cấu hình & Chạy giải thuật"
            >
              <Play size={14} fill="currentColor" /> Chạy Thuật Toán
            </button>
          </div>
        </div>

        {/* EXPERIMENTS LIST */}
        <div style={{ marginTop: '10px' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '10px' }}>
            <span style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.5px' }}>
              Lịch sử thử nghiệm ({experiments.length})
            </span>
          </div>

          <div style={{ maxHeight: '35vh', overflowY: 'auto' }}>
            {experiments.length === 0 ? (
              <div style={{ color: 'var(--text-muted)', fontSize: '13px', textAlign: 'center', padding: '20px 0' }}>
                Chưa có lượt chạy nào. Bấm "Chạy Thuật Toán" để bắt đầu!
              </div>
            ) : (
              experiments.map(exp => (
                <div 
                  key={exp.EXPERIMENT_ID} 
                  className={`experiment-item ${selectedExp?.EXPERIMENT_ID === exp.EXPERIMENT_ID ? 'active' : ''}`}
                  onClick={() => handleSelectExperiment(exp.EXPERIMENT_ID)}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <div style={{ fontWeight: 600, fontSize: '14px' }}>
                      {exp.EXPERIMENT_CODE || `Experiment #${exp.EXPERIMENT_ID}`}
                    </div>
                    <button 
                      className="btn-delete" 
                      onClick={(e) => handleDeleteExperiment(exp.EXPERIMENT_ID, e)}
                      title="Xoá Experiment"
                    >
                      <Trash2 size={15} />
                    </button>
                  </div>
                  <div className="experiment-meta">
                    <span>Quãng đường: {Number(exp.TOTAL_DISTANCE || 0).toFixed(1)} km</span>
                    <span style={{ 
                      color: exp.IS_FEASIBLE ? 'var(--accent-green)' : 'var(--accent-red)',
                      fontWeight: 600 
                    }}>
                      {exp.IS_FEASIBLE ? 'Khả thi' : 'Không khả thi'}
                    </span>
                  </div>
                </div>
              ))
            )}
          </div>
        </div>

        {/* VEHICLES & ROUTES BREAKDOWN */}
        <div style={{ marginTop: 'auto', borderTop: '1px solid var(--panel-border)', paddingTop: '16px' }}>
          <div className="sidebar-title" style={{ fontSize: '15px' }}>
            <Truck size={18} className="text-accent-purple" />
            <span>Phân Bổ Tuyến Xe ({routes.length} xe)</span>
          </div>
          
          <div style={{ maxHeight: '28vh', overflowY: 'auto' }}>
            {routes.length === 0 ? (
              <div style={{ color: 'var(--text-muted)', fontSize: '13px' }}>Chưa có thông tin tuyến xe.</div>
            ) : (
              routes.map((route, i) => {
                const color = ROUTE_COLORS[i % ROUTE_COLORS.length];
                const custCount = route.stops?.length ? Math.max(0, route.stops.length - 2) : 0;
                return (
                  <div 
                    key={route.ROUTE_ID} 
                    className="route-list-item" 
                    style={{ borderLeftColor: color }}
                  >
                    <div className="route-header">
                      <span style={{ color }}>{route.VEHICLE_CODE || `Xe ${route.VEHICLE_ID}`}</span>
                      <span style={{ fontSize: '12px' }}>{custCount} điểm dừng</span>
                    </div>
                    <div className="route-stats">
                      <span>Tải trọng: {Number(route.TOTAL_LOAD || 0).toFixed(1)} kg</span>
                      <span>{Number(route.TOTAL_DISTANCE || 0).toFixed(1)} km</span>
                    </div>
                  </div>
                );
              })
            )}
          </div>
        </div>
      </div>

      {/* MAIN CONTENT */}
      <div className="main-content">
        
        {/* METRICS CARDS */}
        <div className="metrics-container">
          <div className="metric-card glass-panel">
            <div className="metric-icon icon-blue"><Route size={24} /></div>
            <div className="metric-info">
              <h3>Tổng Quãng Đường</h3>
              <p>{Number(expResult.TOTAL_DISTANCE || 0).toFixed(2)} <span style={{ fontSize: '14px', color: 'var(--text-muted)' }}>km</span></p>
            </div>
          </div>
          
          <div className="metric-card glass-panel">
            <div className="metric-icon icon-purple"><Truck size={24} /></div>
            <div className="metric-info">
              <h3>Xe Sử Dụng</h3>
              <p>{expResult.VEHICLE_USED || 0} <span style={{ fontSize: '14px', color: 'var(--text-muted)' }}>xe</span></p>
            </div>
          </div>

          <div className="metric-card glass-panel">
            <div className="metric-icon icon-green"><DollarSign size={24} /></div>
            <div className="metric-info">
              <h3>Chi Phí / Hàm Mục Tiêu</h3>
              <p>{Number(expResult.OBJECTIVE_VALUE || 0).toFixed(1)}</p>
            </div>
          </div>

          <div className="metric-card glass-panel">
            <div className="metric-icon icon-blue"><Activity size={24} /></div>
            <div className="metric-info">
              <h3>Tính Khả Thi</h3>
              <p style={{ color: expResult.IS_FEASIBLE ? 'var(--accent-green)' : 'var(--accent-red)', fontSize: '20px' }}>
                {expResult.IS_FEASIBLE ? 'KHẢ THI (VALID)' : 'VI PHẠM (INVALID)'}
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
            <div style={{ color: 'var(--text-muted)' }}>Chọn một thử nghiệm hoặc tạo mới để hiển thị bản đồ</div>
          )}
        </div>
      </div>

      {/* MODAL 1: RUN SETTINGS & PARAMETERS */}
      {showSettings && (
        <div className="modal-overlay">
          <div className="modal-content glass-panel">
            <div className="modal-header">
              <h3><Settings size={18} /> Cấu Hình & Chạy Thuật Toán</h3>
              <button className="btn-close" onClick={() => setShowSettings(false)}><X size={20}/></button>
            </div>

            <div className="modal-body" style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
              {runError && (
                <div className="error-banner">
                  <AlertTriangle size={18} />
                  <span>{runError}</span>
                </div>
              )}

              {/* Dataset Selection */}
              <div className="form-group">
                <label>Chọn Dataset bài toán:</label>
                <select 
                  className="modal-select" 
                  value={runDatasetId} 
                  onChange={e => setRunDatasetId(e.target.value)}
                >
                  {datasets.map(ds => (
                    <option key={ds.DATASET_ID} value={ds.DATASET_ID}>
                      #{ds.DATASET_ID} - {ds.DATASET_NAME} ({ds.CUSTOMER_COUNT} khách, {ds.VEHICLE_COUNT} xe)
                    </option>
                  ))}
                </select>
              </div>

              {/* Vehicle Capacity Override */}
              <div className="form-group">
                <label>Ghi đè Tải trọng xe tối đa (kg) - Tùy chọn:</label>
                <input 
                  type="number" 
                  placeholder="Để trống để dùng tải trọng mặc định của xe"
                  value={runCapacityOverride} 
                  onChange={e => setRunCapacityOverride(e.target.value)} 
                  className="modal-input" 
                />
                <small style={{ color: 'var(--text-muted)' }}>
                  Cho phép thử nghiệm độ nhạy: giảm tải trọng để xem thuật toán cần thêm xe hay không.
                </small>
              </div>

              {/* Random Seed */}
              <div className="form-group">
                <label>Random Seed (Mầm sinh ngẫu nhiên):</label>
                <div style={{ display: 'flex', gap: '8px' }}>
                  <input 
                    type="number" 
                    value={runSeed} 
                    onChange={e => setRunSeed(e.target.value)} 
                    className="modal-input" 
                    style={{ flex: 1 }}
                  />
                  <button 
                    type="button"
                    onClick={() => setRunSeed(Math.floor(Math.random() * 100000000))} 
                    className="btn-cancel"
                    title="Sinh seed ngẫu nhiên mới"
                  >
                    <Dices size={16} />
                  </button>
                </div>
              </div>
            </div>

            <div className="modal-footer">
              <button onClick={() => setShowSettings(false)} className="btn-cancel">Hủy</button>
              <button 
                onClick={handleRunAlgorithm} 
                disabled={runningAlgo || !runDatasetId}
                className="btn-run-demo" 
                style={{ padding: '8px 16px', opacity: runningAlgo ? 0.7 : 1 }}
              >
                <Play size={16} fill="currentColor"/> 
                {runningAlgo ? 'Đang giải...' : 'Bắt Đầu Chạy'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* MODAL 2: DATASET GENERATOR */}
      {showCreateDataset && (
        <div className="modal-overlay">
          <div className="modal-content modal-lg glass-panel">
            <div className="modal-header">
              <h3><Sparkles size={18} className="text-accent-purple" /> Tạo Dataset Mới (Sinh Dữ Liệu VRP)</h3>
              <button className="btn-close" onClick={() => setShowCreateDataset(false)}><X size={20}/></button>
            </div>

            <div className="modal-body" style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
              {createError && (
                <div className="error-banner">
                  <AlertTriangle size={18} />
                  <span>{createError}</span>
                </div>
              )}

              {/* Dataset Name */}
              <div className="form-group">
                <label>Tên Dataset:</label>
                <input 
                  type="text" 
                  value={dsName} 
                  onChange={e => setDsName(e.target.value)} 
                  placeholder="Ví dụ: Demo Giao Hàng Quận 1" 
                  className="modal-input" 
                />
              </div>

              {/* City Preset */}
              <div className="form-group">
                <label>Khu vực trung tâm (Kho hàng đặt tại đây):</label>
                <div className="preset-container">
                  {Object.entries(CITY_PRESETS).map(([key, city]) => (
                    <button
                      key={key}
                      type="button"
                      className={`preset-pill ${dsPreset === key ? 'active' : ''}`}
                      onClick={() => {
                        setDsPreset(key);
                        setDsName(`Dataset ${city.name} - ${dsCustomerCount} điểm`);
                      }}
                    >
                      {city.name}
                    </button>
                  ))}
                </div>
              </div>

              {/* Customer Count & Vehicle Count */}
              <div className="form-row">
                <div className="form-group">
                  <label>Số điểm giao hàng (Khách):</label>
                  <input 
                    type="number" 
                    min="3" 
                    max="50" 
                    value={dsCustomerCount} 
                    onChange={e => {
                      const val = Number(e.target.value);
                      setDsCustomerCount(val);
                      setDsName(`Dataset ${CITY_PRESETS[dsPreset].name} - ${val} điểm`);
                    }} 
                    className="modal-input" 
                  />
                </div>
                <div className="form-group">
                  <label>Số lượng xe phục vụ:</label>
                  <input 
                    type="number" 
                    min="1" 
                    max="15" 
                    value={dsVehicleCount} 
                    onChange={e => setDsVehicleCount(Number(e.target.value))} 
                    className="modal-input" 
                  />
                </div>
              </div>

              {/* Vehicle Capacity & Radius */}
              <div className="form-row">
                <div className="form-group">
                  <label>Tải trọng mỗi xe (kg):</label>
                  <input 
                    type="number" 
                    min="10" 
                    value={dsVehicleCapacity} 
                    onChange={e => setDsVehicleCapacity(Number(e.target.value))} 
                    className="modal-input" 
                  />
                </div>
                <div className="form-group">
                  <label>Bán kính giao hàng (km):</label>
                  <input 
                    type="number" 
                    min="1" 
                    max="30" 
                    value={dsRadiusKm} 
                    onChange={e => setDsRadiusKm(Number(e.target.value))} 
                    className="modal-input" 
                  />
                </div>
              </div>

              {/* Min Demand & Max Demand */}
              <div className="form-row">
                <div className="form-group">
                  <label>Nhu cầu tối thiểu mỗi đơn (kg):</label>
                  <input 
                    type="number" 
                    min="1" 
                    value={dsMinDemand} 
                    onChange={e => setDsMinDemand(Number(e.target.value))} 
                    className="modal-input" 
                  />
                </div>
                <div className="form-group">
                  <label>Nhu cầu tối đa mỗi đơn (kg):</label>
                  <input 
                    type="number" 
                    min="1" 
                    value={dsMaxDemand} 
                    onChange={e => setDsMaxDemand(Number(e.target.value))} 
                    className="modal-input" 
                  />
                </div>
              </div>

              {/* Feasibility Preview Info Box */}
              <div style={{ 
                background: 'rgba(59, 130, 246, 0.1)', 
                border: '1px solid rgba(59, 130, 246, 0.25)', 
                borderRadius: '8px', 
                padding: '10px 14px', 
                fontSize: '12px',
                display: 'flex',
                justifyContent: 'space-between'
              }}>
                <div>
                  <span style={{ color: 'var(--text-muted)' }}>Ước tính tổng nhu cầu hàng: </span>
                  <strong style={{ color: '#38bdf8' }}>~{Math.round(dsCustomerCount * (dsMinDemand + dsMaxDemand) / 2)} kg</strong>
                </div>
                <div>
                  <span style={{ color: 'var(--text-muted)' }}>Tổng sức chở đội xe: </span>
                  <strong style={{ color: '#4ade80' }}>{dsVehicleCount * dsVehicleCapacity} kg</strong>
                </div>
              </div>
            </div>

            <div className="modal-footer">
              <button onClick={() => setShowCreateDataset(false)} className="btn-cancel">Hủy</button>
              <button 
                onClick={handleCreateDataset} 
                disabled={creatingDataset}
                className="btn-create-dataset" 
                style={{ padding: '8px 18px', opacity: creatingDataset ? 0.7 : 1 }}
              >
                <Sparkles size={16} /> 
                {creatingDataset ? 'Đang tạo...' : 'Tạo & Lưu Dataset'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default App;
