package cn.flying.monitor.server.service.impl;

import cn.flying.monitor.server.entity.dto.Client;
import cn.flying.monitor.server.entity.dto.ClientDetail;
import cn.flying.monitor.server.entity.dto.ClientSsh;
import cn.flying.monitor.server.entity.vo.request.*;
import cn.flying.monitor.server.entity.vo.response.*;
import cn.flying.monitor.server.mapper.ClientDetailMapper;
import cn.flying.monitor.server.mapper.ClientMapper;
import cn.flying.monitor.server.mapper.ClientSshMapper;
import cn.flying.monitor.server.service.ClientService;
import cn.flying.monitor.server.utils.influxDBUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @program: monitor
 * @description: 客户端服务接口实现类
 * @author: 王贝强
 * @create: 2024-07-13 16:24
 */
@Slf4j
@Service
public class ClientServiceImpl extends ServiceImpl<ClientMapper, Client> implements ClientService {

    private final Map<Integer, Client> clientIdCache = new ConcurrentHashMap<>();
    private final Map<String, Client> clientTokenCache = new ConcurrentHashMap<>();
    private final Map<Integer, RuntimeDetailVO> currentRuntime = new ConcurrentHashMap<>();
    @Resource
    influxDBUtils influx;
    private String registerToken = this.createNewToken();
    @Resource
    private ClientDetailMapper clientDetailMapper;
    @Resource
    private ClientSshMapper clientSshMapper;

    @Override
    public String getToken() {
        return registerToken;
    }

    @Override
    public boolean registerClient(String token) {
        if (this.registerToken.equals(token)) {
            int id = this.randomClientId();
            Client client = new Client(id, "未命名主机", token, "cn", "未命名节点", new Date());
            if (this.save(client)) {
                this.registerToken = this.createNewToken();
                this.addClientCache(client);
                log.info("主机注册成功，Id：{}", id);
                return true;
            }
        }
        return false;
    }

    @Override
    public Client findClientById(int id) {
        return clientIdCache.get(id);
    }

    @Override
    public Client findClientByToken(String token) {
        return clientTokenCache.get(token);
    }

    @Override
    public void updateClientDetail(ClientDetailVO vo, Client client) {
        ClientDetail clientDetail = new ClientDetail();
        BeanUtils.copyProperties(vo, clientDetail);
        clientDetail.setId(client.getId());
        if (Objects.nonNull(clientDetailMapper.selectById(client.getId()))) {
            clientDetailMapper.updateById(clientDetail);
        } else {
            clientDetailMapper.insert(clientDetail);
        }
    }

    @Override
    public void updateRuntimeDetail(RuntimeDetailVO vo, Client client) {
        currentRuntime.put(client.getId(), vo);
        influx.writeRuntimeData(client.getId(), vo);
    }

    @Override
    public List<ClientPreviewVO> listClients() {
        return clientIdCache.values().stream().map(client -> {
            ClientPreviewVO vo = client.asViewObject(ClientPreviewVO.class);
            BeanUtils.copyProperties(clientDetailMapper.selectById(client.getId()), vo);
            RuntimeDetailVO runtime = currentRuntime.get(client.getId());
            if (this.isOnline(runtime)) {
                BeanUtils.copyProperties(runtime, vo);
                vo.setOnline(true);
            }
            return vo;
        }).toList();
    }

    @Override
    public List<ClientSimpleVO> listSimpleClients() {
        return clientIdCache.values().stream().map(client -> {
            ClientSimpleVO vo = client.asViewObject(ClientSimpleVO.class);
            BeanUtils.copyProperties(clientDetailMapper.selectById(vo.getId()), vo);
            return vo;
        }).toList();
    }

    @Override
    public void renameClient(RenameClientVO vo) {
        this.update(Wrappers.<Client>update().eq("id", vo.getId()).set("name", vo.getName()));
        this.initClientCache();
    }

