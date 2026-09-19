import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Activity, AlertTriangle, BarChart3, CheckCircle2, Clock3, Database,
  Dices, ListChecks, Map, Play, PlusCircle, Route, Settings, Sparkles,
  Trash2, Truck, X, XCircle,
} from 'lucide-react';
import MapVisualizer from './components/MapVisualizer';
import { apiFetch, buildRunPayload, defaultParameters, formatClock } from './api';
import { ROUTE_COLORS } from './routeColors';
import './index.css';

const CITY_PRESETS = {
  hcm: { name: 'TP. Hồ Chí Minh', lat: 10.7769, lng: 106.7009 },
  hanoi: { name: 'Hà Nội', lat: 21.0285, lng: 105.8542 },
  danang: { name: 'Đà Nẵng', lat: 16.0544, lng: 108.2022 },
};

const EMPTY_RUN = {
  datasetId: '', algorithmCode: '', seed: Date.now(), capacityOverride: '',
  iterationLimit: 100, timeLimitSeconds: 0, parameters: {},
};

const EMPTY_DATASET = {
  name: '', datasetType: 'CVRP', customerCount: 8, vehicleCount: 3,
  vehicleCapacity: 40, minDemand: 5, maxDemand: 15, radiusKm: 6, preset: 'hcm',
};

function MetricCard({ icon, title, value, suffix, tone = 'blue' }) {
  return (
    <div className="metric-card glass-panel">
      <div className={`metric-icon icon-${tone}`}>{icon}</div>
      <div className="metric-info"><h3>{title}</h3><p>{value} {suffix && <small>{suffix}</small>}</p></div>
    </div>
  );
}

function ConvergenceChart({ iterations }) {
  if (!iterations?.length) return <Empty text="Thuật toán này chưa tạo log hội tụ." />;
  const values = iterations.map(row => Number(row.BEST_DISTANCE ?? row.BEST_OBJECTIVE ?? 0));
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  const points = values.map((value, index) => {
    const x = iterations.length === 1 ? 20 : 20 + (index / (iterations.length - 1)) * 760;
    const y = 270 - ((value - min) / span) * 230;
    return `${x},${y}`;
  }).join(' ');
  return (
    <div className="chart-wrap">
      <svg viewBox="0 0 800 300" role="img" aria-label="Biểu đồ hội tụ">
        <line x1="20" y1="270" x2="780" y2="270" className="chart-axis" />
        <line x1="20" y1="30" x2="20" y2="270" className="chart-axis" />
        <polyline points={points} className="chart-line" />
        <text x="25" y="25" className="chart-label">{max.toFixed(2)} km</text>
        <text x="25" y="292" className="chart-label">Best: {min.toFixed(2)} km</text>
      </svg>
      <div className="table-scroll">
        <table><thead><tr><th>Vòng</th><th>Best distance</th><th>Xe tốt nhất</th><th>Thời gian</th><th>Ghi chú</th></tr></thead>
          <tbody>{iterations.map(row => <tr key={row.ITERATION_NO}>
            <td>{row.ITERATION_NO}</td><td>{Number(row.BEST_DISTANCE || 0).toFixed(3)}</td>
            <td>{row.BEST_VEHICLE_USED ?? '-'}</td><td>{row.ELAPSED_TIME_MS} ms</td><td>{row.NOTES || '-'}</td>
          </tr>)}</tbody>
        </table>
      </div>
    </div>
  );
}

function Empty({ text }) { return <div className="empty-state">{text}</div>; }

