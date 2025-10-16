package cn.flying.monitor.server.service.impl;

import cn.flying.monitor.server.entity.dto.Account;
import cn.flying.monitor.server.entity.vo.request.ConfirmResetVO;
import cn.flying.monitor.server.entity.vo.request.CreateSubAccountVO;
import cn.flying.monitor.server.entity.vo.request.EmailResetVO;
import cn.flying.monitor.server.entity.vo.request.ModifyEmailVO;
import cn.flying.monitor.server.entity.vo.response.SubAccountVO;
import cn.flying.monitor.server.mapper.AccountMapper;
import cn.flying.monitor.server.service.AccountService;
import cn.flying.monitor.server.utils.Const;
import cn.flying.monitor.server.utils.FlowUtils;
import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 账户信息处理相关服务
 */
@Service
public class AccountServiceImpl extends ServiceImpl<AccountMapper, Account> implements AccountService {

    //验证邮件发送冷却时间限制，秒为单位
    @Value("${spring.web.verify.mail-limit}")
    int verifyLimit;

    @Resource
    AmqpTemplate rabbitTemplate;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    PasswordEncoder passwordEncoder;

    @Resource
    FlowUtils flow;

    /**
     * 从数据库中通过用户名或邮箱查找用户详细信息
     *
     * @param username 用户名
     * @return 用户详细信息
     * @throws UsernameNotFoundException 如果用户未找到则抛出此异常
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Account account = this.findAccountByNameOrEmail(username);
        if (account == null)
            throw new UsernameNotFoundException("用户名或密码错误");
        return User
                .withUsername(username)
                .password(account.getPassword())
                .roles(account.getRole())
                .build();
    }

    /**
     * 通过用户名或邮件地址查找用户
     *
     * @param text 用户名或邮件
     * @return 账户实体
     */
    public Account findAccountByNameOrEmail(String text) {
        return this.query()
                .eq("username", text).or()
                .eq("email", text)
                .one();
    }

    /**
     * 生成注册验证码存入Redis中，并将邮件发送请求提交到消息队列等待发送
     *
     * @param type    类型
     * @param email   邮件地址
     * @param address 请求IP地址
     * @return 操作结果，null表示正常，否则为错误原因
     */
    public String registerEmailVerifyCode(String type, String email, String address) {
        synchronized (address.intern()) {
            if (!this.verifyLimit(address))
                return "请求频繁，请稍后再试";
            Random random = new Random();
            int code = random.nextInt(899999) + 100000;
            Map<String, Object> data = Map.of("type", type, "email", email, "code", code);
            rabbitTemplate.convertAndSend(Const.MQ_MAIL, data);
            stringRedisTemplate.opsForValue()
                    .set(Const.VERIFY_EMAIL_DATA + email, String.valueOf(code), 3, TimeUnit.MINUTES);
            return null;
        }
    }

    /**
     * 邮件验证码重置密码操作，需要检查验证码是否正确
     *
     * @param info 重置基本信息
     * @return 操作结果，null表示正常，否则为错误原因
     */
    @Override
    public String resetEmailAccountPassword(EmailResetVO info) {
        String verify = resetConfirm(new ConfirmResetVO(info.getEmail(), info.getCode()));
        if (verify != null) return verify;
        String email = info.getEmail();
        String password = passwordEncoder.encode(info.getPassword());
        boolean update = this.update().eq("email", email).set("password", password).update();
        if (update) {
            this.deleteEmailVerifyCode(email);
        }
        return update ? null : "更新失败，请联系管理员";
    }

    /**
     * 重置密码确认操作，验证验证码是否正确
     *
     * @param info 验证基本信息
     * @return 操作结果，null表示正常，否则为错误原因
     */
    @Override
    public String resetConfirm(ConfirmResetVO info) {
        String email = info.getEmail();
        String code = this.getEmailVerifyCode(email);
        if (code == null) return "请先获取验证码";
        if (!code.equals(info.getCode())) return "验证码错误，请重新输入";
        return null;
    }

