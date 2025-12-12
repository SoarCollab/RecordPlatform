package cn.flying.fisco_bcos.adapter.impl;

import cn.flying.fisco_bcos.adapter.model.ChainType;
import cn.flying.fisco_bcos.service.SharingService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地 FISCO BCOS 节点适配器
 * 用于开发和测试环境，连接本地部署的 FISCO BCOS 节点
 *
 * <p>激活条件: {@code blockchain.active=local-fisco} (默认)
 *
 * @see AbstractFiscoAdapter
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "blockchain.active", havingValue = "local-fisco", matchIfMissing = true)
public class LocalFiscoAdapter extends AbstractFiscoAdapter {

    @Resource
    private SharingService sharingService;

    @Override
    public ChainType getChainType() {
        return ChainType.LOCAL_FISCO;
    }

    @Override
    protected SharingService getSharingService() {
        return sharingService;
    }
}
