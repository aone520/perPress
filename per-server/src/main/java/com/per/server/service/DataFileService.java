package com.per.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.per.server.common.BizException;
import com.per.server.common.UserContext;
import com.per.server.dto.FileVO;
import com.per.server.dto.PageVO;
import com.per.server.entity.DataFile;
import com.per.server.mapper.DataFileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 数据文件服务：CSV/JAR/BIN 上传（按 md5 去重存储）、分页查询与删除
 */
@Service
@RequiredArgsConstructor
public class DataFileService {

    private final DataFileMapper dataFileMapper;
    private final FileStorageService storageService;
    private final AuditService auditService;

    /**
     * 上传数据文件：按扩展名识别类型（csv→CSV，txt/tsv/dat→TXT，jar→JAR，其他→BIN），
     * 计算 MD5 落盘到 {storage}/files/{md5}；同 md5 已有记录时直接复用。
     * CSV/TXT 均可作为压测参数文件（脚本 CSV 引用处不限制类型，仅建议选 CSV/TXT）
     *
     * @param file 上传文件
     * @return 文件元信息（id/name/fileType/size/md5）
     */
    public FileVO upload(MultipartFile file) {
        String name = StringUtils.cleanPath(file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename());
        String md5 = storageService.store(file);
        DataFile exist = dataFileMapper.selectOne(new LambdaQueryWrapper<DataFile>()
                .eq(DataFile::getMd5, md5)
                .last("LIMIT 1"));
        if (exist != null) {
            // 类型校正：同内容重复上传时以最新扩展名识别结果为准（历史记录可能被旧版本识别为 BIN）
            String freshType = resolveFileType(name);
            if (!freshType.equals(exist.getFileType()) && !"BIN".equals(freshType)) {
                exist.setFileType(freshType);
                dataFileMapper.updateById(exist);
            }
            return toVO(exist);
        }
        DataFile entity = new DataFile();
        entity.setName(name);
        entity.setFileType(resolveFileType(name));
        entity.setSize(file.getSize());
        entity.setMd5(md5);
        entity.setStoragePath(storageService.filePath(md5).toString());
        entity.setCreateBy(currentUsername());
        dataFileMapper.insert(entity);
        auditService.record("UPLOAD_FILE", "上传数据文件 " + name + "（" + entity.getFileType() + "）");
        return toVO(entity);
    }

    /**
     * 分页查询数据文件列表（按 id 倒序）
     *
     * @param page 页码（从 1 开始）
     * @param size 每页条数
     * @return 文件分页结果
     */
    public PageVO<FileVO> page(long page, long size) {
        Page<DataFile> result = dataFileMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<DataFile>().orderByDesc(DataFile::getId));
        List<FileVO> records = result.getRecords().stream().map(this::toVO).toList();
        return PageVO.of(records, result.getTotal());
    }

    /**
     * 删除数据文件记录（磁盘文件保留以供同 md5 记录复用），记录审计日志
     *
     * @param id 文件ID
     */
    public void delete(Long id) {
        DataFile file = requireById(id);
        dataFileMapper.deleteById(id);
        auditService.record("DELETE_FILE", "删除数据文件 " + file.getName());
    }

    /**
     * 校验文件存在，不存在抛业务异常
     *
     * @param id 文件ID
     * @return 文件实体
     */
    public DataFile requireById(Long id) {
        DataFile file = dataFileMapper.selectById(id);
        if (file == null) {
            throw new BizException("数据文件不存在");
        }
        return file;
    }

    /**
     * 按扩展名识别文件类型：csv→CSV，txt/tsv/dat→TXT，jar→JAR，其他→BIN。
     * CSV/TXT 均可作为压测参数文件被脚本引用
     *
     * @param name 原始文件名
     * @return 文件类型字符串
     */
    private String resolveFileType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".csv")) {
            return "CSV";
        }
        if (lower.endsWith(".txt") || lower.endsWith(".tsv") || lower.endsWith(".dat")) {
            return "TXT";
        }
        if (lower.endsWith(".jar")) {
            return "JAR";
        }
        return "BIN";
    }

    /**
     * 获取当前登录用户名（未登录时返回 null）
     *
     * @return 当前用户名
     */
    private String currentUsername() {
        return UserContext.get() == null ? null : UserContext.get().getUsername();
    }

    /**
     * 文件实体转视图对象
     *
     * @param entity 文件实体
     * @return 文件视图对象
     */
    private FileVO toVO(DataFile entity) {
        FileVO vo = new FileVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setFileType(entity.getFileType());
        vo.setSize(entity.getSize());
        vo.setMd5(entity.getMd5());
        vo.setCreateBy(entity.getCreateBy());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }
}
