# VRP Optimization Research

Ứng dụng nghiên cứu và trực quan hóa **CVRP, VRPTW và TDVRPTW**. Backend Spring Boot đọc bài toán từ MySQL, chạy Greedy/Tabu Search/Genetic Algorithm/Ant Colony Optimization, kiểm định lại nghiệm độc lập rồi lưu toàn bộ experiment, route, stop và log hội tụ. Frontend React cung cấp màn hình sinh dataset, chạy thuật toán, quản trị dữ liệu và phân tích kết quả.

## Chức năng đã có

- CVRP, VRPTW với capacity, time window, service/waiting time và loại xe yêu cầu.
- TDVRPTW dùng travel time theo thời điểm xe rời cung; dataset sinh tự động có 5 khung giao thông/ngày.
- Bốn solver có thể chọn từ FE: `BASELINE_SEQUENTIAL_INSERTION`, `TABU_ROUTE`, `GA_GIANT_TOUR_SPLIT`, `ACO_MACS_VRPTW`.
- Parameter động theo thuật toán, random seed tái hiện được, iteration/time limit và capacity override.
- Validator Java tính lại coverage, tải trọng, cấu trúc route, timeline, time window, loại xe và KPI.
- SQL views kiểm tra chéo coverage, capacity, structure, schedule, time window và metric tổng.
- FE: quản lý dataset, lịch sử experiment, so sánh KPI, bản đồ route, lịch đến/chờ/phục vụ/rời, log hội tụ và validation.

## Tái tạo database

Yêu cầu MySQL 8.0+. File [backend/schema.sql](backend/schema.sql) là bootstrap chính thức, không chứa dữ liệu experiment mẫu và có thể chạy lại trên schema v2:

```powershell
mysql -u root -p < backend/schema.sql
```

Script tự tạo database `vrp_research`, 18 bảng, 8 trigger, 9 view, metadata của 4 thuật toán và 16 tham số. File `backend/vrp_research (1).sql` chỉ là dump tham chiếu cũ; không dùng để triển khai mới vì chứa ID/dữ liệu cố định và `DEFINER` phụ thuộc máy nguồn.

Nếu máy đã có schema cũ, hãy backup rồi import vào một database sạch để tái tạo chắc chắn. `CREATE TABLE IF NOT EXISTS` cố ý không phá dữ liệu hiện hữu nên không thể tự thêm mọi cột/constraint còn thiếu của một schema cũ tùy biến.

### Ý nghĩa các bảng

| Bảng | Vai trò |
| --- | --- |
| `schema_version` | Ghi phiên bản schema đã áp dụng. |
| `algorithm` | Danh mục thuật toán, family, version và trạng thái sử dụng. |
| `algorithm_parameter` | Định nghĩa parameter, kiểu, mặc định và miền hợp lệ của từng thuật toán. |
| `dataset` | Metadata một bộ bài toán CVRP/VRPTW/TDVRPTW. |
| `location` | Tọa độ dùng chung cho depot và customer. |
| `depot` | Kho, giờ mở/đóng và location tương ứng. |
| `customer` | Khách hàng, service time, time window và priority. |
| `delivery_order` | Nhu cầu weight/volume, time window, loại xe bắt buộc và trạng thái đơn. |
| `vehicle` | Đội xe, capacity, loại xe, depot xuất phát và giờ hoạt động. |
| `driver` | Tài xế và xe được phân công (tùy chọn). |
| `location_distance` | Ma trận distance/base travel time có hướng giữa hai location. |
| `travel_time_profile` | Travel time hoặc speed multiplier theo từng khung giờ cho TDVRPTW. |
| `experiment` | Cấu hình và vòng đời một lần chạy solver. |
| `experiment_parameter_value` | Giá trị parameter thực tế của experiment. |
| `experiment_iteration_log` | KPI từng vòng lặp để phân tích hội tụ. |
| `experiment_result` | Kết quả tổng, objective, violation và feasibility. |
| `route_result` | KPI/timeline của từng tuyến xe trong kết quả. |
| `route_stop_result` | Thứ tự dừng, arrival/service/departure, tải và violation tại mỗi stop. |

Các trigger từ chối liên kết khác dataset/experiment và profile giao thông bị chồng lấn. Các view `v_result_*` và `v_route_*` phục vụ kiểm định SQL độc lập với cờ do Java lưu.

## Chạy backend

Yêu cầu JDK 21 và Maven 3.9+.

```powershell
$env:VRP_DB_URL = 'jdbc:mysql://localhost:3306/vrp_research?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Ho_Chi_Minh'
$env:VRP_DB_USER = 'root'
$env:VRP_DB_PASSWORD = 'mat_khau_cua_ban'

cd backend
mvn clean test
mvn spring-boot:run
```

Backend mặc định chạy ở `http://localhost:8088`.

API chính:

| Method | Endpoint | Chức năng |
| --- | --- | --- |
| GET | `/api/algorithms` | Metadata thuật toán và parameter cho form động. |
| GET/POST | `/api/datasets`, `/api/datasets/generate` | Liệt kê và sinh dataset. |
| GET/DELETE | `/api/datasets/{id}` | Xem chi tiết hoặc xóa cascade dataset. |
| GET | `/api/experiments` | Lịch sử experiment và KPI. |
| POST | `/api/experiments/run` | Chạy solver, validate và lưu kết quả. |
| GET/DELETE | `/api/experiments/{id}` | Xem hoặc xóa experiment. |
| GET | `/api/experiments/compare?ids=1,2` | So sánh nhiều experiment. |

## Chạy frontend

Yêu cầu Node.js 20+.

```powershell
cd frontend
npm install
npm test
npm run lint
npm run dev
```

Vite proxy `/api` sang backend `http://localhost:8088`. Khi deploy tách domain, đặt `VITE_API_BASE_URL` trước khi build.

## Kiểm thử

```powershell
cd backend
mvn clean test

cd ../frontend
npm test
npm run lint
npm run build
```

Backend test xác nhận cả 4 solver sinh nghiệm hợp lệ, travel time thay đổi theo profile và validator phát hiện KPI bị sửa. Frontend test phần tạo payload, metadata parameter và định dạng timeline; build/lint là bước kiểm tra bắt buộc trước khi chạy.

## Giới hạn còn lại

Đây là bộ heuristic phục vụ nghiên cứu/demo, chưa phải optimizer production. Mô hình hiện dùng một depot hoạt động cho mỗi lần giải, chưa có pickup-and-delivery, split delivery, nghỉ tài xế, road-network routing hoặc authentication/authorization. Chưa có benchmark chất lượng trên Solomon/Homberger/CVRPLIB và chưa có browser E2E tự động.
