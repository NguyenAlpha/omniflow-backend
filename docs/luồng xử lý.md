HTTP Request
↓
Spring SecurityFilterChain singleton toàn ứng dụng
↓
JwtAuthFilter chạy
↓
Lấy JWT
↓
JwtService đọc token
↓
Load user
↓
Tạo Authentication
↓
Set SecurityContext
↓
Controller



username/password
↓
DaoAuthenticationProvider
↓
UserDetailsService
↓
BCrypt check password



