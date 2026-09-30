package com.per.server.controller;

import com.per.server.common.R;
import com.per.server.dto.PageVO;
import com.per.server.dto.ScriptDebugRequest;
import com.per.server.dto.ScriptDebugVO;
import com.per.server.dto.ScriptDetailVO;
import com.per.server.dto.ScriptFormRequest;
import com.per.server.dto.ScriptVersionCreateRequest;
import com.per.server.dto.ScriptVersionVO;
import com.per.server.dto.ScriptVO;
import com.per.server.service.ScriptDebugService;
import com.per.server.service.ScriptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 脚本中心接口：JMX 导入、表单生成、版本管理与查询
 */
@RestController
@RequestMapping("/api/scripts")
@RequiredArgsConstructor
public class ScriptController {

    private final ScriptService scriptService;

    private final ScriptDebugService scriptDebugService;

    /**
     * 分页查询脚本列表
     *
     * @param page    页码，默认 1
     * @param size    每页条数，默认 10
     * @param keyword 关键词（模糊匹配脚本名）
     * @return 脚本分页数据
     */
    @GetMapping
    public R<PageVO<ScriptVO>> page(@RequestParam(defaultValue = "1") long page,
                                    @RequestParam(defaultValue = "10") long size,
                                    @RequestParam(required = false) String keyword) {
        return R.ok(scriptService.page(page, size, keyword));
    }

    /**
     * 导入 JMX 脚本（multipart：jmxFile + name + description + fileIds 可选逗号分隔），保存为版本 1
     *
     * @param jmxFile     上传的 JMX 文件
     * @param name        脚本名称
     * @param description 脚本描述（可选）
     * @param fileIds     关联文件 id（逗号分隔，可选）
     * @return 创建后的脚本信息
     */
    @PostMapping("/import")
    public R<ScriptVO> importScript(@RequestParam("jmxFile") MultipartFile jmxFile,
                                    @RequestParam String name,
                                    @RequestParam(required = false) String description,
                                    @RequestParam(required = false) String fileIds) {
        return R.ok(scriptService.importScript(jmxFile, name, description, fileIds));
    }

    /**
     * 表单生成脚本：服务端渲染表单定义为标准 JMX 并保存为版本 1
     *
     * @param request 表单脚本创建请求
     * @return 创建后的脚本信息
     */
    @PostMapping("/form")
    public R<ScriptVO> createForm(@RequestBody @Valid ScriptFormRequest request) {
        return R.ok(scriptService.createForm(request));
    }

    /**
     * 一键调试：按表单定义逐接口顺序真实请求一次（server 直连目标环境，不落库、不依赖压测节点），
     * 返回每个接口的完整请求/响应/断言/提取明细；串行链路提取的变量向后传递
     *
     * @param request 调试请求（携带编辑器当前 formDef，未保存也可调试）
     * @return 逐接口调试明细
     */
    @PostMapping("/debug")
    public R<ScriptDebugVO> debug(@RequestBody @Valid ScriptDebugRequest request) {
        return R.ok(scriptDebugService.debug(request.getFormDef()));
    }

    /**
     * 查询脚本详情（含版本摘要列表与表单定义）
     *
     * @param id 脚本ID
     * @return 脚本详情
     */
    @GetMapping("/{id}")
    public R<ScriptDetailVO> detail(@PathVariable Long id) {
        return R.ok(scriptService.detail(id));
    }

    /**
     * 获取指定版本的 JMX 文本（直接返回 XML，不走统一响应包装）
     *
     * @param id      脚本ID
     * @param version 版本号
     * @return JMX 文本
     */
    @GetMapping("/{id}/versions/{v}/jmx")
    public ResponseEntity<String> jmx(@PathVariable Long id, @PathVariable("v") Integer version) {
        String jmx = scriptService.getJmx(id, version);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/xml;charset=UTF-8"))
                .body(jmx);
    }

    /**
     * 新增脚本版本（jmxContent 与 formDef 二选一）
     *
     * @param id      脚本ID
     * @param request 版本创建请求
     * @return 新版本摘要
     */
    @PostMapping("/{id}/versions")
    public R<ScriptVersionVO> addVersion(@PathVariable Long id,
                                         @RequestBody @Valid ScriptVersionCreateRequest request) {
        return R.ok(scriptService.addVersion(id, request));
    }

    /**
     * 删除脚本及其全部版本
     *
     * @param id 脚本ID
     * @return 空数据成功响应
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        scriptService.delete(id);
        return R.ok();
    }

    /**
     * 一键复制脚本：深拷贝基本信息与全部版本，新名称自动追加「-副本」后缀
     *
     * @param id 源脚本ID
     * @return 复制出的新脚本信息
     */
    @PostMapping("/{id}/copy")
    public R<ScriptVO> copy(@PathVariable Long id) {
        return R.ok(scriptService.copy(id));
    }
}
