package cn.flying.monitor.server.service;

import cn.flying.monitor.server.entity.dto.Client;
import cn.flying.monitor.server.entity.vo.request.*;
import cn.flying.monitor.server.entity.vo.response.*;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * @program: monitor
 * @description: 客户端服务接口
 * @author: 王贝强
 * @create: 2024-07-13 16:22
 */
public interface ClientService extends IService<Client> {
    String getToken();

    boolean registerClient(String token);

    Client findClientById(int id);

    Client findClientByToken(String token);

    void updateClientDetail(ClientDetailVO vo, Client client);

    void updateRuntimeDetail(RuntimeDetailVO vo, Client client);

    List<ClientPreviewVO> listClients();

    List<ClientSimpleVO> listSimpleClients();

    void renameClient(RenameClientVO vo);

    ClientDetailsVO clientDetails(int clientId);

    void renameNode(RenameNodeVO vo);

    RuntimeHistoryVO clientRuntimeDetailsHistory(int clientId);

    RuntimeDetailVO clientRuntimeDetailsNow(int clientId);

    void deleteClient(int clientId);

    void saveSshConnection(SshConnectVO vo);

    SshSettingsVO getSshSetting(int clientId);
}
