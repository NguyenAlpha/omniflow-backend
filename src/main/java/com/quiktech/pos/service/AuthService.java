package com.quiktech.pos.service;

import com.quiktech.pos.dto.request.auth.LoginRequest;
import com.quiktech.pos.dto.request.auth.RegisterRequest;
import com.quiktech.pos.dto.response.auth.AuthResponse;
import com.quiktech.pos.dto.response.common.ErrorCode;
import com.quiktech.pos.entity.User;
import com.quiktech.pos.exception.InvalidTokenException;
import com.quiktech.pos.repository.UserRepository;
import com.quiktech.pos.security.LoginAttemptLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Xác thực người dùng: đăng ký, đăng nhập, làm mới token, đăng xuất.
 *
 * <p>Có 2 loại token:
 * <ul>
 *   <li><b>Access token</b> (JWT, sống ngắn — {@code jwt.expiration}, mặc định 1 giờ): client gửi kèm mọi request.</li>
 *   <li><b>Refresh token</b> (chuỗi ngẫu nhiên lưu DB, sống {@code jwt.refresh-token-expiration-days} ngày): chỉ dùng để xin access
 *       token mới khi access token hết hạn — xem {@link RefreshTokenService}.</li>
 * </ul>
 * Phần dựng response (memberships, JWT) nằm ở {@link AuthResponseAssembler}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final RefreshTokenService refreshTokenService;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final AuthResponseAssembler authResponseAssembler;

    /**
     * Tạo tài khoản mới và đăng nhập luôn (trả token ngay, không cần gọi login).
     *
     * <p>User mới chưa thuộc business nào nên {@code memberships} rỗng — client gọi tiếp
     * {@code POST /api/businesses/default} để tạo business/store/kho mặc định.
     *
     * @param request username, email, mật khẩu (chưa băm), họ tên, số điện thoại
     * @return access token, refresh token, thông tin user và memberships (rỗng)
     * @throws com.quiktech.pos.exception.BusinessRuleException username hoặc email đã được tài khoản khác dùng
     *         (→ 400 USERNAME_TAKEN / EMAIL_TAKEN)
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        User user = User.builder()
                .username(request.username())
                .email(request.email())
                // Băm mật khẩu bằng BCrypt (bean PasswordEncoder) — DB chỉ lưu bản băm,
                // không bao giờ lưu mật khẩu gốc
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName())
                .phone(request.phone())
                .build();

        User saved;
        try {
            // Không kiểm tra trùng username/email trước khi lưu: để DB tự chặn bằng unique index
            // (uq_users_username_active / uq_users_email_active). Nếu kiểm tra trước, 2 người
            // đăng ký cùng lúc vẫn có thể cùng lọt qua bước kiểm tra.
            // Vi phạm unique index → Spring ném DataIntegrityViolationException ngay khi save()
            // (User dùng ID tự tăng IDENTITY nên INSERT chạy ngay, không đợi tới lúc commit).
            // try chỉ bọc save(): lỗi constraint ở bước khác (VD tạo refresh token) không bị
            // báo nhầm thành "trùng username/email".
            saved = userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            log.warn("Register failed: duplicate username or email: {}", request.username());
            // Theo tên unique index → 400 USERNAME_TAKEN / EMAIL_TAKEN (xem UserUniqueViolations)
            throw UserUniqueViolations.toException(e);
        }
        // Không ghi email vào log (dữ liệu cá nhân) — userId đủ để tra cứu
        log.info("User registered: userId={}, username={}", saved.getId(), saved.getUsername());
        return buildAuthResponse(saved);
    }

    /**
     * Đăng nhập bằng username hoặc email.
     *
     * <p>Thứ tự: kiểm tra còn lượt đăng nhập → kiểm tra mật khẩu → tạo refresh token mới
     * → dựng response. Mỗi lần sai mật khẩu trừ 1 lượt của tài khoản (xem {@link LoginAttemptLimiter}).
     *
     * @param request username hoặc email + mật khẩu
     * @return access token, refresh token mới, thông tin user và memberships
     * @throws com.quiktech.pos.exception.RateLimitExceededException hết lượt đăng nhập sai (→ 429)
     * @throws BadCredentialsException sai username/email hoặc mật khẩu (→ 401)
     * @throws org.springframework.security.authentication.DisabledException tài khoản bị khóa (→ 401)
     */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        // Tra user trước để giới hạn đăng nhập sai theo tài khoản (username/email chung bucket)
        // Truyền cùng 1 giá trị cho cả 2 tham số: query là "username = ? OR email = ?"
        Optional<User> found = userRepository.findByUsernameOrEmail(request.usernameOrEmail(), request.usernameOrEmail());
        // accountKey = khóa đếm số lần sai trên Redis: userId nếu tài khoản tồn tại, ngược lại
        // là SHA-256 của chuỗi đã nhập (vẫn chặn được việc dò thử tài khoản không có thật)
        String accountKey = LoginAttemptLimiter.accountKey(found.map(User::getId).orElse(null), request.usernameOrEmail());
        // Đã hết lượt (rate-limit.login-account.*, mặc định 5 lần sai / 15 phút) → ném RateLimitExceededException → 429,
        // dừng luôn ở đây, kể cả khi lần này nhập đúng mật khẩu
        loginAttemptLimiter.assertAllowed(accountKey);

        try {
            // Giao việc kiểm tra mật khẩu cho Spring Security. Bên trong authenticate():
            //   1. Gọi UserDetailsService (ApplicationConfig) tìm user theo username/email
            //   2. So mật khẩu nhập vào với passwordHash bằng BCrypt
            //   3. Kiểm tra user.isEnabled() (active và chưa bị xóa)
            // Sai/không tìm thấy user → BadCredentialsException; tài khoản bị khóa → DisabledException.
            // UsernamePasswordAuthenticationToken ở đây chỉ là "gói" chứa cặp tên + mật khẩu
            // gửi cho Spring Security, không phải JWT hay token trả về client.
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.usernameOrEmail(), request.password())
            );
        } catch (BadCredentialsException wrongPassword) {
            // Chỉ sai mật khẩu mới bị tính; tài khoản bị khóa (DisabledException) không trừ lượt
            loginAttemptLimiter.recordFailure(accountKey);
            // Ném lại để GlobalExceptionHandler trả 401 INVALID_CREDENTIALS
            throw wrongPassword;
        }

        // authenticate() ở trên đã thành công nên user chắc chắn tồn tại — nếu vẫn
        // không tìm thấy (race hiếm: user bị xóa giữa 2 câu query) thì fail với message
        // rõ ràng thay vì NoSuchElementException không có context
        User user = found
                .orElseThrow(() -> new IllegalStateException(
                        "User not found after successful authentication: " + request.usernameOrEmail()));

        log.info("User logged in: username={}", user.getUsername());
        return buildAuthResponse(user);
    }

    /**
     * Đổi refresh token lấy access token mới khi access token hết hạn (không cần nhập lại mật khẩu).
     *
     * <p>Refresh token cũ bị thu hồi, response chứa refresh token mới (token rotation).
     * Memberships được tính lại nên đây cũng là cách cập nhật quyền sau khi quyền thay đổi.
     *
     * <p>noRollbackFor bắt buộc (xem Javadoc RefreshTokenService.rotate): transaction này
     * bao cả rotate() (propagation REQUIRED) — nếu InvalidTokenException gây rollback
     * ở tầng này thì các UPDATE revoke trong rotate()/nhánh user-disabled đều bị hủy.
     *
     * @param refreshToken refresh token client đang giữ
     * @return access token mới, refresh token mới, thông tin user và memberships
     * @throws InvalidTokenException token không tồn tại / đã dùng / hết hạn, hoặc tài khoản
     *                               đã bị khóa hay xóa (→ 401)
     */
    @Transactional(noRollbackFor = InvalidTokenException.class)
    public AuthResponse refresh(String refreshToken) {
        // rotate() = "đổi token cũ lấy token mới" → trả về RotateResult(newToken, userId);
        // token sai/đã dùng/hết hạn → InvalidTokenException → 401 (chi tiết: xem RefreshTokenService.rotate)
        RefreshTokenService.RotateResult result = refreshTokenService.rotate(refreshToken);
        // User bị xóa mềm sẽ không tìm thấy do @SQLRestriction("deleted_at IS NULL");
        // user bị khóa thì isEnabled() = false. Cả 2 trường hợp: thu hồi toàn bộ refresh
        // token để chặn user offboarded tự gia hạn phiên vô thời hạn (CRITICAL #2).
        User user = userRepository.findById(result.userId()).orElse(null);
        if (user == null || !user.isEnabled()) {
            refreshTokenService.revokeAll(result.userId());
            log.warn("Refresh blocked for disabled/deleted userId={} — all tokens revoked", result.userId());
            throw new InvalidTokenException(ErrorCode.REFRESH_TOKEN_INVALID, "User account is disabled");
        }
        // Dựng response mới (JWT mới + memberships tính lại theo quyền hiện tại) kèm refresh token
        // vừa tạo — client phải lưu đè refresh token cũ bằng token này
        return authResponseAssembler.assemble(user, result.newToken());
    }

    /**
     * Đăng xuất khỏi tất cả thiết bị bằng cách thu hồi mọi refresh token của user.
     *
     * @param userId ID của user đang đăng nhập (lấy từ JWT ở controller)
     */
    @Transactional
    public void logout(Long userId) {
        // Thu hồi mọi refresh token của user = đăng xuất trên tất cả thiết bị.
        // Access token (JWT) không thu hồi được — vẫn dùng được tới khi tự hết hạn.
        refreshTokenService.revokeAll(userId);
        log.info("User logged out: userId={}", userId);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Dùng cho register/login: tạo refresh token MỚI (lưu DB, hạn theo {@code jwt.refresh-token-expiration-days}) rồi dựng response.
     * refresh() không đi qua đây vì rotate() đã tạo sẵn token mới.
     *
     * @param user user vừa đăng ký hoặc vừa xác thực thành công
     * @return response đầy đủ gửi về client
     */
    private AuthResponse buildAuthResponse(User user) {
        String rtValue = refreshTokenService.create(user.getId());
        return authResponseAssembler.assemble(user, rtValue);
    }
}
