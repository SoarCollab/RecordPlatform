package cn.flying.identity;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Spring Boot应用上下文测试
 * 由于涉及多个外部依赖（Nacos、Dubbo、Redis、MySQL等），
 * 在单元测试环境中暂时禁用此测试。
 * 所有的功能测试都在各个独立的Service测试类中进行。
 */
@SpringBootTest
@Disabled("由于外部依赖（Nacos、Dubbo等）在测试环境不可用，暂时禁用上下文加载测试")
class IdentityApplicationTests {

	@Test
	void contextLoads() {
		// 此测试用于验证Spring Boot应用上下文能否正常加载
		// 在生产环境中，所有外部依赖都会正常配置
	}

}
