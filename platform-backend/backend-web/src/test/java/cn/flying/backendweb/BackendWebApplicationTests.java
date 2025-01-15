package cn.flying.backendweb;

import com.alibaba.druid.pool.DruidDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;

@SpringBootTest
class BackendWebApplicationTests {

    @Autowired
    private DataSource dataSource;

    @Test
    void druid_Test() {
        System.out.println(dataSource.getClass());
        DruidDataSource druidDataSource = (DruidDataSource) dataSource;
        System.out.println("初始化连接数："+ druidDataSource.getInitialSize());
        System.out.println("最大连接数："+ druidDataSource.getMaxActive());
    }

}
