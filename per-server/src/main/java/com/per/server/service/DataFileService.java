package com.per.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.per.server.common.BizException;
import com.per.server.common.UserContext;
import com.per.server.dto.FilePreviewVO;
import com.per.server.dto.FileVO;
import com.per.server.dto.PageVO;
import com.per.server.entity.DataFile;
import com.per.server.mapper.DataFileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    /** 预览最大行数（超出截断，完整内容走下载） */
    private static final int PREVIEW_MAX_LINES = 100;

    /** 单行预览最大字符数（超长行截断，防止巨型单行拖垮前端） */
    private static final int PREVIEW_MAX_LINE_CHARS = 2000;

    /**
     * 文本文件预览：仅支持 CSV/TXT，读取前 100 行（单行超 2000 字符截断）
     *
     * @param id 文件ID
     * @return 预览内容（行列表 + 截断标记）
     */
    public FilePreviewVO preview(Long id) {
        DataFile file = requireById(id);
        if (!"CSV".equals(file.getFileType()) && !"TXT".equals(file.getFileType())) {
            throw new BizException("仅支持 CSV/TXT 文本文件预览，二进制文件请下载后查看");
        }
        Path path = Paths.get(file.getStoragePath());
        if (!Files.exists(path)) {
            throw new BizException("文件内容不存在（磁盘数据可能已被清理）");
        }
        List<String> lines = new java.util.ArrayList<>();
        boolean truncated = false;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lines.size() >= PREVIEW_MAX_LINES) {
                    truncated = true;
                    break;
                }
                lines.add(line.length() > PREVIEW_MAX_LINE_CHARS
                        ? line.substring(0, PREVIEW_MAX_LINE_CHARS) + "…" : line);
            }
        } catch (IOException e) {
            throw new BizException("读取文件失败：" + e.getMessage());
        }
        FilePreviewVO vo = new FilePreviewVO();
        vo.setId(file.getId());
        vo.setName(file.getName());
        vo.setFileType(file.getFileType());
        vo.setSize(file.getSize());
        vo.setLines(lines);
        vo.setTruncated(truncated);
        return vo;
    }

    /**
     * 文件下载：以原始文件名（UTF-8 编码 Content-Disposition）返回文件流
     *
     * @param id 文件ID
     * @return 文件流响应
     */
    public ResponseEntity<Resource> download(Long id) {
        DataFile file = requireById(id);
        Path path = Paths.get(file.getStoragePath());
        if (!Files.exists(path)) {
            throw new BizException("文件内容不存在（磁盘数据可能已被清理）");
        }
        long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            throw new BizException("读取文件失败：" + e.getMessage());
        }
        String encodedName = java.net.URLEncoder.encode(file.getName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(size)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .body(new FileSystemResource(path));
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
