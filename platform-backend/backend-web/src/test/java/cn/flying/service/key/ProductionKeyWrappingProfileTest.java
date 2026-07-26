package cn.flying.service.key;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionKeyWrappingProfileTest {

    /**
     * 验证生产 profile 清除通用配置中的 local provider、key id 和 JWT key 回退。
     */
    @Test
    void shouldRequireExplicitProductionProviderAndLocalKeyConfiguration() throws IOException {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("JWT_KEY", "jwt-key-must-not-be-used-for-file-envelopes");
        addLast(environment, "application.yml");
        addFirst(environment, "application-prod.yml");

        assertThat(environment.getProperty("file.key-envelope.active-provider")).isEmpty();
        assertThat(environment.getProperty("file.key-envelope.providers.local.key-id")).isEmpty();
        assertThat(environment.getProperty("file.key-envelope.providers.local.master-key")).isEmpty();
        assertThat(environment.getProperty("file.key-envelope.providers.vault-transit.allow-http"))
                .isEqualTo("false");
    }

    /**
     * 以低优先级加载通用 YAML 属性源。
     */
    private void addLast(MockEnvironment environment, String resource) throws IOException {
        for (PropertySource<?> source : load(resource)) {
            environment.getPropertySources().addLast(source);
        }
    }

    /**
     * 以高优先级加载 profile YAML 属性源。
     */
    private void addFirst(MockEnvironment environment, String resource) throws IOException {
        List<PropertySource<?>> sources = load(resource);
        for (int index = sources.size() - 1; index >= 0; index--) {
            environment.getPropertySources().addFirst(sources.get(index));
        }
    }

    /**
     * 读取一个 classpath YAML 资源中的全部文档。
     */
    private List<PropertySource<?>> load(String resource) throws IOException {
        return new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource));
    }
}
