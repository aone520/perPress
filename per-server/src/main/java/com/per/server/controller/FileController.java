package com.per.server.controller;

import com.per.server.common.R;
import com.per.server.dto.FileVO;
import com.per.server.dto.PageVO;
import com.per.server.service.DataFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件库管理接口：CSV/JAR/BIN 上传、分页查询与删除
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final DataFileService dataFileService;

    /**
     * 上传数据文件（fileType 按扩展名自动识别：csv→CSV，jar→JAR，其他→BIN；同 md5 复用记录）
     *
     * @param file 上传文件（multipart 字段名 file）
     * @return 文件元信息 {id,name,fileType,size,md5}
     */
    @PostMapping
    public R<FileVO> upload(@RequestParam("file") MultipartFile file) {
        return R.ok(dataFileService.upload(file));
    }

    /**
     * 分页查询文件列表
     *
     * @param page 页码，默认 1
     * @param size 每页条数，默认 10
     * @return 文件分页数据
     */
    @GetMapping
    public R<PageVO<FileVO>> page(@RequestParam(defaultValue = "1") long page,
                                  @RequestParam(defaultValue = "10") long size) {
        return R.ok(dataFileService.page(page, size));
    }

    /**
     * 删除数据文件记录（磁盘文件保留以供同 md5 复用）
     *
     * @param id 文件ID
     * @return 空数据成功响应
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        dataFileService.delete(id);
        return R.ok();
    }
}
