package cn.flying.controller;

import cn.flying.common.annotation.SecureId;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.dao.dto.Account;
import cn.flying.dao.vo.auth.AccountVO;
import cn.flying.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * @program: RecordPlatform
 * @description:
 * @author: 王贝强
 * @create: 2024-11-01 09:51
 */
@RestController
@Tag(name = "测试接口", description = "用于测试接口的异常处理和权限控制")
public class TestController {

    @Resource
    private AccountService accountService;

    @GetMapping("/")
    @Operation(summary = "测试接口")
    public String index(){
        return "hello world,I'm flying!!!";
    }

    @GetMapping("/testException")
    @Operation(summary = "测试其他异常")
    public String testException(){
        int i = 1/0;
        return "测试其他异常";
    }

    @GetMapping("/testBusinessException")
    @Operation(summary = "测试业务异常")
    public String testBusinessException(){
        throw new GeneralException(ResultEnum.RESULT_DATA_NONE);
    }

    /**
     * 获取多个用户信息
     * 示例：处理集合类型
     */
    @GetMapping("/multiple")
    @Operation(summary = "获取多个用户信息（隐藏用户原始Id）")
    @SecureId(hideOriginalId = true)  // 隐藏原始ID
    public Result<List<AccountVO>> getMultipleUsers() {
        // 模拟查询多个用户
        List<AccountVO> users = new ArrayList<>();
        Account account1 = accountService.findAccountByNameOrEmail("flyingTest1");
        Account account2 = accountService.findAccountByNameOrEmail("flyingTest2");
        if (account1 != null&& account2 != null) {
            AccountVO vo1 = new AccountVO();
            BeanUtils.copyProperties(account1, vo1);
            users.add(vo1);
            AccountVO vo2 = new AccountVO();
            BeanUtils.copyProperties(account2, vo2);
            users.add(vo2);
        }

        // 安全切面会自动处理集合中每个对象的ID
        return Result.success(users);
    }
}
