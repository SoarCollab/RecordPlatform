package cn.flying.web.controller;

import cn.flying.common.annotation.OperationLog;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 示例控制器，演示操作日志注解的使用
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    /**
     * 查询接口示例
     */
    @GetMapping("/query")
    @OperationLog(module = "示例模块", operationType = "查询", description = "查询示例数据")
    public Map<String, Object> query(@RequestParam(required = false) String param) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "查询成功");
        result.put("data", "查询参数: " + param);
        return result;
    }

    /**
     * 新增接口示例
     */
    @PostMapping("/add")
    @OperationLog(module = "示例模块", operationType = "新增", description = "新增示例数据")
    public Map<String, Object> add(@RequestBody Map<String, Object> params) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "新增成功");
        result.put("data", params);
        return result;
    }

    /**
     * 修改接口示例
     */
    @PutMapping("/update/{id}")
    @OperationLog(module = "示例模块", operationType = "修改", description = "修改示例数据")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> params) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "修改成功");
        result.put("id", id);
        result.put("data", params);
        return result;
    }

    /**
     * 删除接口示例
     */
    @DeleteMapping("/delete/{id}")
    @OperationLog(module = "示例模块", operationType = "删除", description = "删除示例数据")
    public Map<String, Object> delete(@PathVariable String id) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "删除成功");
        result.put("id", id);
        return result;
    }

    /**
     * 异常演示接口
     */
    @GetMapping("/error")
    @OperationLog(module = "示例模块", operationType = "错误", description = "演示错误日志记录")
    public Map<String, Object> error() {
        throw new RuntimeException("模拟业务异常");
    }
} 