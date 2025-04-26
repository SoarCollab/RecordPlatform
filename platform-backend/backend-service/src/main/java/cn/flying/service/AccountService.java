package cn.flying.service;

import cn.flying.dao.dto.Account;
import cn.flying.dao.vo.auth.*;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.security.core.userdetails.UserDetailsService;

public interface AccountService extends IService<Account>, UserDetailsService {
    Account findAccountByNameOrEmail(String text);
    Account findAccountById(String id);
    String registerEmailVerifyCode(String type, String email, String address);
    String registerEmailAccount(EmailRegisterVO info);
    String resetEmailAccountPassword(EmailResetVO info);
    String resetConfirm(ConfirmResetVO info);
    String modifyEmail(Long userId, ModifyEmailVO modifyEmailVO);
    String changePassword(String userId, ChangePasswordVO changePasswordVO);
}