    @PostConstruct
    public void initClientCache() {
        clientIdCache.clear();
        clientTokenCache.clear();
        List<Client> clients = this.list();

        // 数据完整性验证
        long invalidCount = clients.stream()
                .filter(client -> client.getToken() == null || client.getToken().isBlank())
                .count();

        if (invalidCount > 0) {
            log.error("发现 {} 个客户端记录的 token 为空或空白，请检查数据库表结构是否正确。" +
                    "client 表必须包含 token 字段，且为 NOT NULL 和 UNIQUE 约束", invalidCount);
        }

        // 加载客户端缓存
        clients.forEach(this::addClientCache);

        log.info("客户端缓存初始化完成，共加载 {} 个客户端记录，缓存大小: ID缓存={}, Token缓存={}",
                clients.size(), clientIdCache.size(), clientTokenCache.size());
    }

    @Override
    public ClientDetailsVO clientDetails(int clientId) {
        ClientDetailsVO client = clientIdCache.get(clientId).asViewObject(ClientDetailsVO.class);
        BeanUtils.copyProperties(clientDetailMapper.selectById(clientId), client);
        client.setOnline(this.isOnline(currentRuntime.get(clientId)));
        return client;
    }

    @Override
    public void renameNode(RenameNodeVO vo) {
        this.update(Wrappers.<Client>update().eq("id", vo.getId())
                .set("location", vo.getLocation()).set("node", vo.getNode()));
        this.initClientCache();
    }

    @Override
    public RuntimeHistoryVO clientRuntimeDetailsHistory(int clientId) {
        RuntimeHistoryVO vo = influx.readRuntimeHistory(clientId);
        ClientDetail detail = clientDetailMapper.selectById(clientId);
        BeanUtils.copyProperties(detail, vo);
        return vo;
    }

    @Override
    public RuntimeDetailVO clientRuntimeDetailsNow(int clientId) {
        return currentRuntime.get(clientId);
    }

    @Override
    public void deleteClient(int clientId) {
        this.removeById(clientId);
        baseMapper.deleteById(clientId);
        this.initClientCache();
        currentRuntime.remove(clientId);
    }

    @Override
    public void saveSshConnection(SshConnectVO vo) {
        Client client = clientIdCache.get(vo.getId());
        if (client == null) return;
        ClientSsh clientSsh = new ClientSsh();
        BeanUtils.copyProperties(vo, clientSsh);
        if (Objects.nonNull(clientSshMapper.selectById(client.getId())))
            clientSshMapper.updateById(clientSsh);
        else
            clientSshMapper.insert(clientSsh);
    }

    @Override
    public SshSettingsVO getSshSetting(int clientId) {
        ClientSsh clientSsh = clientSshMapper.selectById(clientId);
        SshSettingsVO vo;
        if (clientSsh == null) {
            ClientDetail detail = clientDetailMapper.selectById(clientId);
            vo = new SshSettingsVO();
            vo.setIp(detail.getIp());
        } else vo = clientSsh.asViewObject(SshSettingsVO.class);
        return vo;
    }

    private boolean isOnline(RuntimeDetailVO runtime) {
        return runtime != null && System.currentTimeMillis() - runtime.getTimestamp() < 60 * 1000;
    }

    private int randomClientId() {
        return new Random().nextInt(90000000) + 10000000;
    }

    private String createNewToken() {
        String CHARACTERS = "abcdefghijhlmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder builder = new StringBuilder(24);
        for (int i = 0; i < 24; i++) {
            builder.append(CHARACTERS.charAt(random.nextInt(CHARACTERS.length())));
        }
        return builder.toString();
    }

    private void addClientCache(Client client) {
        // 防御性检查：避免 null token 污染缓存
        if (client == null) {
            log.warn("客户端对象为 null，跳过缓存加载");
            return;
        }

        if (client.getToken() == null || client.getToken().isBlank()) {
            log.warn("客户端 {} 的 token 为空或空白，跳过缓存加载。请检查数据库记录和表结构", client.getId());
            return;
        }

        clientIdCache.put(client.getId(), client);
        clientTokenCache.put(client.getToken(), client);
        log.debug("客户端 {} 已加载到缓存，Token: {}...", client.getId(),
                client.getToken().substring(0, Math.min(8, client.getToken().length())));
    }
}
