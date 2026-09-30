package com.per.server.init;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.per.server.common.TokenUtil;
import com.per.server.dto.EnginePackageVO;
import com.per.server.entity.EnginePackage;
import com.per.server.entity.SysConfig;
import com.per.server.entity.User;
import com.per.server.mapper.EnginePackageMapper;
import com.per.server.mapper.SysConfigMapper;
import com.per.server.mapper.UserMapper;
import com.per.server.service.EnginePackageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 系统数据初始化器：应用启动后确保内置管理员账号与节点注册 token 存在，
 * 并在 engine_package 表为空时按配置引导注册默认引擎包
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    /** 内置管理员用户名 */
    private static final String ADMIN_USERNAME = "admin";
    /** 系统配置键：节点注册 token */
    private static final String CONFIG_REGISTER_TOKEN = "register.token";
    /** 系统配置键：admin 首次登录强制改密标记（=1 表示仍使用初始密码，需强制改密） */
    private static final String CONFIG_ADMIN_INITIAL_PASSWORD_FLAG = "admin.initial-password-flag";
    /** 引导引擎包备注 */
    private static final String BOOTSTRAP_REMARK = "启动引导自动注册";

    private final UserMapper userMapper;
    private final SysConfigMapper sysConfigMapper;
    private final EnginePackageMapper enginePackageMapper;
    private final EnginePackageService enginePackageService;
    private final BCryptPasswordEncoder passwordEncoder;

    /** 启动引导引擎包文件路径（来自配置 per.engine.bootstrap-file，默认空） */
    @Value("${per.engine.bootstrap-file:}")
    private String engineBootstrapFile;

    /** 指定初始注册 token（来自配置 per.register.default-token，默认空=随机生成 32 位 hex）；
     *  一键部署场景由环境变量 PER_REGISTER_DEFAULT_TOKEN 注入，保证 server 与 Agent 容器共用同一预知 token */
    @Value("${per.register.default-token:}")
    private String registerDefaultToken;

    /**
     * 应用启动完成后执行初始化
     *
     * @param args 启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        initAdminUser();
        initRegisterToken();
        initEngineBootstrap();
    }

    /**
     * 初始化内置管理员账号（admin/admin123，角色 ADMIN），已存在则跳过；
     * 同时确保 sys_config 存在 admin.initial-password-flag=1（新建 admin 必写，
     * 存量 admin 无该键时兼容补写，用于首次登录强制改密）
     */
    private void initAdminUser() {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, ADMIN_USERNAME));
        if (count == null || count == 0) {
            User admin = new User();
            admin.setUsername(ADMIN_USERNAME);
            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setNickname("系统管理员");
            admin.setRole("ADMIN");
            admin.setStatus(1);
            userMapper.insert(admin);
            log.info("内置管理员账号初始化完成：admin/admin123");
        }
        initAdminInitialPasswordFlag();
    }

    /**
     * 补写 admin 首次登录强制改密标记（admin.initial-password-flag=1），已存在则跳过；
     * 该标记在 admin 通过 /api/auth/change-password 修改密码后清除
     */
    private void initAdminInitialPasswordFlag() {
        if (sysConfigMapper.selectById(CONFIG_ADMIN_INITIAL_PASSWORD_FLAG) != null) {
            return;
        }
        SysConfig config = new SysConfig();
        config.setConfigKey(CONFIG_ADMIN_INITIAL_PASSWORD_FLAG);
        config.setConfigValue("1");
        sysConfigMapper.insert(config);
        log.info("admin 首次登录强制改密标记已写入（admin.initial-password-flag=1）");
    }

    /**
     * 初始化节点注册 token：配置了 per.register.default-token 时使用指定值（一键部署场景
     * 与 Agent 容器共用同一预知 token），否则随机生成 32 位 hex；已存在则跳过
     */
    private void initRegisterToken() {
        if (sysConfigMapper.selectById(CONFIG_REGISTER_TOKEN) != null) {
            return;
        }
        String token = StringUtils.hasText(registerDefaultToken)
                ? registerDefaultToken.trim() : TokenUtil.randomHex(32);
        SysConfig config = new SysConfig();
        config.setConfigKey(CONFIG_REGISTER_TOKEN);
        config.setConfigValue(token);
        sysConfigMapper.insert(config);
        log.info("节点注册token初始化完成（{}）", StringUtils.hasText(registerDefaultToken) ? "使用配置指定值" : "随机生成");
    }

    /**
     * 启动引导默认引擎包：engine_package 表为空且 per.engine.bootstrap-file 指向的
     * zip 存在时自动注册为引擎包（version=文件名去扩展名，md5 计算，is_current=1）；
     * 未配置或文件不存在时仅 INFO 提示"未配置初始引擎包"；表非空时跳过
     */
    private void initEngineBootstrap() {
        Path file = StringUtils.hasText(engineBootstrapFile) ? Path.of(engineBootstrapFile.trim()) : null;
        if (file == null || !Files.isRegularFile(file)) {
            log.info("未配置初始引擎包（如需自动注册，请设置 per.engine.bootstrap-file 指向引擎 zip）");
            return;
        }
        Long count = enginePackageMapper.selectCount(new LambdaQueryWrapper<EnginePackage>());
        if (count != null && count > 0) {
            log.info("engine_package 表已有 {} 条记录，跳过初始引擎包引导", count);
            return;
        }
        String version = stripExtension(file.getFileName().toString());
        EnginePackageVO registered = enginePackageService.registerLocalFile(file, version, BOOTSTRAP_REMARK);
        log.info("初始引擎包注册完成：version={}, md5={}, file={}",
                registered.getVersion(), registered.getMd5(), file);
    }

    /**
     * 去除文件名的最后一个扩展名（如 per-engine-5.6.3-per1.zip → per-engine-5.6.3-per1）
     *
     * @param fileName 原始文件名
     * @return 去扩展名后的版本号（无扩展名时原样返回）
     */
    private String stripExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx > 0 ? fileName.substring(0, idx) : fileName;
    }
}
