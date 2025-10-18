package cn.flying.monitor.server.controller;

import cn.flying.monitor.common.entity.Result;
import cn.flying.monitor.server.entity.dto.Client;
import cn.flying.monitor.server.entity.vo.request.ClientDetailVO;
import cn.flying.monitor.server.entity.vo.request.RuntimeDetailVO;
import cn.flying.monitor.server.service.ClientService;
import cn.flying.monitor.server.utils.Const;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * @program: monitor
 * @description: 客户端接口
 */
@RestController
@RequestMapping("/monitor")
public class ClientController {
    @Resource
    ClientService clientService;

    @GetMapping("/register")
    public ResponseEntity<Result<Void>> registerClient(@RequestHeader("Authorization") String token) {
        if (clientService.registerClient(token)) {
            return ResponseEntity.ok(Result.success((Void) null, "注册成功"));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.error("客户端注册失败，请检查Token是否正确！"));
    }

    @PostMapping("/detail")
    public ResponseEntity<Result<Void>> updateClientDetails(
            @RequestAttribute(Const.ATTR_CLIENT) Client client,
            @RequestBody @Valid ClientDetailVO vo) {
        clientService.updateClientDetail(vo, client);
        return ResponseEntity.ok(Result.success((Void) null, "更新成功"));
    }

    @PostMapping("/runtime")
    public ResponseEntity<Result<Void>> updateRuntimeDetails(
            @RequestAttribute(Const.ATTR_CLIENT) Client client,
            @RequestBody @Valid RuntimeDetailVO vo) {
        clientService.updateRuntimeDetail(vo, client);
        return ResponseEntity.ok(Result.success((Void) null, "更新成功"));
    }
}