function App() {
  const [experiments, setExperiments] = useState([]);
  const [datasets, setDatasets] = useState([]);
  const [algorithms, setAlgorithms] = useState([]);
  const [detail, setDetail] = useState(null);
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState('');
  const [toast, setToast] = useState(null);
  const [tab, setTab] = useState('map');
  const [showRun, setShowRun] = useState(false);
  const [showCreate, setShowCreate] = useState(false);
  const [showDatasets, setShowDatasets] = useState(false);
  const [runConfig, setRunConfig] = useState(EMPTY_RUN);
  const [datasetForm, setDatasetForm] = useState(EMPTY_DATASET);
  const [busy, setBusy] = useState(false);
  const [modalError, setModalError] = useState('');
  const [compareIds, setCompareIds] = useState([]);
  const [compareData, setCompareData] = useState(null);

  const selected = detail?.experiment;
  const routes = detail?.routes || [];
  const selectedAlgorithm = algorithms.find(item => item.code === runConfig.algorithmCode);

  const notify = useCallback((message, type = 'success') => {
    setToast({ message, type });
    window.setTimeout(() => setToast(null), 3500);
  }, []);

  const selectExperiment = useCallback(async id => {
    setLoading(true);
    try {
      setDetail(await apiFetch(`/experiments/${id}`));
      setPageError('');
    } catch (error) {
      setPageError(error.message);
    } finally {
      setLoading(false);
    }
  }, []);

  const refreshLists = useCallback(async preferredExperimentId => {
    const [experimentRows, datasetRows, algorithmRows] = await Promise.all([
      apiFetch('/experiments'), apiFetch('/datasets'), apiFetch('/algorithms'),
    ]);
    setExperiments(experimentRows);
    setDatasets(datasetRows);
    setAlgorithms(algorithmRows.filter(item => item.implemented));
    const firstAlgorithm = algorithmRows.find(item => item.implemented);
    setRunConfig(current => ({
      ...current,
      datasetId: current.datasetId || datasetRows[0]?.DATASET_ID || '',
      algorithmCode: current.algorithmCode || firstAlgorithm?.code || '',
      parameters: Object.keys(current.parameters).length ? current.parameters : defaultParameters(firstAlgorithm),
    }));
    const targetId = preferredExperimentId || experimentRows[0]?.EXPERIMENT_ID;
    if (targetId) await selectExperiment(targetId);
    else setLoading(false);
  }, [selectExperiment]);

  useEffect(() => {
    refreshLists().catch(error => { setPageError(error.message); setLoading(false); });
  }, [refreshLists]);

  const updateAlgorithm = code => {
    const algorithm = algorithms.find(item => item.code === code);
    setRunConfig(current => ({ ...current, algorithmCode: code, parameters: defaultParameters(algorithm) }));
  };

  const runAlgorithm = async event => {
    event.preventDefault(); setBusy(true); setModalError('');
    try {
      const data = await apiFetch('/experiments/run', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(buildRunPayload(runConfig)),
      });
      setDetail(data); setShowRun(false); notify('Đã chạy và lưu kết quả thuật toán.');
      await refreshLists(data.experiment.EXPERIMENT_ID);
    } catch (error) { setModalError(error.message); }
    finally { setBusy(false); }
  };

  const createDataset = async event => {
    event.preventDefault(); setBusy(true); setModalError('');
    const city = CITY_PRESETS[datasetForm.preset];
    try {
      const created = await apiFetch('/datasets/generate', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          ...datasetForm, name: datasetForm.name.trim() || `${datasetForm.datasetType} ${city.name}`,
          centerLat: city.lat, centerLng: city.lng, seed: Date.now(), preset: undefined,
        }),
      });
      setShowCreate(false); notify(`Đã tạo dataset #${created.datasetId}.`);
      const rows = await apiFetch('/datasets'); setDatasets(rows);
      setRunConfig(current => ({ ...current, datasetId: created.datasetId })); setShowRun(true);
    } catch (error) { setModalError(error.message); }
    finally { setBusy(false); }
  };

  const deleteExperiment = async (id, event) => {
    event.stopPropagation();
    if (!window.confirm('Xóa vĩnh viễn experiment và toàn bộ route/stop liên quan?')) return;
    try {
      await apiFetch(`/experiments/${id}`, { method: 'DELETE' });
      notify('Đã xóa experiment.'); setDetail(null); await refreshLists();
    } catch (error) { notify(error.message, 'error'); }
  };

  const deleteDataset = async id => {
    if (!window.confirm('Xóa dataset sẽ xóa cả experiment, route và kết quả liên quan. Tiếp tục?')) return;
    try {
      await apiFetch(`/datasets/${id}`, { method: 'DELETE' });
      notify('Đã xóa dataset và dữ liệu phụ thuộc.'); setDetail(null); await refreshLists();
    } catch (error) { notify(error.message, 'error'); }
  };

  const compare = async () => {
    if (compareIds.length < 2) { notify('Chọn ít nhất 2 experiment để so sánh.', 'error'); return; }
    try { setCompareData(await apiFetch(`/experiments/compare?ids=${compareIds.join(',')}`)); }
    catch (error) { notify(error.message, 'error'); }
  };

  const validations = useMemo(() => detail?.validation ? [
    ['Bao phủ đơn hàng', detail.validation.COVERAGE_VALID],
    ['Tải trọng', detail.validation.CAPACITY_VALID],
    ['Time window', detail.validation.TIME_WINDOW_VALID],
    ['Cấu trúc route', detail.validation.STRUCTURE_VALID],
    ['Kết quả tính lại', detail.validation.CALCULATED_FEASIBLE],
    ['Cờ đã lưu', detail.validation.STORED_FEASIBLE],
  ] : [], [detail]);

  if (loading && !experiments.length && !pageError) {
    return <div className="center-screen"><Activity className="spin" /> Đang tải VRP Engine…</div>;
  }

  return (
    <div className="app-container">
      {toast && <div className={`toast toast-${toast.type}`}>{toast.message}</div>}
      <aside className="sidebar glass-panel">
        <div className="brand"><Activity className="text-blue" /><div><strong>VRP Optimizer</strong><small>CVRP · VRPTW · TDVRPTW</small></div></div>
        <div className="action-grid">
          <button className="btn primary" onClick={() => { setModalError(''); setShowRun(true); }}><Play size={15} /> Chạy</button>
          <button className="btn purple" onClick={() => { setModalError(''); setShowCreate(true); }}><PlusCircle size={15} /> Dataset</button>
          <button className="btn subtle span-2" onClick={() => setShowDatasets(true)}><Database size={15} /> Quản lý {datasets.length} dataset</button>
        </div>
        <div className="section-heading"><span>Lịch sử ({experiments.length})</span><button className="link-btn" onClick={compare}>So sánh</button></div>
        <div className="experiment-list">
          {experiments.length === 0 && <Empty text="Chưa có lượt chạy." />}
          {experiments.map(exp => <div key={exp.EXPERIMENT_ID} className={`experiment-item ${selected?.EXPERIMENT_ID === exp.EXPERIMENT_ID ? 'active' : ''}`} onClick={() => selectExperiment(exp.EXPERIMENT_ID)}>
            <div className="item-title"><label onClick={event => event.stopPropagation()}><input type="checkbox" checked={compareIds.includes(exp.EXPERIMENT_ID)} onChange={event => setCompareIds(ids => event.target.checked ? [...ids, exp.EXPERIMENT_ID] : ids.filter(id => id !== exp.EXPERIMENT_ID))} /></label><strong>{exp.EXPERIMENT_CODE}</strong><button className="icon-btn danger" onClick={event => deleteExperiment(exp.EXPERIMENT_ID, event)}><Trash2 size={14} /></button></div>
            <div className="pill-row"><span className="pill">{exp.ALGORITHM_CODE}</span><span className={`pill ${exp.IS_FEASIBLE ? 'ok' : 'bad'}`}>{exp.IS_FEASIBLE ? 'Khả thi' : 'Vi phạm'}</span></div>
            <div className="item-meta"><span>{Number(exp.TOTAL_DISTANCE || 0).toFixed(2)} km</span><span>{exp.VEHICLE_USED || 0} xe</span></div>
          </div>)}
        </div>
        <div className="route-summary">
          <div className="section-heading"><span><Truck size={16} /> Tuyến xe ({routes.length})</span></div>
          {routes.map((route, index) => <div key={route.ROUTE_ID} className="route-list-item" style={{ borderLeftColor: ROUTE_COLORS[index % ROUTE_COLORS.length] }}>
            <strong>{route.VEHICLE_CODE}</strong><span>{Math.max(0, (route.stops?.length || 2) - 2)} khách</span>
            <small>{Number(route.TOTAL_LOAD).toFixed(1)}/{Number(route.CAPACITY_WEIGHT).toFixed(1)} kg · {Number(route.TOTAL_DISTANCE).toFixed(2)} km</small>
          </div>)}
        </div>
      </aside>

      <main className="main-content">
        {pageError && <div className="error-banner"><AlertTriangle /> {pageError}</div>}
        <div className="page-title"><div><h1>{selected?.EXPERIMENT_CODE || 'Chưa có experiment'}</h1><p>{selected ? `${selected.DATASET_NAME} · ${selected.DATASET_TYPE} · ${selected.ALGORITHM_NAME}` : 'Tạo dataset rồi chạy một thuật toán để bắt đầu.'}</p></div>{loading && <Activity className="spin" />}</div>
        <div className="metrics-container">
          <MetricCard icon={<Route />} title="Tổng quãng đường" value={Number(selected?.TOTAL_DISTANCE || 0).toFixed(2)} suffix="km" />
          <MetricCard icon={<Truck />} title="Số xe sử dụng" value={selected?.VEHICLE_USED || 0} suffix="xe" tone="purple" />
          <MetricCard icon={<Clock3 />} title="Di chuyển / Chờ" value={`${formatClock(selected?.TOTAL_TRAVEL_TIME)} / ${formatClock(selected?.TOTAL_WAITING_TIME)}`} tone="green" />
          <MetricCard icon={selected?.IS_FEASIBLE ? <CheckCircle2 /> : <XCircle />} title="Validation" value={selected?.IS_FEASIBLE ? 'KHẢ THI' : 'VI PHẠM'} tone={selected?.IS_FEASIBLE ? 'green' : 'red'} />
        </div>
        <div className="tabs">
          {[['map', Map, 'Bản đồ'], ['routes', ListChecks, 'Lịch trình'], ['convergence', BarChart3, 'Hội tụ'], ['validation', CheckCircle2, 'Kiểm định']].map(([key, Icon, label]) => <button key={key} className={tab === key ? 'active' : ''} onClick={() => setTab(key)}><Icon size={16} /> {label}</button>)}
        </div>
        <section className="workspace glass-panel">
          {!detail && <Empty text="Chưa có dữ liệu kết quả để phân tích." />}
          {detail && tab === 'map' && <MapVisualizer depots={detail.depots} customers={detail.customers} routes={routes} />}
          {detail && tab === 'routes' && <div className="route-details">{routes.map((route, routeIndex) => <article key={route.ROUTE_ID} className="route-card">
            <h3 style={{ color: ROUTE_COLORS[routeIndex % ROUTE_COLORS.length] }}>{route.VEHICLE_CODE} <span>{route.VEHICLE_TYPE}</span></h3>
            <p>{Number(route.TOTAL_DISTANCE).toFixed(2)} km · tải {Number(route.TOTAL_LOAD).toFixed(1)} kg · {formatClock(route.START_TIME)}–{formatClock(route.END_TIME)}</p>
            <div className="table-scroll"><table><thead><tr><th>#</th><th>Điểm</th><th>Loại</th><th>Đến</th><th>Bắt đầu</th><th>Rời</th><th>Chờ</th><th>Tải sau</th><th>Vi phạm</th></tr></thead>
              <tbody>{route.stops?.map(stop => <tr key={`${route.ROUTE_ID}-${stop.SEQUENCE_NO}`}><td>{stop.SEQUENCE_NO}</td><td>{stop.CODE}</td><td>{stop.STOP_TYPE}</td><td>{formatClock(stop.ARRIVAL_TIME)}</td><td>{formatClock(stop.SERVICE_START_TIME)}</td><td>{formatClock(stop.DEPARTURE_TIME)}</td><td>{stop.WAITING_TIME}s</td><td>{Number(stop.LOAD_AFTER_WEIGHT).toFixed(1)}</td><td>{Number(stop.CAPACITY_VIOLATION) + Number(stop.TIME_WINDOW_VIOLATION) > 0 ? 'Có' : 'Không'}</td></tr>)}</tbody>
            </table></div>
          </article>)}</div>}
          {detail && tab === 'convergence' && <ConvergenceChart iterations={detail.iterations} />}
          {detail && tab === 'validation' && <div><div className="validation-grid">{validations.map(([label, valid]) => <div key={label} className={`validation-item ${valid ? 'ok' : 'bad'}`}>{valid ? <CheckCircle2 /> : <XCircle />}<span>{label}</span><strong>{valid ? 'Đạt' : 'Không đạt'}</strong></div>)}</div>
            <h3 className="subheading">Tham số thực thi</h3><div className="parameter-list">{Object.entries(detail.parameters || {}).map(([key, value]) => <span key={key}><strong>{key}</strong>: {value}</span>)}</div>
            <div className="kpi-strip"><span>Objective: <strong>{Number(selected.OBJECTIVE_VALUE).toFixed(3)}</strong></span><span>CPU: <strong>{selected.EXECUTION_TIME_MS} ms</strong></span><span>Vòng tìm thấy: <strong>{selected.ITERATION_FOUND ?? '-'}</strong></span><span>Đơn chưa phục vụ: <strong>{selected.UNSERVED_ORDER_COUNT}</strong></span></div>
          </div>}
        </section>
      </main>

      {showRun && <Modal title="Cấu hình thuật toán" icon={<Settings />} onClose={() => setShowRun(false)} wide>
        <form onSubmit={runAlgorithm}><ModalError text={modalError} /><div className="form-row"><Field label="Dataset"><select value={runConfig.datasetId} onChange={event => setRunConfig({ ...runConfig, datasetId: event.target.value })}>{datasets.map(ds => <option key={ds.DATASET_ID} value={ds.DATASET_ID}>{ds.DATASET_CODE} · {ds.DATASET_TYPE} · {ds.CUSTOMER_COUNT} khách</option>)}</select></Field>
          <Field label="Thuật toán"><select value={runConfig.algorithmCode} onChange={event => updateAlgorithm(event.target.value)}>{algorithms.map(item => <option key={item.code} value={item.code}>{item.name}</option>)}</select></Field></div>
          <p className="help">{selectedAlgorithm?.description}</p>
          <div className="form-row"><Field label="Iteration limit"><input type="number" min="1" value={runConfig.iterationLimit} onChange={event => setRunConfig({ ...runConfig, iterationLimit: event.target.value })} /></Field><Field label="Time limit (giây, 0 = không giới hạn)"><input type="number" min="0" value={runConfig.timeLimitSeconds} onChange={event => setRunConfig({ ...runConfig, timeLimitSeconds: event.target.value })} /></Field></div>
          <div className="form-row"><Field label="Capacity override (để trống = mặc định)"><input type="number" min="0" step="0.01" value={runConfig.capacityOverride} onChange={event => setRunConfig({ ...runConfig, capacityOverride: event.target.value })} /></Field><Field label="Random seed"><div className="input-action"><input type="number" value={runConfig.seed} onChange={event => setRunConfig({ ...runConfig, seed: event.target.value })} /><button type="button" className="icon-btn" onClick={() => setRunConfig({ ...runConfig, seed: Math.floor(Math.random() * 1e9) })}><Dices /></button></div></Field></div>
          {!!selectedAlgorithm?.parameters?.length && <><h4 className="subheading">Tham số {selectedAlgorithm.code}</h4><div className="form-row dynamic-params">{selectedAlgorithm.parameters.map(parameter => <Field key={parameter.code} label={parameter.name || parameter.code} hint={parameter.description}><input type={parameter.dataType === 'TEXT' ? 'text' : 'number'} min={parameter.minValue ?? undefined} max={parameter.maxValue ?? undefined} step={parameter.dataType === 'REAL' ? '0.01' : '1'} value={runConfig.parameters[parameter.code] ?? ''} onChange={event => setRunConfig({ ...runConfig, parameters: { ...runConfig.parameters, [parameter.code]: event.target.value } })} /></Field>)}</div></>}
          <ModalActions busy={busy} onCancel={() => setShowRun(false)} submit="Chạy và lưu" />
        </form>
      </Modal>}

      {showCreate && <Modal title="Sinh dataset mới" icon={<Sparkles />} onClose={() => setShowCreate(false)} wide>
        <form onSubmit={createDataset}><ModalError text={modalError} /><div className="form-row"><Field label="Loại bài toán"><select value={datasetForm.datasetType} onChange={event => setDatasetForm({ ...datasetForm, datasetType: event.target.value })}><option>CVRP</option><option>VRPTW</option><option>TDVRPTW</option></select></Field><Field label="Khu vực"><select value={datasetForm.preset} onChange={event => setDatasetForm({ ...datasetForm, preset: event.target.value })}>{Object.entries(CITY_PRESETS).map(([key, city]) => <option value={key} key={key}>{city.name}</option>)}</select></Field></div>
          <Field label="Tên dataset"><input value={datasetForm.name} placeholder="Tự sinh nếu để trống" onChange={event => setDatasetForm({ ...datasetForm, name: event.target.value })} /></Field>
          <div className="form-row">{[['customerCount', 'Số khách', 1], ['vehicleCount', 'Số xe', 1], ['vehicleCapacity', 'Tải xe (kg)', 0.01], ['radiusKm', 'Bán kính (km)', 0.1], ['minDemand', 'Demand nhỏ nhất', 0.01], ['maxDemand', 'Demand lớn nhất', 0.01]].map(([key, label, step]) => <Field key={key} label={label}><input type="number" min="1" step={step} value={datasetForm[key]} onChange={event => setDatasetForm({ ...datasetForm, [key]: Number(event.target.value) })} /></Field>)}</div>
          <div className="info-box">Ước tính nhu cầu: <strong>{Math.round(datasetForm.customerCount * (datasetForm.minDemand + datasetForm.maxDemand) / 2)} kg</strong> · sức chở: <strong>{datasetForm.vehicleCount * datasetForm.vehicleCapacity} kg</strong>{datasetForm.datasetType === 'TDVRPTW' && ' · tự sinh 5 khung giao thông cho mỗi cung.'}</div>
          <ModalActions busy={busy} onCancel={() => setShowCreate(false)} submit="Tạo dataset" />
        </form>
      </Modal>}

      {showDatasets && <Modal title="Quản lý dataset" icon={<Database />} onClose={() => setShowDatasets(false)} wide><div className="table-scroll"><table><thead><tr><th>Mã</th><th>Tên</th><th>Loại</th><th>Khách</th><th>Xe</th><th>Traffic</th><th></th></tr></thead><tbody>{datasets.map(ds => <tr key={ds.DATASET_ID}><td>{ds.DATASET_CODE}</td><td>{ds.DATASET_NAME}</td><td><span className="pill">{ds.DATASET_TYPE}</span></td><td>{ds.CUSTOMER_COUNT}</td><td>{ds.VEHICLE_COUNT}</td><td>{ds.TIME_DEPENDENT ? 'Có' : 'Không'}</td><td><button className="icon-btn danger" onClick={() => deleteDataset(ds.DATASET_ID)}><Trash2 /></button></td></tr>)}</tbody></table></div></Modal>}

      {compareData && <Modal title="So sánh experiment" icon={<BarChart3 />} onClose={() => setCompareData(null)} wide><div className="table-scroll"><table><thead><tr><th>Experiment</th><th>Thuật toán</th><th>Dataset</th><th>Xe</th><th>Distance</th><th>Travel</th><th>Waiting</th><th>CPU</th><th>Valid</th></tr></thead><tbody>{compareData.map(row => <tr key={row.EXPERIMENT_ID}><td>{row.EXPERIMENT_CODE}</td><td>{row.ALGORITHM_CODE}</td><td>{row.DATASET_CODE}</td><td>{row.VEHICLE_USED}</td><td>{Number(row.TOTAL_DISTANCE).toFixed(3)}</td><td>{formatClock(row.TOTAL_TRAVEL_TIME)}</td><td>{formatClock(row.TOTAL_WAITING_TIME)}</td><td>{row.EXECUTION_TIME_MS} ms</td><td>{row.IS_FEASIBLE ? 'Đạt' : 'Lỗi'}</td></tr>)}</tbody></table></div></Modal>}
    </div>
  );
}

function Modal({ title, icon, onClose, children, wide }) {
  return <div className="modal-overlay" onMouseDown={event => event.target === event.currentTarget && onClose()}><div className={`modal-content glass-panel ${wide ? 'modal-wide' : ''}`}><div className="modal-header"><h3>{icon}{title}</h3><button className="icon-btn" onClick={onClose}><X /></button></div><div className="modal-body">{children}</div></div></div>;
}

function Field({ label, hint, children }) { return <label className="form-group"><span>{label}</span>{children}{hint && <small>{hint}</small>}</label>; }
function ModalError({ text }) { return text ? <div className="error-banner"><AlertTriangle />{text}</div> : null; }
function ModalActions({ busy, onCancel, submit }) { return <div className="modal-footer"><button type="button" className="btn subtle" onClick={onCancel}>Hủy</button><button className="btn primary" disabled={busy} type="submit"><Play size={15} />{busy ? 'Đang xử lý…' : submit}</button></div>; }

export default App;
