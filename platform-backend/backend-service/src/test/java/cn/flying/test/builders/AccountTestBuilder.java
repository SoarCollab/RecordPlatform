package cn.flying.test.builders;

import cn.flying.dao.dto.Account;

import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Test data builder for Account entity.
 * Provides fluent API for creating test fixtures.
 */
public class AccountTestBuilder {

    private static final AtomicLong idCounter = new AtomicLong(1L);

    public static Account anAccount() {
        long id = idCounter.getAndIncrement();
        Account account = new Account(
                id,
                "testuser_" + id,
                "hashed_password",
                "test" + id + "@example.com",
                "USER",
                "/avatars/default.png",
                "Test User " + id
        );
        account.setRegisterTime(new Date());
        account.setUpdateTime(new Date());
        return account;
    }

    public static Account anAccount(Consumer<Account> customizer) {
        Account account = anAccount();
        customizer.accept(account);
        return account;
    }

    public static Account anAccountWithId(Long id) {
        return anAccount(a -> a.setId(id));
    }

    public static Account anAccountWithUsername(String username) {
        return anAccount(a -> a.setUsername(username));
    }

    public static void resetIdCounter() {
        idCounter.set(1L);
    }
}
