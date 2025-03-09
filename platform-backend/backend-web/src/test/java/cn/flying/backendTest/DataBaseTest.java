package cn.flying.backendTest;


import cn.flying.common.aspect.SecureIdAspect;
import cn.flying.common.constant.Result;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.vo.response.AccountVO;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import com.alibaba.druid.pool.DruidDataSource;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import cn.flying.service.AccountService;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

/**
 * @program: RecordPlatform
 * @description: 数据库测试
 * @author: flyingcoding
 * @create: 2025-03-07 13:22
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test") // 激活test配置文件
public class DataBaseTest {
    @Autowired
    private DataSource dataSource;

    @Resource
    private AccountService accountService;
    
    @Autowired
    private ApplicationContext applicationContext;
    
    @Autowired
    private SecureIdAspect secureIdAspect;

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
        account1.setId(IdUtils.nextEntityId());
        account1.setUsername("flyingTest111");
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
        
        if (account != null) {
            System.out.println(account);
        }else{
            System.out.println("用户已被删除!");
        }
    }

    @Test
    void OutEntity_Test() {
        log.info("======= 开始测试ID混淆功能 =======");
        
        // 创建测试用户账号
        Account account = new Account();
        account.setId(IdUtils.nextEntityId());
        account.setUsername("flyingTest");
        account.setPassword("12345689");
        account.setRole("user");
        account.setEmail("flyingTest@163.com");

        log.info("原始账号数据：{}", account);
        
        // 创建AccountVO并复制属性
        AccountVO accountVO = new AccountVO();
        BeanUtils.copyProperties(account, accountVO);
        
        // 创建Result包装
        log.info("转换前VO数据：{}", accountVO);
        Result<AccountVO> result = Result.success(accountVO);
        
        // 通过代理对象调用处理方法
        TestSecureIdService testService = applicationContext.getBean(TestSecureIdService.class);
        
        log.info("开始调用代理方法...");
        Result<AccountVO> processedResult = testService.processWithSecureId(result);
        log.info("代理方法调用完成");
        
        log.info("转换后VO数据：{}", processedResult.getData());
        
        // 直接输出关键信息
        AccountVO resultVO = processedResult.getData();
        log.info("原始ID: {}", resultVO.getId());
        log.info("外部ID: {}", resultVO.getExternalId());
        
        // 验证ID是否被隐藏
        assertNull(resultVO.getId(), "ID应该被隐藏");
        assertNotNull(resultVO.getExternalId(), "外部ID应该被设置");
        
        log.info("======= ID混淆功能测试完成 =======");
    }
}
