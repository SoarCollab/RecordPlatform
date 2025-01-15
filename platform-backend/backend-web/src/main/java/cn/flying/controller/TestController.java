package cn.flying.controller;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @program: RecordPlatform
 * @description:
 * @author: 王贝强
 * @create: 2024-11-01 09:51
 */
@RestController
public class TestController {
    @RequestMapping("/")
    public String index(){
        return "hello world,I'm flying!!!";
    }

    @RequestMapping("/testException")
    public String testException(){
        int i = 1/0;
        return "测试其他异常";
    }

    @RequestMapping("/testBusinessException")
    public String testBusinessException(){
        throw new GeneralException(ResultEnum.RESULT_DATA_NONE);
    }
}
