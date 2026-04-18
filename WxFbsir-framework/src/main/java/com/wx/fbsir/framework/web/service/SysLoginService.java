package com.wx.fbsir.framework.web.service;

import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import com.wx.fbsir.common.constant.CacheConstants;
import com.wx.fbsir.common.constant.Constants;
import com.wx.fbsir.common.constant.UserConstants;
import com.wx.fbsir.common.core.domain.model.LoginUser;
import com.wx.fbsir.common.core.redis.RedisCache;
import com.wx.fbsir.common.exception.ServiceException;
import com.wx.fbsir.common.exception.user.BlackListException;
import com.wx.fbsir.common.exception.user.CaptchaException;
import com.wx.fbsir.common.exception.user.CaptchaExpireException;
import com.wx.fbsir.common.exception.user.UserNotExistsException;
import com.wx.fbsir.common.exception.user.UserPasswordNotMatchException;
import com.wx.fbsir.common.utils.DateUtils;
import com.wx.fbsir.common.utils.MessageUtils;
import com.wx.fbsir.common.utils.StringUtils;
import com.wx.fbsir.common.utils.ip.IpUtils;
import com.wx.fbsir.framework.manager.AsyncManager;
import com.wx.fbsir.framework.manager.factory.AsyncFactory;
import com.wx.fbsir.framework.security.context.AuthenticationContextHolder;
import com.wx.fbsir.system.service.ISysConfigService;
import com.wx.fbsir.system.service.ISysUserService;
import com.wx.fbsir.business.point.enums.PointsRuleCode;
import com.wx.fbsir.business.point.domain.PointsResult;
import com.wx.fbsir.business.point.service.PointsPrecheckService;
import com.wx.fbsir.business.point.mapper.PointsRecordMapper;

/**
 * 登录校验方法
 * 
 * @author wxfbsir
 */
@Component
public class SysLoginService
{
    private static final Logger log = LoggerFactory.getLogger(SysLoginService.class);

    @Autowired
    private TokenService tokenService;

    @Resource
    private AuthenticationManager authenticationManager;

    @Autowired
    private RedisCache redisCache;
    
    @Autowired
    private ISysUserService userService;

    @Autowired
    private ISysConfigService configService;

    @Autowired
    private PointsPrecheckService pointsPrecheckService;

    @Autowired
    private PointsRecordMapper pointsRecordMapper;

