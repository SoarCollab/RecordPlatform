package cn.flying.monitor.server.controller;

import cn.flying.monitor.common.entity.Result;
import cn.flying.monitor.server.entity.vo.request.ChangePasswordVO;
import cn.flying.monitor.server.entity.vo.request.CreateSubAccountVO;
import cn.flying.monitor.server.entity.vo.request.ModifyEmailVO;
import cn.flying.monitor.server.entity.vo.response.SubAccountVO;
import cn.flying.monitor.server.service.AccountService;
import cn.flying.monitor.server.utils.Const;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @program: monitor
 * @description: 用户相关接口
 */
@RestController
@RequestMapping("/api/user")
public class UserController {
    @Resource
    AccountService service;

    @PostMapping("/change-password")
    public ResponseEntity<Result<Void>> changePassword(@RequestBody @Valid ChangePasswordVO vo,
                                                       @RequestAttribute(Const.ATTR_USER_ID) int clientId) {
        if (service.changePassword(clientId, vo.getPassword(), vo.getNew_password())) {
            return ResponseEntity.ok(Result.success((Void) null, "密码修改成功"));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error("原始密码输入错误"));
    }

    @PostMapping("/modify-email")
    public ResponseEntity<Result<Void>> modifyEmail(
            @RequestAttribute(Const.ATTR_USER_ID) int userId,
            @RequestBody @Valid ModifyEmailVO vo) {
        String result = service.modifyEmail(userId, vo);
        if (result == null) {
            return ResponseEntity.ok(Result.success((Void) null, "邮箱修改成功"));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(result));
    }

    @PostMapping("/sub/create")
    public ResponseEntity<Result<Void>> createSubAccount(@RequestBody @Valid CreateSubAccountVO vo) {
        service.createSubAccount(vo);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Result.success((Void) null, "子账户创建成功"));
    }

    @GetMapping("/sub/delete")
    public ResponseEntity<Result<Void>> deleteSubAccount(
            int uid,
            @RequestAttribute(Const.ATTR_USER_ID) int userId) {
        if (uid == userId) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Result.error("非法参数"));
        }
        service.deleteSubAccount(uid);
        return ResponseEntity.ok(Result.success((Void) null, "子账户删除成功"));
    }

    @GetMapping("/sub/list")
    public ResponseEntity<Result<List<SubAccountVO>>> subAccountList() {
        return ResponseEntity.ok(Result.success(service.listSubAccount(), "获取成功"));
    }
}