    @Override
    public boolean changePassword(int id, String oldPass, String newPass) {
        Account account = this.getById(id);
        String password = account.getPassword();
        if (!passwordEncoder.matches(oldPass, password))
            return false;
        this.update(Wrappers.<Account>update().eq("id", id).set("password", passwordEncoder.encode(newPass)));
        return true;
    }

    @Override
    public void createSubAccount(CreateSubAccountVO vo) {
        Account account = findAccountByNameOrEmail(vo.getEmail());
        if (account != null)
            throw new IllegalArgumentException("邮箱已被使用！");
        account = findAccountByNameOrEmail(vo.getUsername());
        if (account != null)
            throw new IllegalArgumentException("用户名已被使用！");
        account = new Account(null, vo.getUsername(), passwordEncoder.encode(vo.getPassword())
                , vo.getEmail(), Const.ROLE_DEFAULT, JSONArray.copyOf(vo.getClients()).toString(), new Date(), "local", null, null);
        this.save(account);
    }

    @Override
    public void deleteSubAccount(int uid) {
        this.removeById(uid);
    }

    @Override
    public List<SubAccountVO> listSubAccount() {
        return this.list(Wrappers.<Account>query().eq("role", Const.ROLE_DEFAULT))
                .stream().map(account -> {
                    SubAccountVO vo = new SubAccountVO();
                    BeanUtils.copyProperties(account, vo, "clients");
                    vo.setClients(JSONArray.parse(account.getClients()));
                    return vo;
                }).toList();
    }

    @Override
    public String modifyEmail(int uid, ModifyEmailVO vo) {
        String code = getEmailVerifyCode(vo.getEmail());
        if (code == null) return "请先获取验证码！";
        if (!code.equals(vo.getCode())) return "验证码错误，请重新输入！";
        this.deleteEmailVerifyCode(vo.getEmail());
        Account account = this.findAccountByNameOrEmail(vo.getEmail());
        if (account != null && account.getId() != uid) return "该邮箱已被他人绑定，请更换邮箱！";
        this.update().set("email", vo.getEmail())
                .eq("id", uid).update();
        return null;
    }

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
    @Override
    public Account findOrCreateOAuthUser(String provider, Long oauthUserId,
                                         String username, String email) {
        // 先查找是否已存在OAuth用户
        Account account = this.baseMapper.findByOAuthUser(provider, oauthUserId);

        if (account != null) {
            // 用户已存在，直接返回
            return account;
        }

        // 用户不存在，创建新用户
        account = new Account();
        account.setUsername(username);
        account.setEmail(email);
        account.setPassword(""); // OAuth用户无需密码
        account.setRole(Const.ROLE_DEFAULT);
        account.setClients("[]"); // 空客户端列表
        account.setRegisterTime(new Date());

        // 设置OAuth相关字段
        account.setAuthType("oauth");
        account.setOauthProvider(provider);
        account.setOauthUserId(oauthUserId);

        // 保存到数据库
        this.save(account);

        return account;
    }

    /**
     * 移除Redis中存储的邮件验证码
     *
     * @param email 电邮
     */
    private void deleteEmailVerifyCode(String email) {
        String key = Const.VERIFY_EMAIL_DATA + email;
        stringRedisTemplate.delete(key);
    }

    /**
     * 获取Redis中存储的邮件验证码
     *
     * @param email 电邮
     * @return 验证码
     */
    private String getEmailVerifyCode(String email) {
        String key = Const.VERIFY_EMAIL_DATA + email;
        return stringRedisTemplate.opsForValue().get(key);
    }

    /**
     * 针对IP地址进行邮件验证码获取限流
     *
     * @param address 地址
     * @return 是否通过验证
     */
    private boolean verifyLimit(String address) {
        String key = Const.VERIFY_EMAIL_LIMIT + address;
        return flow.limitOnceCheck(key, verifyLimit);
    }
}