    /**
     * 登录验证
     * 
     * @param username 用户名
     * @param password 密码
     * @param code 验证码
     * @param uuid 唯一标识
     * @return 结果
     */
    public String login(String username, String password, String code, String uuid)
    {
        // 验证码校验
        validateCaptcha(username, code, uuid);
        // 登录前置校验
        loginPreCheck(username, password);
        // 用户验证
        Authentication authentication = null;
        java.util.Date lastLoginDate = null;
        try
        {
            UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(username, password);
            AuthenticationContextHolder.setContext(authenticationToken);
            // 该方法会去调用UserDetailsServiceImpl.loadUserByUsername
            authentication = authenticationManager.authenticate(authenticationToken);
            LoginUser tmp = (LoginUser) authentication.getPrincipal();
            if (tmp != null && tmp.getUser() != null) {
                lastLoginDate = tmp.getUser().getLoginDate();
            }
        }
        catch (Exception e)
        {
            if (e instanceof BadCredentialsException)
            {
                AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("user.password.not.match")));
                throw new UserPasswordNotMatchException();
            }
            else
            {
                String raw = e.getMessage();
                String friendly = raw;
                if (raw != null)
                {
                    if (raw.contains("Communications link failure") || raw.contains("Could not open JDBC")
                        || raw.contains("CommunicationsException"))
                    {
                        friendly = "数据库连接失败，请确认 MySQL 已启动且账号配置正确";
                    }
                    else if (raw.contains("Redis") || raw.contains("Unable to connect to Redis")
                        || raw.contains("RedisConnectionFailure"))
                    {
                        friendly = "Redis 连接失败，请确认 Redis 已启动且配置正确";
                    }
                }
                AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, raw != null ? raw : "error"));
                throw new ServiceException(friendly);
            }
        }
        finally
        {
            AuthenticationContextHolder.clearContext();
        }
        AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_SUCCESS, MessageUtils.message("user.login.success")));
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        try
        {
            recordLoginInfo(loginUser.getUserId());
        }
        catch (Exception ex)
        {
            log.warn("更新登录时间/地点失败（不影响本次登录发牌） userId={}: {}", loginUser.getUserId(), ex.getMessage());
        }
        // 首次登录奖励（判断是否已领取），失败不影响登录
        try
        {
            boolean alreadyGranted = pointsRecordMapper.checkUserTaskCompleted(loginUser.getUserId(),
                    PointsRuleCode.FIRST_LOGIN_BONUS.getCode()) > 0;
            if (!alreadyGranted)
            {
                PointsResult firstLoginResult = pointsPrecheckService.tryChangePoints(loginUser.getUserId(),
                        PointsRuleCode.FIRST_LOGIN_BONUS.getCode(), PointsRuleCode.FIRST_LOGIN_BONUS.getDefaultPoints());
                if (!firstLoginResult.isSuccess())
                {
                    log.info("First login bonus skipped for user {}, code={}, msg={}",
                            loginUser.getUserId(), firstLoginResult.getCode(), firstLoginResult.getMsg());
                }
            }
        }
        catch (Exception ex)
        {
            log.warn("First login bonus grant error for user {}", loginUser.getUserId(), ex);
        }
        // 登录成功后发放每日登录积分（失败不影响登录）
        try
        {
            PointsResult pointsResult = pointsPrecheckService.tryChangePoints(loginUser.getUserId(),
                    PointsRuleCode.DAILY_LOGIN.getCode(), null);
            if (!pointsResult.isSuccess())
            {
                log.debug("Daily login points skipped for user {}, code={}, msg={}",
                        loginUser.getUserId(), pointsResult.getCode(), pointsResult.getMsg());
            }
        }
        catch (Exception ex)
        {
            log.warn("Daily login points grant error for user {}", loginUser.getUserId(), ex);
        }
        // 生成 token（会话缓存依赖 Redis）
        try
        {
            return tokenService.createToken(loginUser);
        }
        catch (Exception e)
        {
            log.error("创建登录令牌失败（常见原因：Redis 不可用）: {}", e.getMessage());
            throw new ServiceException("登录会话建立失败，请确认 Redis 已启动且应用可连接（若已启动仍失败，请查看后台日志）");
        }
    }

    /**
     * 校验验证码
     * 
     * @param username 用户名
     * @param code 验证码
     * @param uuid 唯一标识
     * @return 结果
     */
    public void validateCaptcha(String username, String code, String uuid)
    {
        boolean captchaEnabled = configService.selectCaptchaEnabled();
        if (captchaEnabled)
        {
            String verifyKey = CacheConstants.CAPTCHA_CODE_KEY + StringUtils.nvl(uuid, "");
            String captcha;
            try
            {
                captcha = redisCache.getCacheObject(verifyKey);
            }
            catch (Exception e)
            {
                log.error("读取验证码失败（请检查 Redis）: {}", e.getMessage());
                throw new ServiceException("验证码服务不可用，请确认 Redis 已启动且应用可连接");
            }
            if (captcha == null)
            {
                AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("user.jcaptcha.expire")));
                throw new CaptchaExpireException();
            }
            try
            {
                redisCache.deleteObject(verifyKey);
            }
            catch (Exception e)
            {
                log.warn("删除验证码缓存失败（忽略）: {}", e.getMessage());
            }
            if (!code.equalsIgnoreCase(captcha))
            {
                AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("user.jcaptcha.error")));
                throw new CaptchaException();
            }
        }
    }

    /**
     * 登录前置校验
     * @param username 用户名
     * @param password 用户密码
     */
    public void loginPreCheck(String username, String password)
    {
        // 用户名或密码为空 错误
        if (StringUtils.isEmpty(username) || StringUtils.isEmpty(password))
        {
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("not.null")));
            throw new UserNotExistsException();
        }
        // 密码如果不在指定范围内 错误
        if (password.length() < UserConstants.PASSWORD_MIN_LENGTH
                || password.length() > UserConstants.PASSWORD_MAX_LENGTH)
        {
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("user.password.not.match")));
            throw new UserPasswordNotMatchException();
        }
        // 用户名不在指定范围内 错误
        if (username.length() < UserConstants.USERNAME_MIN_LENGTH
                || username.length() > UserConstants.USERNAME_MAX_LENGTH)
        {
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("user.password.not.match")));
            throw new UserPasswordNotMatchException();
        }
        // IP黑名单校验
        String blackStr = configService.selectConfigByKey("sys.login.blackIPList");
        if (IpUtils.isMatchedIp(blackStr, IpUtils.getIpAddr()))
        {
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, MessageUtils.message("login.blocked")));
            throw new BlackListException();
        }
    }

    /**
     * 记录登录信息
     *
     * @param userId 用户ID
     */
    public void recordLoginInfo(Long userId)
    {
        userService.updateLoginInfo(userId, IpUtils.getIpAddr(), DateUtils.getNowDate());
    }
}
