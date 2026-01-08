package cn.flying.test.mapper;

import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.mapper.AccountMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AccountMapper Integration Tests")
class AccountMapperIT extends BaseMapperIT {

    @Autowired
    private AccountMapper accountMapper;

    private Account createTestAccount(Long userId, Long tenantId, String username) {
        Account account = new Account();
        account.setId(userId);
        account.setUsername(username);
        account.setPassword("hashed_password_" + UUID.randomUUID());
        account.setEmail(username + "@test.com");
        account.setRole("user");
        account.setAvatar("https://example.com/avatar.png");
        account.setNickname("Test " + username);
        account.setTenantId(tenantId);
        account.setRegisterTime(new Date());
        account.setUpdateTime(new Date());
        account.setDeleted(0);
        return account;
    }

    @Nested
    @DisplayName("Basic CRUD Operations")
    class BasicCrudTests {

        @Test
        @DisplayName("should insert and select account")
        void shouldInsertAndSelectAccount() {
            Long userId = IdUtils.nextEntityId();
            String username = "test_user_" + userId;
            Account account = createTestAccount(userId, TEST_TENANT_ID, username);
            
            int inserted = accountMapper.insert(account);
            assertThat(inserted).isEqualTo(1);

            Account found = accountMapper.selectById(userId);
            assertThat(found).isNotNull();
            assertThat(found.getUsername()).isEqualTo(username);
            assertThat(found.getEmail()).isEqualTo(username + "@test.com");
            assertThat(found.getRole()).isEqualTo("user");
        }

        @Test
        @DisplayName("should update account")
        void shouldUpdateAccount() {
            Long userId = IdUtils.nextEntityId();
            Account account = createTestAccount(userId, TEST_TENANT_ID, "update_test_" + userId);
            accountMapper.insert(account);

            account.setNickname("Updated Nickname");
            account.setAvatar("https://example.com/new-avatar.png");
            int updated = accountMapper.updateById(account);
            
            assertThat(updated).isEqualTo(1);
            
            Account found = accountMapper.selectById(userId);
            assertThat(found.getNickname()).isEqualTo("Updated Nickname");
            assertThat(found.getAvatar()).isEqualTo("https://example.com/new-avatar.png");
        }

        @Test
        @DisplayName("should apply logical delete")
        void shouldApplyLogicalDelete() {
            Long userId = IdUtils.nextEntityId();
            Account account = createTestAccount(userId, TEST_TENANT_ID, "delete_test_" + userId);
            accountMapper.insert(account);

            int deleted = accountMapper.deleteById(userId);
            assertThat(deleted).isEqualTo(1);

            Account found = accountMapper.selectById(userId);
            assertThat(found).isNull();
        }
    }

    @Nested
    @DisplayName("Query Operations")
    class QueryTests {

        @Test
        @DisplayName("should find account by username")
        void shouldFindAccountByUsername() {
            Long userId = IdUtils.nextEntityId();
            String username = "query_test_" + userId;
            Account account = createTestAccount(userId, TEST_TENANT_ID, username);
            accountMapper.insert(account);

            LambdaQueryWrapper<Account> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Account::getUsername, username);
            
            Account found = accountMapper.selectOne(wrapper);
            assertThat(found).isNotNull();
            assertThat(found.getId()).isEqualTo(userId);
        }

        @Test
        @DisplayName("should find account by email")
        void shouldFindAccountByEmail() {
            Long userId = IdUtils.nextEntityId();
            String username = "email_test_" + userId;
            Account account = createTestAccount(userId, TEST_TENANT_ID, username);
            accountMapper.insert(account);

            LambdaQueryWrapper<Account> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Account::getEmail, username + "@test.com");
            
            Account found = accountMapper.selectOne(wrapper);
            assertThat(found).isNotNull();
            assertThat(found.getUsername()).isEqualTo(username);
        }

        @Test
        @DisplayName("should list accounts by role")
        void shouldListAccountsByRole() {
            Long userId1 = IdUtils.nextEntityId();
            Long userId2 = IdUtils.nextEntityId();
            
            Account user1 = createTestAccount(userId1, TEST_TENANT_ID, "role_user_" + userId1);
            user1.setRole("admin");
            accountMapper.insert(user1);

            Account user2 = createTestAccount(userId2, TEST_TENANT_ID, "role_user_" + userId2);
            user2.setRole("admin");
            accountMapper.insert(user2);

            LambdaQueryWrapper<Account> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(Account::getRole, "admin");
            
            List<Account> admins = accountMapper.selectList(wrapper);
            assertThat(admins.size()).isGreaterThanOrEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Tenant Isolation Tests")
    class TenantIsolationTests {

        @Test
        @DisplayName("should isolate accounts by tenant")
        void shouldIsolateAccountsByTenant() {
            Long userId1 = IdUtils.nextEntityId();
            Long userId2 = IdUtils.nextEntityId();
            
            Account account1 = createTestAccount(userId1, TEST_TENANT_ID, "tenant1_user_" + userId1);
            accountMapper.insert(account1);

            runWithTenant(999L, () -> {
                Account account2 = createTestAccount(userId2, 999L, "tenant2_user_" + userId2);
                accountMapper.insert(account2);
            });

            LambdaQueryWrapper<Account> wrapper = new LambdaQueryWrapper<>();
            wrapper.likeRight(Account::getUsername, "tenant");
            
            List<Account> accounts = accountMapper.selectList(wrapper);
            assertThat(accounts).hasSize(1);
            assertThat(accounts.get(0).getTenantId()).isEqualTo(TEST_TENANT_ID);
        }
    }

    @Nested
    @DisplayName("Unique Constraint Tests")
    class UniqueConstraintTests {

        @Test
        @DisplayName("should enforce unique username within tenant")
        void shouldEnforceUniqueUsernameWithinTenant() {
            Long userId1 = IdUtils.nextEntityId();
            Long userId2 = IdUtils.nextEntityId();
            String sharedUsername = "unique_user_" + System.currentTimeMillis();
            
            Account account1 = createTestAccount(userId1, TEST_TENANT_ID, sharedUsername);
            int inserted1 = accountMapper.insert(account1);
            assertThat(inserted1).isEqualTo(1);

            Account account2 = createTestAccount(userId2, TEST_TENANT_ID, sharedUsername);
            
            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.dao.DuplicateKeyException.class,
                    () -> accountMapper.insert(account2)
            );
        }

        @Test
        @DisplayName("should enforce unique email within tenant")
        void shouldEnforceUniqueEmailWithinTenant() {
            Long userId1 = IdUtils.nextEntityId();
            Long userId2 = IdUtils.nextEntityId();
            String sharedEmail = "unique_" + System.currentTimeMillis() + "@test.com";
            
            Account account1 = createTestAccount(userId1, TEST_TENANT_ID, "user1_" + userId1);
            account1.setEmail(sharedEmail);
            accountMapper.insert(account1);

            Account account2 = createTestAccount(userId2, TEST_TENANT_ID, "user2_" + userId2);
            account2.setEmail(sharedEmail);
            
            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.dao.DuplicateKeyException.class,
                    () -> accountMapper.insert(account2)
            );
        }
    }
}
