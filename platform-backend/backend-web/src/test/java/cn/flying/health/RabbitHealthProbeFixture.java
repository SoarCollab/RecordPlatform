package cn.flying.health;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** Preserves mocked message publishing while executing health callbacks against the supplied broker. */
final class RabbitHealthProbeFixture {

    private RabbitHealthProbeFixture() {
    }

    /** Delegates callback execution only; other template operations remain unchanged. */
    static void enableHealthCallbacks(RabbitTemplate mockedTemplate, ConnectionFactory connectionFactory) {
        RabbitTemplate delegate = new RabbitTemplate(connectionFactory);
        doAnswer(invocation -> {
            ChannelCallback<Object> callback = invocation.getArgument(0);
            return delegate.execute(callback);
        }).when(mockedTemplate).execute(any());
    }
}
