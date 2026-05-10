Vòng đời TOÀN HỆ THỐNG (Application Lifecycle)

Application Start
│
├── Spring Boot khởi động
│
├── IoC Container được tạo
│
├── Singleton Beans được tạo
│   │
│   ├── JwtService
│   ├── JwtAuthFilter
│   ├── UserDetailsService
│   ├── AuthenticationProvider
│   ├── Controllers
│   └── SecurityFilterChain
│
├── SecurityFilterChain build danh sách filters
│   │
│   ├── CorsFilter
│   ├── CsrfFilter
│   ├── JwtAuthFilter
│   ├── UsernamePasswordAuthenticationFilter
│   ├── AuthorizationFilter
│   └── ExceptionTranslationFilter
│
├── Application READY
│
├── Request A xử lý
├── Request B xử lý
├── Request C xử lý
│
└── Application Shutdown

==================================

Vòng đời của HTTP Login Request

HTTP Request đến
│
├── Tomcat nhận request
│
├── DelegatingFilterProxy
│
├── SecurityFilterChain
│
├── CorsFilter
│
├── CsrfFilter
│
├── UsernamePasswordAuthenticationFilter
│   │
│   ├── đọc username/password
│   ├── tạo Authentication chưa xác thực
│   │
│   └── gọi AuthenticationManager
│
├── AuthenticationManager
│   │
│   └── chọn AuthenticationProvider phù hợp
│
├── DaoAuthenticationProvider
│   │
│   ├── gọi UserDetailsService
│   │   └── load user từ DB
│   │
│   ├── gọi PasswordEncoder
│   │   └── BCrypt check password
│   │
│   ├── nếu đúng:
│   │     tạo Authentication đã xác thực
│   │
│   └── return Authentication
│
├── SecurityContextHolder
│   │
│   └── set Authentication
│
├── Login Success Handler
│   │
│   └── generate JWT
│
├── Response trả JWT về client
│
├── SecurityContextHolder.clearContext()
│
└── HTTP Request kết thúc

=====================================

Vòng đời của 1 HTTP Request có JWT

HTTP Request đến
│
├── Tomcat nhận request
│
├── DelegatingFilterProxy
│
├── SecurityFilterChain bắt đầu xử lý
│
├── CorsFilter
│
├── CsrfFilter
│
├── JwtAuthFilter
│   │
│   ├── lấy JWT từ header
│   ├── validate JWT
│   ├── load UserDetails
│   ├── tạo Authentication
│   └── set vào SecurityContext
│
├── AuthorizationFilter
│   │
│   ├── check authenticated()
│   └── check roles/permissions
│
├── Controller
│   │
│   └── business logic
│
├── Response trả về client
│
├── SecurityContextHolder.clearContext()
│
└── HTTP Request kết thúc



Quan hệ giữa 2 vòng đời

Application Lifecycle
│
├── SecurityFilterChain sống toàn app
├── JwtAuthFilter sống toàn app
└── JwtService sống toàn app

HTTP Request Lifecycle
│
├── SecurityContext được tạo
├── Authentication được set
└── SecurityContext bị clear


Sơ đồ tổng hợp lớn

┌─────────────────────────────────────┐
│         APPLICATION LIFECYCLE       │
├─────────────────────────────────────┤
│                                     │
│  Singleton Beans                    │
│  ├── SecurityFilterChain            │
│  ├── JwtAuthFilter                  │
│  ├── JwtService                     │
│  └── Controllers                    │
│                                     │
│  Sống toàn ứng dụng                 │
│                                     │
└─────────────────────────────────────┘


                ↓ xử lý request


┌─────────────────────────────────────┐
│        HTTP REQUEST LIFECYCLE       │
├─────────────────────────────────────┤
│                                     │
│  Request tới                        │
│      ↓                              │
│  SecurityFilterChain                │
│      ↓                              │
│  JwtAuthFilter                      │
│      ↓                              │
│  SecurityContext                    │
│      ↓                              │
│  AuthorizationFilter                │
│      ↓                              │
│  Controller                         │
│      ↓                              │
│  Response                           │
│      ↓                              │
│  clear SecurityContext              │
│                                     │
└─────────────────────────────────────┘