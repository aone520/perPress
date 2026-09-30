package com.per.server.controller;

import com.per.server.common.R;
import com.per.server.dto.EnginePackageVO;
import com.per.server.service.EnginePackageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 引擎包管理接口：JMeter 引擎 zip 上传、列表与发布
 */
@RestController
@RequestMapping("/api/engines")
@RequiredArgsConstructor
public class EnginePackageController {

    private final EnginePackageService enginePackageService;

    /**
     * 上传引擎包（multipart：file + version 必填 + remark 可选），上传后自动置为当前发布版本
     *
     * @param file    引擎 zip 文件
     * @param version 引擎版本号
     * @param remark  备注（可选）
     * @return 引擎包信息
     */
    @PostMapping
    public R<EnginePackageVO> upload(@RequestParam("file") MultipartFile file,
                                     @RequestParam String version,
                                     @RequestParam(required = false) String remark) {
        return R.ok(enginePackageService.upload(file, version, remark));
    }

    /**
     * 查询引擎包列表
     *
     * @return 引擎包列表（按 id 倒序）
     */
    @GetMapping
    public R<List<EnginePackageVO>> list() {
        return R.ok(enginePackageService.list());
    }

    /**
     * 发布指定引擎包为当前版本（其余清 0）
     *
     * @param id 引擎包ID
     * @return 更新后的引擎包信息
     */
    @PostMapping("/{id}/publish")
    public R<EnginePackageVO> publish(@PathVariable Long id) {
        return R.ok(enginePackageService.publish(id));
    }
}
