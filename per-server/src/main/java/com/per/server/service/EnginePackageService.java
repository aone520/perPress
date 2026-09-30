package com.per.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.per.server.common.BizException;
import com.per.server.dto.EngineVO;
import com.per.server.dto.EnginePackageVO;
import com.per.server.entity.EnginePackage;
import com.per.server.mapper.EnginePackageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 引擎包管理服务：JMeter 引擎 zip 上传、列表、发布（is_current 唯一）与当前引擎信息查询
 */
@Service
@RequiredArgsConstructor
public class EnginePackageService {

    private final EnginePackageMapper enginePackageMapper;
    private final FileStorageService storageService;
    private final AuditService auditService;

    /**
     * 上传引擎包：按 md5 落盘存储，上传后自动置为当前发布版本（其余清 0）
     *
     * @param file    引擎 zip 文件
     * @param version 引擎版本号（必填）
     * @param remark  备注（可选）
     * @return 引擎包信息
     */
    @Transactional(rollbackFor = Exception.class)
    public EnginePackageVO upload(MultipartFile file, String version, String remark) {
        if (file == null || file.isEmpty()) {
            throw new BizException("引擎包文件不能为空");
        }
        if (version == null || version.isBlank()) {
            throw new BizException("version不能为空");
        }
        String md5 = storageService.store(file);
        EnginePackage entity = new EnginePackage();
        entity.setVersion(version.trim());
        entity.setFileName(file.getOriginalFilename());
        entity.setSize(file.getSize());
        entity.setMd5(md5);
        entity.setStoragePath(storageService.filePath(md5).toString());
        entity.setIsCurrent(1);
        entity.setRemark(remark);
        enginePackageMapper.insert(entity);
        clearOtherCurrent(entity.getId());
        auditService.record("UPLOAD_ENGINE", "上传引擎包 v" + version);
        return toVO(entity);
    }

    /**
     * 查询引擎包列表（按 id 倒序）
     *
     * @return 引擎包列表
     */
    public List<EnginePackageVO> list() {
        return enginePackageMapper.selectList(new LambdaQueryWrapper<EnginePackage>()
                        .orderByDesc(EnginePackage::getId))
                .stream().map(this::toVO).toList();
    }

    /**
     * 发布指定引擎包为当前版本（其余清 0），记录审计日志
     *
     * @param id 引擎包ID
     * @return 更新后的引擎包信息
     */
    @Transactional(rollbackFor = Exception.class)
    public EnginePackageVO publish(Long id) {
        EnginePackage pack = enginePackageMapper.selectById(id);
        if (pack == null) {
            throw new BizException("引擎包不存在");
        }
        EnginePackage update = new EnginePackage();
        update.setId(id);
        update.setIsCurrent(1);
        enginePackageMapper.updateById(update);
        clearOtherCurrent(id);
        auditService.record("PUBLISH_ENGINE", "发布引擎包 v" + pack.getVersion() + " 为当前版本");
        return toVO(enginePackageMapper.selectById(id));
    }

    /**
     * 获取当前发布引擎信息（Agent 注册/心跳响应使用，无则返回 null）
     *
     * @return 引擎信息（version/md5/url），未发布任何引擎时为 null
     */
    public EngineVO currentEngine() {
        EnginePackage pack = enginePackageMapper.selectOne(new LambdaQueryWrapper<EnginePackage>()
                .eq(EnginePackage::getIsCurrent, 1)
                .orderByDesc(EnginePackage::getId)
                .last("LIMIT 1"));
        if (pack == null) {
            return null;
        }
        return new EngineVO(pack.getVersion(), pack.getMd5(), "/agent/files/" + pack.getMd5());
    }

    /**
     * 将本地引擎包文件注册为引擎包并置为当前发布版本（启动引导用，不记审计）：
     * 文件导入 md5 存储体系后写 engine_package 记录，其余版本 is_current 清 0
     *
     * @param file    本地引擎包 zip 文件
     * @param version 引擎版本号
     * @param remark  备注
     * @return 注册后的引擎包信息
     */
    @Transactional(rollbackFor = Exception.class)
    public EnginePackageVO registerLocalFile(Path file, String version, String remark) {
        String md5 = storageService.importFile(file);
        EnginePackage entity = new EnginePackage();
        entity.setVersion(version);
        entity.setFileName(file.getFileName().toString());
        try {
            entity.setSize(Files.size(file));
        } catch (IOException e) {
            entity.setSize(0L);
        }
        entity.setMd5(md5);
        entity.setStoragePath(storageService.filePath(md5).toString());
        entity.setIsCurrent(1);
        entity.setRemark(remark);
        enginePackageMapper.insert(entity);
        clearOtherCurrent(entity.getId());
        return toVO(entity);
    }

    /**
     * 将除指定引擎包外的全部记录 is_current 置 0
     *
     * @param currentId 保持为当前版本的引擎包ID
     */
    private void clearOtherCurrent(Long currentId) {
        enginePackageMapper.update(null, new LambdaUpdateWrapper<EnginePackage>()
                .set(EnginePackage::getIsCurrent, 0)
                .ne(EnginePackage::getId, currentId));
    }

    /**
     * 引擎包实体转视图对象
     *
     * @param entity 引擎包实体
     * @return 引擎包视图对象
     */
    private EnginePackageVO toVO(EnginePackage entity) {
        EnginePackageVO vo = new EnginePackageVO();
        vo.setId(entity.getId());
        vo.setVersion(entity.getVersion());
        vo.setFileName(entity.getFileName());
        vo.setSize(entity.getSize());
        vo.setMd5(entity.getMd5());
        vo.setIsCurrent(entity.getIsCurrent());
        vo.setRemark(entity.getRemark());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }
}
