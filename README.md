# VRP Algorithm Demo

Ứng dụng Java console tối giản để đọc dataset từ schema `vrp_research`, giải bằng **Nearest Neighbor Greedy CVRP**, kiểm tra nghiệm và lưu toàn bộ experiment/routes/stops về MySQL.

## Yêu cầu

- JDK 21
- Maven 3.9+
- MySQL 8 với file `vrp_research_mysql8_phpmyadmin_safe.sql` đã import

## Cấu hình MySQL

Cách an toàn nhất là đặt biến môi trường PowerShell (mật khẩu không bị commit):

```powershell
$env:VRP_DB_URL = 'jdbc:mysql://localhost:3306/vrp_research?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Ho_Chi_Minh'
$env:VRP_DB_USER = 'root'
$env:VRP_DB_PASSWORD = 'mat_khau_cua_ban'
$env:VRP_DATASET_ID = '1'
```

Hoặc copy `src/main/resources/application.properties` thành `application-local.properties` ở thư mục gốc rồi sửa giá trị. File local đã nằm trong `.gitignore`.

## Chạy

```powershell
mvn test
mvn exec:java
```

Có thể truyền `dataset_id` trực tiếp:

```powershell
mvn exec:java -Dexec.args="1"
```

Sau khi chạy, kiểm tra kết quả:

```sql
SELECT * FROM v_experiment_summary ORDER BY experiment_id DESC;
SELECT * FROM v_result_validation ORDER BY result_id DESC;
```

Phạm vi hiện tại là baseline CVRP. Solver chỉ dùng tải trọng để chọn khách; lịch đến/chờ/phục vụ vẫn được tính và lưu để chuẩn bị mở rộng sang VRPTW.
