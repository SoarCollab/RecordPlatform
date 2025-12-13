package cn.flying.service;

import cn.flying.dao.dto.Account;
import cn.flying.dao.vo.auth.*;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.Collection;
import java.util.Map;

public interface AccountService extends IService<Account>, UserDetailsService {
    Account findAccountByNameOrEmail(String text);
    Account findAccountById(Long id);

    /**
     * 批量查询用户信息
     * @param ids 用户ID集合
     * @return 用户ID -> Account 映射
     */
    Map<Long, Account> findAccountsByIds(Collection<Long> ids);

    String registerEmailVerifyCode(String type, String email, String address);
    String registerEmailAccount(EmailRegisterVO info);
    String resetEmailAccountPassword(EmailResetVO info);
    String resetConfirm(ConfirmResetVO info);
    String modifyEmail(Long userId, ModifyEmailVO modifyEmailVO);
    String changePassword(Long userId, ChangePasswordVO changePasswordVO);

    /**
     * 更新用户信息（头像等）
     * @param userId 用户ID
     * @param vo 更新请求
     * @return 更新后的用户信息
     */
    Account updateUserInfo(Long userId, UpdateUserVO vo);
}
