package cn.flying.monitor.server.service;

import cn.flying.monitor.server.entity.dto.Account;
import cn.flying.monitor.server.entity.vo.request.ConfirmResetVO;
import cn.flying.monitor.server.entity.vo.request.CreateSubAccountVO;
import cn.flying.monitor.server.entity.vo.request.EmailResetVO;
import cn.flying.monitor.server.entity.vo.request.ModifyEmailVO;
import cn.flying.monitor.server.entity.vo.response.SubAccountVO;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.List;

public interface AccountService extends IService<Account>, UserDetailsService {
    Account findAccountByNameOrEmail(String text);

    String registerEmailVerifyCode(String type, String email, String address);

    String resetEmailAccountPassword(EmailResetVO info);

    String resetConfirm(ConfirmResetVO info);

    boolean changePassword(int id, String oldPass, String newPass);

    void createSubAccount(CreateSubAccountVO vo);

    void deleteSubAccount(int uid);

    List<SubAccountVO> listSubAccount();

    String modifyEmail(int uid, ModifyEmailVO vo);

    /**
     * 查找或创建OAuth用户
     * 用于OAuth2单点登录，如果用户不存在则自动创建
     *
     * @param provider    OAuth提供者标识，如platform-identity
     * @param oauthUserId OAuth提供者系统中的用户ID
     * @param username    用户名
     * @param email       邮箱地址
     * @return 创建或找到的Account对象
     */
    Account findOrCreateOAuthUser(String provider, Long oauthUserId,
                                  String username, String email);
}
