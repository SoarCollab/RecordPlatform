package cn.flying.monitor.server.controller;

import cn.flying.monitor.server.entity.RestBean;
import cn.flying.monitor.server.entity.dto.Client;
import cn.flying.monitor.server.entity.vo.request.ClientDetailVO;
import cn.flying.monitor.server.entity.vo.request.RuntimeDetailVO;
import cn.flying.monitor.server.service.ClientService;
import cn.flying.monitor.server.utils.Const;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * @program: monitor
 * @description: 客户端接口
 * @author: 王贝强
 * @create: 2024-07-12 21:23
 */
@RestController
@RequestMapping("/monitor")
public class ClientController {
    @Resource
    ClientService clientService;

    @GetMapping("/register")
    public RestBean<Void> registerClient(@RequestHeader("Authorization") String token) {
        return clientService.registerClient(token) ? RestBean.success() : RestBean.failure(401, "客户端注册失败，请检查Token是否正确！");
    }

    @PostMapping("/detail")
    public RestBean<Void> updateClientDetails(@RequestAttribute(Const.ATTR_CLIENT) Client client,
                                              @RequestBody @Valid ClientDetailVO vo) {
        clientService.updateClientDetail(vo, client);
        return RestBean.success();
    }

    @PostMapping("/runtime")
    public RestBean<Void> updateRuntimeDetails(@RequestAttribute(Const.ATTR_CLIENT) Client client,
                                               @RequestBody @Valid RuntimeDetailVO vo) {
        clientService.updateRuntimeDetail(vo, client);
        return RestBean.success();
    }
}
