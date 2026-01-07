package cn.flying.test.builders;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 extension that resets all test builder ID counters before each test.
 * This ensures test isolation and prevents ID collisions between tests.
 *
 * <p>Usage:</p>
 * <pre>
 * {@code
 * @ExtendWith(BuilderResetExtension.class)
 * class MyTest {
 *     @Test
 *     void testSomething() {
 *         Ticket ticket = TicketTestBuilder.aTicket(); // ID will be 1
 *     }
 * }
 * }
 * </pre>
 */
public class BuilderResetExtension implements BeforeEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        resetAllBuilders();
    }

    /**
     * Resets all builder ID counters. Can be called manually if needed.
     */
    public static void resetAllBuilders() {
        FileShareTestBuilder.resetIdCounter();
        FriendFileShareTestBuilder.resetIdCounter();
        TicketTestBuilder.resetIdCounter();
        FileTestBuilder.resetIdCounter();
        AccountTestBuilder.resetIdCounter();
        FriendRequestTestBuilder.resetIdCounter();
        FileUploadStateTestBuilder.resetClientIdCounter();
    }
}
