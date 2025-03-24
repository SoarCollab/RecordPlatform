package cn.flying.backendTest;

import cn.flying.common.util.SnowflakeIdGenerator;
import cn.flying.dao.dto.Account;
import cn.flying.service.AccountService;
import com.alibaba.druid.pool.DruidDataSource;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * @program: RecordPlatform
 * @description: 数据库测试
 * @author: 王贝强
 * @create: 2025-03-07 13:22
 */
@SpringBootTest
public class DataBaseTest {
    @Autowired
    private DataSource dataSource;

    @Resource
    private SnowflakeIdGenerator generator;

    @Resource
    private AccountService accountService;

    @Test
    void druid_Test() {
        System.out.println(dataSource.getClass());
        DruidDataSource druidDataSource = (DruidDataSource) dataSource;
        System.out.println("初始化连接数："+ druidDataSource.getInitialSize());
        System.out.println("最大连接数："+ druidDataSource.getMaxActive());
    }



    @Test
    void Mybatis_Test() {
        List<Account> testList = new ArrayList<>();
        //雪花ID作为主键

        Account account1 = new Account();
        account1.setId(generator.nextId());
        account1.setUsername("flyingTest");
        account1.setPassword("12345689");
        account1.setRole("user");
        account1.setEmail("flyingTest@163.com");

        testList.add(account1);
        accountService.saveBatch(testList);

        LambdaQueryWrapper<Account> wrapper = Wrappers.<Account>lambdaQuery()
                .eq(Account::getUsername, "flyingTest");
        Account account2 = accountService.getOne(wrapper);
        System.out.println(account2);
    }

    @Test
    void Mybatis_Test2() {
        //逻辑删除测试（逻辑删除字段deleted）
        LambdaQueryWrapper<Account> wrapper = Wrappers.<Account>lambdaQuery()
                .eq(Account::getUsername, "flyingTest");
        Account account = accountService.getOne(wrapper);
        //testMapper.deleteById(test);
        //testService.removeById(test);
        if (account != null) {
            System.out.println(account);
        }else{
            System.out.println("用户已被删除!");
        }
    }
}
