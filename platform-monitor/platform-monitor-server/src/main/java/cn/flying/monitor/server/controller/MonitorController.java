package cn.flying.monitor.server.controller;

import cn.flying.monitor.common.entity.Result;
import cn.flying.monitor.server.entity.dto.Account;
import cn.flying.monitor.server.entity.vo.request.RenameClientVO;
import cn.flying.monitor.server.entity.vo.request.RenameNodeVO;
import cn.flying.monitor.server.entity.vo.request.SshConnectVO;
import cn.flying.monitor.server.entity.vo.response.*;
import cn.flying.monitor.server.service.AccountService;
import cn.flying.monitor.server.service.ClientService;
import cn.flying.monitor.server.utils.Const;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @program: monitor
 * @description: 监控信息接口
 * @author: 王贝强
 * @create: 2024-07-18 15:28
 */

@RestController
@RequestMapping("/api/monitor")
public class MonitorController {
    @Resource
    ClientService clientService;
    @Resource
    AccountService accountService;

    @GetMapping("/list")
    public ResponseEntity<Result<List<ClientPreviewVO>>> listAllClient(
            @RequestAttribute(Const.ATTR_USER_ID) int userId,
            @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        List<ClientPreviewVO> clients = clientService.listClients();
        if (this.isAdminAccount(userRole)) {
            return ResponseEntity.ok(Result.success(clients, "获取成功"));
        }
        List<Integer> ids = this.accountAccessClients(userId);
        List<ClientPreviewVO> filtered = clients.stream()
                .filter(vo -> ids.contains(vo.getId()))
                .toList();
        return ResponseEntity.ok(Result.success(filtered, "获取成功"));
    }

    private boolean isAdminAccount(String role) {
        role = role.substring(5);
        return Const.ROLE_ADMIN.equals(role);
    }

    private List<Integer> accountAccessClients(int uid) {
        Account account = accountService.getById(uid);
        return account.getClientList();
    }

    @GetMapping("/simple-list")
    public ResponseEntity<Result<List<ClientSimpleVO>>> simpleClientList(
            @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (this.isAdminAccount(userRole)) {
            return ResponseEntity.ok(Result.success(clientService.listSimpleClients(), "获取成功"));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.error("权限不足"));
    }

    @PostMapping("/rename")
    public ResponseEntity<Result<Void>> renameClient(
            @RequestBody @Valid RenameClientVO client,
            @RequestAttribute(Const.ATTR_USER_ID) int userId,
            @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (this.permissionCheck(userId, userRole, client.getId())) {
            clientService.renameClient(client);
            return ResponseEntity.ok(Result.success((Void) null, "修改成功"));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.error("权限不足"));
    }

    private boolean permissionCheck(int uid, String role, int clientId) {
        if (this.isAdminAccount(role)) {
            return true;
        }
        return this.accountAccessClients(uid).contains(clientId);
    }

    @PostMapping("/node")
    public ResponseEntity<Result<Void>> renameNode(
            @RequestBody @Valid RenameNodeVO vo,
            @RequestAttribute(Const.ATTR_USER_ID) int userId,
            @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (this.permissionCheck(userId, userRole, vo.getId())) {
            clientService.renameNode(vo);
            return ResponseEntity.ok(Result.success((Void) null, "修改成功"));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.error("权限不足"));
    }

    @GetMapping("/details")
    public ResponseEntity<Result<ClientDetailsVO>> details(
            @RequestParam int clientId,
            @RequestAttribute(Const.ATTR_USER_ID) int userId,
            @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (this.permissionCheck(userId, userRole, clientId)) {
            return ResponseEntity.ok(Result.success(clientService.clientDetails(clientId), "获取成功"));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.error("权限不足"));
    }

    @GetMapping("/runtime_history")
    public ResponseEntity<Result<RuntimeHistoryVO>> runtimeDetailsHistory(
            @RequestParam int clientId,
            @RequestAttribute(Const.ATTR_USER_ID) int userId,
            @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (this.permissionCheck(userId, userRole, clientId)) {
            return ResponseEntity.ok(Result.success(clientService.clientRuntimeDetailsHistory(clientId), "获取成功"));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.error("权限不足"));
    }

    @GetMapping("/runtime_now")
    public ResponseEntity<Result<RuntimeDetailVO>> runtimeDetailsNow(
            @RequestParam int clientId,
            @RequestAttribute(Const.ATTR_USER_ID) int userId,
            @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (this.permissionCheck(userId, userRole, clientId)) {
            return ResponseEntity.ok(Result.success(clientService.clientRuntimeDetailsNow(clientId), "获取成功"));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.error("权限不足"));
    }

    @GetMapping("/register")
    public ResponseEntity<Result<String>> registerToken(
            @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (this.isAdminAccount(userRole)) {
            return ResponseEntity.ok(Result.success(clientService.getToken(), "生成成功"));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.error("权限不足"));
    }

    @GetMapping("/delete")
    public ResponseEntity<Result<Void>> deleteClient(
            @RequestParam int clientId,
            @RequestAttribute(Const.ATTR_USER_ROLE) String userRole) {
        if (this.isAdminAccount(userRole)) {
            clientService.deleteClient(clientId);
            return ResponseEntity.ok(Result.success((Void) null, "删除成功"));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.error("权限不足"));
    }

    @PostMapping("/ssh-save")
    public ResponseEntity<Result<Void>> saveSSHConnection(
            @RequestAttribute(Const.ATTR_USER_ID) int userId,
            @RequestAttribute(Const.ATTR_USER_ROLE) String userRole,
            @RequestBody @Valid SshConnectVO vo) {
        if (this.permissionCheck(userId, userRole, vo.getId())) {
            clientService.saveSshConnection(vo);
            return ResponseEntity.ok(Result.success((Void) null, "保存成功"));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.error("权限不足"));
    }

    @GetMapping("/ssh")
    public ResponseEntity<Result<SshSettingsVO>> getSshConnect(
            @RequestAttribute(Const.ATTR_USER_ID) int userId,
            @RequestAttribute(Const.ATTR_USER_ROLE) String userRole,
            @RequestParam int clientId) {
        if (this.permissionCheck(userId, userRole, clientId)) {
            return ResponseEntity.ok(Result.success(clientService.getSshSetting(clientId), "获取成功"));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.error("权限不足"));
    }
}
