package cn.flying.fisco_bcos.adapter.impl;

import cn.flying.fisco_bcos.adapter.model.ChainType;
import cn.flying.fisco_bcos.service.SharingService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * BSN FISCO BCOS 节点适配器
 * 用于生产环境，连接 BSN 区块链服务网络托管的 FISCO BCOS 节点
 *
 * <p>激活条件: {@code blockchain.active=bsn-fisco}
 *
 * <p>与 LocalFiscoAdapter 使用相同的 SDK 和合约调用逻辑，
 * 区别在于连接的是 BSN 托管的城市节点而非本地节点。
 *
 * @see AbstractFiscoAdapter
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "blockchain.active", havingValue = "bsn-fisco")
public class BsnFiscoAdapter extends AbstractFiscoAdapter {

    @Resource
    private SharingService sharingService;

    @Override
    public ChainType getChainType() {
        return ChainType.BSN_FISCO;
    }

    @Override
    protected SharingService getSharingService() {
        return sharingService;
    }
}
