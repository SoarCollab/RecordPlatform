package cn.flying.service.impl;

import cn.flying.common.constant.UserRole;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import cn.flying.common.util.FlowUtils;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.vo.auth.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("AccountServiceImpl Tests")
@ExtendWith(MockitoExtension.class)
class AccountServiceImplTest {

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private AmqpTemplate rabbitTemplate;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private FlowUtils flow;

    @Spy
    @InjectMocks
    private AccountServiceImpl accountService;

    private static final Long USER_ID = 1001L;
    private static final String USERNAME = "testuser";
    private static final String EMAIL = "test@example.com";
    private static final String PASSWORD = "password123";
    private static final String ENCODED_PASSWORD = "$2a$10$encodedpassword";
    private static final String VERIFY_CODE = "123456";
    private static final String IP_ADDRESS = "192.168.1.1";
    private static final int VERIFY_LIMIT = 60;
    private static final Long REGISTRATION_TENANT_ID = 1L;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(accountService, "VERIFY_LIMIT", VERIFY_LIMIT);
        ReflectionTestUtils.setField(accountService, "registrationTenantId", REGISTRATION_TENANT_ID);
        ReflectionTestUtils.setField(accountService, "baseMapper", accountMapper);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        getAddressLocks().clear();
    }

    @Nested
    @DisplayName("loadUserByUsername")
    class LoadUserByUsername {

        @Test
        @DisplayName("should return UserDetails when user found by username")
        void userFound_returnsUserDetails() {
            Account account = createAccount();
            doReturn(account).when(accountService).findAccountByNameOrEmail(USERNAME);

            UserDetails result = accountService.loadUserByUsername(USERNAME);

            assertEquals(USERNAME, result.getUsername());
            assertEquals(ENCODED_PASSWORD, result.getPassword());
            assertTrue(result.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_" + UserRole.ROLE_DEFAULT.getRole())));
        }

        @Test
        @DisplayName("should return UserDetails when user found by email")
        void userFoundByEmail_returnsUserDetails() {
            Account account = createAccount();
            doReturn(account).when(accountService).findAccountByNameOrEmail(EMAIL);

            UserDetails result = accountService.loadUserByUsername(EMAIL);

            assertNotNull(result);
        }

        @Test
        @DisplayName("should throw UsernameNotFoundException when user not found")
        void userNotFound_throws() {
            doReturn(null).when(accountService).findAccountByNameOrEmail(anyString());

            assertThrows(UsernameNotFoundException.class,
                    () -> accountService.loadUserByUsername("nonexistent"));
        }
    }

    @Nested
    @DisplayName("registerEmailVerifyCode")
    class RegisterEmailVerifyCode {

        @Test
        @DisplayName("should send verification code when rate limit not exceeded")
        void rateLimitOk_sendsCode() {
            when(flow.limitOnceCheck(Const.VERIFY_EMAIL_LIMIT + IP_ADDRESS, VERIFY_LIMIT)).thenReturn(true);

            String result = accountService.registerEmailVerifyCode("register", EMAIL, IP_ADDRESS);

            assertNull(result);
            verify(rabbitTemplate).convertAndSend(eq(Const.MQ_MAIL), any(Map.class));
            verify(valueOperations).set(eq(Const.VERIFY_EMAIL_DATA + EMAIL), anyString(), eq(3L), eq(TimeUnit.MINUTES));
        }

        @Test
        @DisplayName("should return error when rate limit exceeded")
        void rateLimitExceeded_returnsError() {
            when(flow.limitOnceCheck(Const.VERIFY_EMAIL_LIMIT + IP_ADDRESS, VERIFY_LIMIT)).thenReturn(false);

            String result = accountService.registerEmailVerifyCode("register", EMAIL, IP_ADDRESS);

            assertEquals("请求频繁，请稍后再试", result);
            verify(rabbitTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }

        @Test
        @DisplayName("should generate 6-digit code")
        void generatesValidCode() {
            when(flow.limitOnceCheck(anyString(), anyInt())).thenReturn(true);
            ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);

            accountService.registerEmailVerifyCode("register", EMAIL, IP_ADDRESS);

            verify(valueOperations).set(anyString(), codeCaptor.capture(), anyLong(), any());
            String code = codeCaptor.getValue();
            assertTrue(code.matches("\\d{6}"), "Code should be 6 digits");
            int codeInt = Integer.parseInt(code);
            assertTrue(codeInt >= 100000 && codeInt <= 999999);
        }

        @Test
        @DisplayName("should store code with correct key format")
        void storesCodeWithCorrectKey() {
            when(flow.limitOnceCheck(anyString(), anyInt())).thenReturn(true);
            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);

            accountService.registerEmailVerifyCode("register", EMAIL, IP_ADDRESS);

            verify(valueOperations).set(keyCaptor.capture(), anyString(), anyLong(), any());
            assertEquals(Const.VERIFY_EMAIL_DATA + EMAIL, keyCaptor.getValue());
        }

        @Test
        @DisplayName("should send email data to correct queue")
        void sendsToCorrectQueue() {
            when(flow.limitOnceCheck(anyString(), anyInt())).thenReturn(true);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);

            accountService.registerEmailVerifyCode("register", EMAIL, IP_ADDRESS);

            verify(rabbitTemplate).convertAndSend(eq(Const.MQ_MAIL), dataCaptor.capture());
            Map<String, Object> data = dataCaptor.getValue();
            assertEquals("register", data.get("type"));
            assertEquals(EMAIL, data.get("email"));
            assertNotNull(data.get("code"));
        }

        /**
         * 验证地址锁在请求结束后被清理，避免攻击者用大量源地址撑大静态 map。
         */
        @Test
        @DisplayName("should remove address lock after verification request")
        void shouldRemoveAddressLockAfterVerificationRequest() {
            when(flow.limitOnceCheck(anyString(), anyInt())).thenReturn(true);

            accountService.registerEmailVerifyCode("register", EMAIL, IP_ADDRESS);

            assertTrue(getAddressLocks().isEmpty());
        }
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, ?> getAddressLocks() {
        return (ConcurrentHashMap<String, ?>) ReflectionTestUtils.getField(AccountServiceImpl.class, "ADDRESS_LOCKS");
    }

    @Nested
    @DisplayName("registerEmailAccount - validation only")
    class RegisterEmailAccountValidation {

        @Test
        @DisplayName("should return error when verification code not obtained")
        void noVerificationCode_returnsError() {
            EmailRegisterVO vo = createRegisterVO();
            when(valueOperations.get(Const.VERIFY_EMAIL_DATA + EMAIL)).thenReturn(null);

            String result = accountService.registerEmailAccount(vo);

            assertEquals("请先获取验证码", result);
        }

        @Test
        @DisplayName("should return error when verification code is wrong")
        void wrongVerificationCode_returnsError() {
            EmailRegisterVO vo = createRegisterVO();
            when(valueOperations.get(Const.VERIFY_EMAIL_DATA + EMAIL)).thenReturn("654321");

            String result = accountService.registerEmailAccount(vo);

            assertEquals("验证码错误，请重新输入", result);
        }

        @Test
        @DisplayName("should return error when email already registered")
        void emailExists_returnsError() {
            EmailRegisterVO vo = createRegisterVO();
            when(valueOperations.get(Const.VERIFY_EMAIL_DATA + EMAIL)).thenReturn(VERIFY_CODE);
            when(accountMapper.exists(any())).thenReturn(true);

            String result = accountService.registerEmailAccount(vo);

            assertEquals("该邮件地址已被注册", result);
        }

        @Test
        @DisplayName("should return error when username already taken")
        void usernameTaken_returnsError() {
            EmailRegisterVO vo = createRegisterVO();
            when(valueOperations.get(Const.VERIFY_EMAIL_DATA + EMAIL)).thenReturn(VERIFY_CODE);
            when(accountMapper.exists(any())).thenReturn(false, true);

            String result = accountService.registerEmailAccount(vo);

            assertEquals("该用户名已被他人使用，请重新更换", result);
        }

        /**
         * 验证公开注册只写入服务端配置的租户，不信任未认证请求预先写入的 TenantContext。
         */
        @Test
        @DisplayName("should register account under configured tenant")
        void validRegistration_usesConfiguredTenant() {
            TenantContext.setTenantId(99L);
            EmailRegisterVO vo = createRegisterVO();
            when(valueOperations.get(Const.VERIFY_EMAIL_DATA + EMAIL)).thenReturn(VERIFY_CODE);
            when(accountMapper.exists(any())).thenAnswer(invocation -> {
                assertEquals(REGISTRATION_TENANT_ID, TenantContext.getTenantId());
                return false;
            });
            when(passwordEncoder.encode(PASSWORD)).thenReturn(ENCODED_PASSWORD);
            when(accountMapper.insert(any(Account.class))).thenAnswer(invocation -> {
                assertEquals(REGISTRATION_TENANT_ID, TenantContext.getTenantId());
                return 1;
            });
            ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);

            String result;
            try (MockedStatic<IdUtils> idUtilsMock = mockStatic(IdUtils.class)) {
                idUtilsMock.when(IdUtils::nextUserId).thenReturn(USER_ID);
                result = accountService.registerEmailAccount(vo);
            }

            assertNull(result);
            verify(accountMapper).insert(accountCaptor.capture());
            Account saved = accountCaptor.getValue();
            assertEquals(REGISTRATION_TENANT_ID, saved.getTenantId());
            assertEquals(99L, TenantContext.getTenantId());
            verify(stringRedisTemplate).delete(Const.VERIFY_EMAIL_DATA + EMAIL);
        }
    }

    @Nested
    @DisplayName("updateUserInfo")
    class UpdateUserInfo {

        @Test
        @DisplayName("should update avatar successfully")
        void updateAvatar_success() {
            Account account = createAccount();
            UpdateUserVO vo = new UpdateUserVO();
            vo.setAvatar("/avatars/new.png");
            
            doReturn(account).when(accountService).findAccountById(USER_ID);
            when(accountMapper.updateById(any(Account.class))).thenReturn(1);

            Account result = accountService.updateUserInfo(USER_ID, vo);

            assertEquals("/avatars/new.png", result.getAvatar());
            verify(accountMapper).updateById(any(Account.class));
        }

        @Test
        @DisplayName("should update nickname successfully")
        void updateNickname_success() {
            Account account = createAccount();
            UpdateUserVO vo = new UpdateUserVO();
            vo.setNickname("New Nickname");
            
            doReturn(account).when(accountService).findAccountById(USER_ID);
            when(accountMapper.updateById(any(Account.class))).thenReturn(1);

            Account result = accountService.updateUserInfo(USER_ID, vo);

            assertEquals("New Nickname", result.getNickname());
        }

        @Test
        @DisplayName("should update both avatar and nickname")
        void updateBothFields_success() {
            Account account = createAccount();
            UpdateUserVO vo = new UpdateUserVO();
            vo.setAvatar("/avatars/new.png");
            vo.setNickname("New Nickname");
            
            doReturn(account).when(accountService).findAccountById(USER_ID);
            when(accountMapper.updateById(any(Account.class))).thenReturn(1);

            Account result = accountService.updateUserInfo(USER_ID, vo);

            assertEquals("/avatars/new.png", result.getAvatar());
            assertEquals("New Nickname", result.getNickname());
        }

        @Test
        @DisplayName("should not update when no fields provided")
        void noFields_noUpdate() {
            Account account = createAccount();
            UpdateUserVO vo = new UpdateUserVO();
            
            doReturn(account).when(accountService).findAccountById(USER_ID);

            Account result = accountService.updateUserInfo(USER_ID, vo);

            assertNotNull(result);
            verify(accountMapper, never()).updateById(any(Account.class));
        }

        @Test
        @DisplayName("should throw when user not found")
        void userNotFound_throws() {
            UpdateUserVO vo = new UpdateUserVO();
            vo.setAvatar("/avatars/new.png");
            
            doReturn(null).when(accountService).findAccountById(USER_ID);

            assertThrows(RuntimeException.class,
                    () -> accountService.updateUserInfo(USER_ID, vo));
        }
    }

    private Account createAccount() {
        Account account = new Account(USER_ID, USERNAME, ENCODED_PASSWORD, EMAIL,
                UserRole.ROLE_DEFAULT.getRole(), "/avatars/default.png", "Test User");
        account.setTenantId(1L);
        return account;
    }

    private EmailRegisterVO createRegisterVO() {
        EmailRegisterVO vo = new EmailRegisterVO();
        vo.setEmail(EMAIL);
        vo.setCode(VERIFY_CODE);
        vo.setUsername(USERNAME);
        vo.setPassword(PASSWORD);
        vo.setNickname("Test Nickname");
        return vo;
    }
}
